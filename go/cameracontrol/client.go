// Package cameracontrol implements a synchronous, reconnecting client for the
// Camera Control JSON WebSocket API (protocol version 4).
//
// Requests are serialized on one authenticated connection. Status and
// capture-session polling reconnect automatically with bounded exponential
// backoff. A capture job reuses a stable request ID when it is replayed after
// a dropped socket so the running server can deduplicate it.
package cameracontrol

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"time"
)

// ProtocolVersion is the only Camera Control protocol this client speaks.
const ProtocolVersion = 4

// Object is one decoded JSON object.
type Object = map[string]any

var terminalJobStates = map[string]bool{"completed": true, "failed": true, "cancelled": true}

// TerminalSessionStates holds the capture-session states that end a session.
var TerminalSessionStates = map[string]bool{"completed": true, "failed": true, "cancelled": true}

// APIError is a structured error returned by the Camera Control API.
type APIError struct {
	Code    string
	Message string
	Details any
}

func (e *APIError) Error() string { return fmt.Sprintf("API error %s: %s", e.Code, e.Message) }

// ProtocolError reports a malformed or unsupported protocol message.
type ProtocolError struct{ Message string }

func (e *ProtocolError) Error() string { return e.Message }

func protocolErrorf(format string, args ...any) *ProtocolError {
	return &ProtocolError{Message: fmt.Sprintf(format, args...)}
}

// ConnectionInterruptedError reports that the connection could not be restored
// within the configured number of attempts.
type ConnectionInterruptedError struct {
	Action string
	Err    error
}

func (e *ConnectionInterruptedError) Error() string {
	return fmt.Sprintf("connection interrupted while performing %q: %v", e.Action, e.Err)
}

func (e *ConnectionInterruptedError) Unwrap() error { return e.Err }

// Conn is the minimal text-message WebSocket surface the client needs. It is
// an interface so tests can drive the client without a network.
type Conn interface {
	Send(ctx context.Context, message []byte) error
	Receive(ctx context.Context) ([]byte, error)
	Close() error
}

// DialFunc opens one WebSocket connection with a verified TLS configuration.
type DialFunc func(ctx context.Context, endpoint string, tlsConfig *tls.Config) (Conn, error)

// Options configures a Client. The zero value of every optional field is
// replaced by the documented default.
type Options struct {
	// Password is the shared Web/API password shown by the app.
	Password string
	// CAFile is the verified Camera Control local CA certificate. It is
	// required unless TLSConfig is supplied.
	CAFile string
	// TLSConfig overrides the CA-file based configuration.
	TLSConfig *tls.Config
	// Timeout bounds a single request or event wait. Default: 30s.
	Timeout time.Duration
	// ReconnectAttempts bounds idempotent retries. Default: 5.
	ReconnectAttempts int
	// ReconnectDelay is the first backoff delay. Default: 1s.
	ReconnectDelay time.Duration
	// Dial overrides the WebSocket dialer. Default: DialWebSocket.
	Dial DialFunc
}

// Client is one authenticated Camera Control connection. It is not safe for
// concurrent use by multiple goroutines.
type Client struct {
	url           string
	options       Options
	conn          Conn
	events        []Object
	requestNumber int
}

// New validates the endpoint and returns a client that is not yet connected.
func New(endpoint string, options Options) (*Client, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return nil, fmt.Errorf("invalid URL %q: %w", endpoint, err)
	}
	if parsed.Scheme != "wss" {
		return nil, errors.New("Camera Control requires a wss:// URL")
	}
	if options.Timeout == 0 {
		options.Timeout = 30 * time.Second
	}
	if options.Timeout <= 0 {
		return nil, errors.New("timeout must be positive")
	}
	if options.ReconnectAttempts == 0 {
		options.ReconnectAttempts = 5
	}
	if options.ReconnectAttempts < 0 {
		return nil, errors.New("reconnect attempts cannot be negative")
	}
	if options.ReconnectDelay == 0 {
		options.ReconnectDelay = time.Second
	}
	if options.Dial == nil {
		options.Dial = DialWebSocket
	}
	return &Client{url: endpoint, options: options}, nil
}

