package cameracontrol

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/coder/websocket"
)

// DialWebSocket is the default dialer. It performs the WSS handshake with the
// supplied verified TLS configuration and disables the read size limit so that
// Base64 JPEG payloads arrive intact.
func DialWebSocket(ctx context.Context, endpoint string, tlsConfig *tls.Config) (Conn, error) {
	httpClient := &http.Client{
		Transport: &http.Transport{
			TLSClientConfig:     tlsConfig,
			TLSHandshakeTimeout: 10 * time.Second,
			Proxy:               nil,
		},
	}
	connection, _, err := websocket.Dial(ctx, endpoint, &websocket.DialOptions{
		HTTPClient: httpClient,
	})
	if err != nil {
		return nil, err
	}
	connection.SetReadLimit(-1)
	return &webSocketConn{connection: connection}, nil
}

type webSocketConn struct {
	connection *websocket.Conn
}

func (c *webSocketConn) Send(ctx context.Context, message []byte) error {
	return c.connection.Write(ctx, websocket.MessageText, message)
}

func (c *webSocketConn) Receive(ctx context.Context) ([]byte, error) {
	messageType, data, err := c.connection.Read(ctx)
	if err != nil {
		return nil, err
	}
	if messageType != websocket.MessageText {
		return nil, &ProtocolError{Message: "the server returned a non-JSON WebSocket message"}
	}
	return data, nil
}

func (c *webSocketConn) Close() error {
	return c.connection.Close(websocket.StatusNormalClosure, "")
}

// NewRequestID returns a fresh idempotency key for a capture job.
func NewRequestID(prefix string) string { return prefix + "-" + randomID() }

// randomID returns a random RFC 4122 version 4 identifier.
func randomID() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic(errors.New("the system random source is unavailable"))
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", bytes[0:4], bytes[4:6], bytes[6:8], bytes[8:10], bytes[10:16])
}