// Connected reports whether a socket is currently retained.
func (c *Client) Connected() bool { return c.conn != nil }

// Connect opens, validates protocol v4, and authenticates one WSS connection.
func (c *Client) Connect(ctx context.Context) error {
	c.Close()
	tlsConfig := c.options.TLSConfig
	if tlsConfig == nil {
		loaded, err := tlsConfigFromCA(c.options.CAFile)
		if err != nil {
			return err
		}
		tlsConfig = loaded
	}

	openTimeout := c.options.Timeout
	if openTimeout > 10*time.Second {
		openTimeout = 10 * time.Second
	}
	dialCtx, cancel := context.WithTimeout(ctx, openTimeout)
	defer cancel()
	conn, err := c.options.Dial(dialCtx, c.url, tlsConfig)
	if err != nil {
		return err
	}
	c.conn = conn

	if err := c.handshake(ctx); err != nil {
		c.Close()
		return err
	}
	return nil
}

func (c *Client) handshake(ctx context.Context) error {
	hello, err := c.receive(ctx, c.options.Timeout)
	if err != nil {
		return err
	}
	if hello["event"] != "hello" || !isProtocolVersion(hello["protocolVersion"]) {
		return protocolErrorf("unsupported server greeting: %v", hello)
	}
	authenticated, err := c.requestOnce(ctx, "authenticate",
		Object{"password": c.options.Password}, c.options.Timeout)
	if err != nil {
		return err
	}
	if !isProtocolVersion(authenticated["protocolVersion"]) {
		return protocolErrorf("authentication returned an unsupported protocol: %v", authenticated)
	}
	return nil
}

// Close closes the current socket without discarding queued events.
func (c *Client) Close() {
	conn := c.conn
	c.conn = nil
	if conn != nil {
		_ = conn.Close()
	}
}

// Request sends one action and returns its result object. Pass
// retryOnDisconnect=false for non-idempotent operations such as the legacy
// direct capture action.
func (c *Client) Request(ctx context.Context, action string, parameters Object, retryOnDisconnect bool) (Object, error) {
	attempts := 0
	if retryOnDisconnect {
		attempts = c.options.ReconnectAttempts
	}
	var lastErr error
	for attempt := 0; attempt <= attempts; attempt++ {
		if c.conn == nil {
			if err := c.Connect(ctx); err != nil {
				if isPermanent(err) {
					return nil, err
				}
				lastErr = err
				if attempt >= attempts {
					break
				}
				if err := sleepBackoff(ctx, c.options.ReconnectDelay, attempt); err != nil {
					return nil, err
				}
				continue
			}
		}
		result, err := c.requestOnce(ctx, action, parameters, c.options.Timeout)
		if err == nil {
			return result, nil
		}
		if isPermanent(err) {
			return nil, err
		}
		lastErr = err
		c.Close()
		if attempt >= attempts {
			break
		}
		if err := sleepBackoff(ctx, c.options.ReconnectDelay, attempt); err != nil {
			return nil, err
		}
	}
	return nil, &ConnectionInterruptedError{Action: action, Err: lastErr}
}

// Status returns camera, device, capture-session, and reliability status.
func (c *Client) Status(ctx context.Context) (Object, error) {
	return c.Request(ctx, "status", Object{}, true)
}

// CaptureOptions holds the capture settings shared by jobs and sessions.
type CaptureOptions struct {
	Camera     int
	Resolution string
	Flash      string
	Controls   Object
	// RequestID is the stable idempotency key. One is generated when empty.
	RequestID string
}

func (o CaptureOptions) normalized() CaptureOptions {
	if o.Camera == 0 {
		o.Camera = 1
	}
	if o.Resolution == "" {
		o.Resolution = "high"
	}
	if o.Flash == "" {
		o.Flash = "off"
	}
	if o.Controls == nil {
		o.Controls = Object{}
	}
	return o
}

// CaptureJob captures once through the idempotent job API and waits for the
// job to reach a terminal state.
func (c *Client) CaptureJob(ctx context.Context, options CaptureOptions) (Object, error) {
	options = options.normalized()
	requestID := options.RequestID
	if requestID == "" {
		requestID = "go-" + randomID()
	}
	parameters := Object{
		"requestId":  requestID,
		"camera":     options.Camera,
		"resolution": options.Resolution,
		"flash":      options.Flash,
		"controls":   options.Controls,
	}
	for resume := 0; resume <= c.options.ReconnectAttempts; resume++ {
		result, err := c.Request(ctx, "captureJob", parameters, true)
		if err != nil {
			return nil, err
		}
		job, err := objectField(result["job"], "captureJob result.job")
		if err != nil {
			return nil, err
		}
		if state, _ := job["state"].(string); terminalJobStates[state] {
			return job, nil
		}
		jobID := job["id"]
		event, err := c.WaitForEvent(ctx, "captureJob", func(event Object) bool {
			pending, ok := event["job"].(map[string]any)
			if !ok || pending["id"] != jobID {
				return false
			}
			state, _ := pending["state"].(string)
			return terminalJobStates[state]
		}, c.options.Timeout)
		if err == nil {
			return objectField(event["job"], "captureJob event.job")
		}
		var interrupted *ConnectionInterruptedError
		if !errors.As(err, &interrupted) {
			return nil, err
		}
		// Re-submit the same semantic request ID. The running app
		// deduplicates it and returns the retained job snapshot.
		if resume >= c.options.ReconnectAttempts {
			return nil, err
		}
	}
	return nil, &ConnectionInterruptedError{Action: "captureJob", Err: errors.New("recovery attempts exhausted")}
}

// TimeLapseOptions describes a time-lapse capture session.
type TimeLapseOptions struct {
	CaptureOptions
	IntervalSeconds float64
	// Count stops the session after this many photos. Zero means unbounded.
	Count int
}

// StartTimeLapse starts a time-lapse capture session and returns its snapshot.
func (c *Client) StartTimeLapse(ctx context.Context, options TimeLapseOptions) (Object, error) {
	capture := options.CaptureOptions.normalized()
	plan := Object{"mode": "timeLapse", "intervalSeconds": options.IntervalSeconds}
	if options.Count > 0 {
		plan["count"] = options.Count
	}
	result, err := c.Request(ctx, "startCaptureSession", Object{
		"camera":     capture.Camera,
		"resolution": capture.Resolution,
		"flash":      capture.Flash,
		"controls":   capture.Controls,
		"plan":       plan,
	}, false)
	if err != nil {
		return nil, err
	}
	return objectField(result["session"], "startCaptureSession result.session")
}

// CaptureSessionStatus polls the latest retained capture session across
// reconnects.
func (c *Client) CaptureSessionStatus(ctx context.Context, sessionID string) (Object, error) {
	status, err := c.Status(ctx)
	if err != nil {
		return nil, err
	}
	session, ok := status["captureSession"].(map[string]any)
	if !ok || session["id"] != sessionID {
		return nil, &ProtocolError{Message: "the capture session is no longer retained by the server"}
	}
	return session, nil
}

// CancelCaptureSession cancels one retained time-lapse/capture session.
func (c *Client) CancelCaptureSession(ctx context.Context, sessionID string) (Object, error) {
	result, err := c.Request(ctx, "cancelCaptureSession", Object{"sessionId": sessionID}, true)
	if err != nil {
		return nil, err
	}
	return objectField(result["session"], "cancelCaptureSession result.session")
}

// WaitForEvent returns the next matching unsolicited event. A nil predicate
// matches any event with the given name.
func (c *Client) WaitForEvent(ctx context.Context, name string, predicate func(Object) bool, timeout time.Duration) (Object, error) {
	if timeout <= 0 {
		timeout = c.options.Timeout
	}
	deadline := time.Now().Add(timeout)
	matches := func(event Object) bool {
		return event["event"] == name && (predicate == nil || predicate(event))
	}
	for index, event := range c.events {
		if matches(event) {
			c.events = append(c.events[:index], c.events[index+1:]...)
			return event, nil
		}
	}
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return nil, &ConnectionInterruptedError{
				Action: name,
				Err:    fmt.Errorf("timed out waiting for %q", name),
			}
		}
		message, err := c.receive(ctx, remaining)
		if err != nil {
			if isPermanent(err) {
				return nil, err
			}
			c.Close()
			return nil, &ConnectionInterruptedError{Action: name, Err: err}
		}
		if _, ok := message["event"]; !ok {
			return nil, protocolErrorf("unexpected response while waiting: %v", message)
		}
		if matches(message) {
			return message, nil
		}
		c.events = append(c.events, message)
	}
}

func (c *Client) requestOnce(ctx context.Context, action string, parameters Object, timeout time.Duration) (Object, error) {
	conn := c.conn
	if conn == nil {
		return nil, errors.New("no active Camera Control connection")
	}
	c.requestNumber++
	requestID := fmt.Sprintf("go-%d", c.requestNumber)
	payload := Object{"id": requestID, "action": action}
	for key, value := range parameters {
		payload[key] = value
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	sendCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if err := conn.Send(sendCtx, encoded); err != nil {
		return nil, err
	}

	deadline := time.Now().Add(timeout)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return nil, fmt.Errorf("timed out waiting for %q", action)
		}
		response, err := c.receive(ctx, remaining)
		if err != nil {
			return nil, err
		}
		if _, ok := response["event"]; ok {
			c.events = append(c.events, response)
			continue
		}
		if response["id"] != requestID {
			return nil, protocolErrorf("unexpected response id: %v", response["id"])
		}
		if ok, _ := response["ok"].(bool); !ok {
			apiError, isObject := response["error"].(map[string]any)
			if !isObject {
				return nil, protocolErrorf("malformed API error: %v", response)
			}
			code, _ := apiError["code"].(string)
			message, _ := apiError["message"].(string)
			if code == "" {
				code = "unknown"
			}
			if message == "" {
				message = "no message"
			}
			return nil, &APIError{Code: code, Message: message, Details: apiError["details"]}
		}
		return objectField(response["result"], action+" result")
	}
}

func (c *Client) receive(ctx context.Context, timeout time.Duration) (Object, error) {
	conn := c.conn
	if conn == nil {
		return nil, errors.New("no active Camera Control connection")
	}
	readCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	message, err := conn.Receive(readCtx)
	if err != nil {
		return nil, err
	}
	var decoded any
	if err := json.Unmarshal(message, &decoded); err != nil {
		return nil, &ProtocolError{Message: "the server returned invalid JSON"}
	}
	return objectField(decoded, "WebSocket message")
}

// DecodePhoto validates and decodes a JPEG photo object returned by the API.
func DecodePhoto(photo Object) ([]byte, error) {
	if photo["mimeType"] != "image/jpeg" {
		return nil, protocolErrorf("unexpected photo metadata: %v", photo)
	}
	encoded, ok := photo["dataBase64"].(string)
	if !ok {
		return nil, &ProtocolError{Message: "the photo doesn't contain Base64 JPEG data"}
	}
	jpeg, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, &ProtocolError{Message: "the photo contains invalid Base64 data"}
	}
	return jpeg, nil
}

func tlsConfigFromCA(caFile string) (*tls.Config, error) {
	if caFile == "" {
		return nil, errors.New("a CA certificate is required without an explicit TLS configuration")
	}
	pem, err := os.ReadFile(caFile)
	if err != nil {
		return nil, fmt.Errorf("CA certificate not found: %s", caFile)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		return nil, fmt.Errorf("no certificate found in %s", caFile)
	}
	return &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}, nil
}

func objectField(value any, label string) (Object, error) {
	object, ok := value.(map[string]any)
	if !ok {
		return nil, protocolErrorf("%s isn't a JSON object", label)
	}
	return object, nil
}

// isPermanent reports whether retrying an operation cannot help.
func isPermanent(err error) bool {
	var apiError *APIError
	var protocolError *ProtocolError
	return errors.As(err, &apiError) || errors.As(err, &protocolError) ||
		errors.Is(err, context.Canceled)
}

func isProtocolVersion(value any) bool {
	number, ok := value.(float64)
	return ok && int(number) == ProtocolVersion
}

func sleepBackoff(ctx context.Context, base time.Duration, attempt int) error {
	delay := base << attempt
	if delay > 10*time.Second {
		delay = 10 * time.Second
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
