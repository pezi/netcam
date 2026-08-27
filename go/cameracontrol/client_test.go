package cameracontrol

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

// fakeConn replays scripted server messages and answers requests the way the
// app does, so the handshake and event queue can be exercised offline.
type fakeConn struct {
	messages []Object
	sent     []Object
	closed   bool
}

func (c *fakeConn) Send(_ context.Context, message []byte) error {
	var payload Object
	if err := json.Unmarshal(message, &payload); err != nil {
		return err
	}
	c.sent = append(c.sent, payload)
	switch payload["action"] {
	case "authenticate":
		c.messages = append([]Object{{
			"id": payload["id"], "ok": true,
			"result": Object{"protocolVersion": ProtocolVersion},
		}}, c.messages...)
	case "status":
		c.messages = append(c.messages,
			Object{"event": "captureSession", "session": Object{"id": "session-1", "state": "waiting"}},
			Object{"id": payload["id"], "ok": true, "result": Object{"ready": true}},
		)
	}
	return nil
}

func (c *fakeConn) Receive(context.Context) ([]byte, error) {
	if len(c.messages) == 0 {
		return nil, errors.New("the connection was closed by the peer")
	}
	message := c.messages[0]
	c.messages = c.messages[1:]
	return json.Marshal(message)
}

func (c *fakeConn) Close() error {
	c.closed = true
	return nil
}

func newTestClient(t *testing.T, conn *fakeConn) *Client {
	t.Helper()
	client, err := New("wss://192.168.1.50:8080/ws", Options{
		Password:  "secret123",
		TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12},
		Timeout:   time.Second,
		Dial: func(context.Context, string, *tls.Config) (Conn, error) {
			return conn, nil
		},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return client
}

func TestAuthenticatesProtocolV4AndQueuesEvents(t *testing.T) {
	conn := &fakeConn{messages: []Object{{"event": "hello", "protocolVersion": ProtocolVersion}}}
	client := newTestClient(t, conn)
	ctx := context.Background()
	if err := client.Connect(ctx); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	status, err := client.Status(ctx)
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	if status["ready"] != true {
		t.Fatalf("unexpected status: %v", status)
	}
	event, err := client.WaitForEvent(ctx, "captureSession", nil, 100*time.Millisecond)
	if err != nil {
		t.Fatalf("WaitForEvent: %v", err)
	}
	session := event["session"].(map[string]any)
	if session["id"] != "session-1" {
		t.Fatalf("unexpected event: %v", event)
	}
	if conn.sent[0]["action"] != "authenticate" {
		t.Fatalf("the first request must authenticate, got %v", conn.sent[0])
	}
	client.Close()
	if !conn.closed {
		t.Fatal("Close must close the socket")
	}
}

func TestRejectsAPIErrors(t *testing.T) {
	conn := &fakeConn{messages: []Object{
		{"event": "hello", "protocolVersion": ProtocolVersion},
		{"id": "go-2", "ok": false, "error": Object{"code": "camera_busy", "message": "Busy"}},
	}}
	client := newTestClient(t, conn)
	ctx := context.Background()
	if err := client.Connect(ctx); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	_, err := client.Request(ctx, "cancelCaptureSession", Object{"sessionId": "session-1"}, false)
	var apiError *APIError
	if !errors.As(err, &apiError) {
		t.Fatalf("expected an APIError, got %v", err)
	}
	if apiError.Code != "camera_busy" {
		t.Fatalf("unexpected error code: %s", apiError.Code)
	}
}

func TestRejectsOldProtocolServers(t *testing.T) {
	conn := &fakeConn{messages: []Object{{"event": "hello", "protocolVersion": 1}}}
	client := newTestClient(t, conn)
	err := client.Connect(context.Background())
	var protocolError *ProtocolError
	if !errors.As(err, &protocolError) {
		t.Fatalf("expected a ProtocolError, got %v", err)
	}
}

func TestDecodePhotoRejectsForeignMedia(t *testing.T) {
	if _, err := DecodePhoto(Object{"mimeType": "image/png"}); err == nil {
		t.Fatal("a non-JPEG photo must be rejected")
	}
	jpeg, err := DecodePhoto(Object{"mimeType": "image/jpeg", "dataBase64": "/9j/4AAQ"})
	if err != nil {
		t.Fatalf("DecodePhoto: %v", err)
	}
	if len(jpeg) != 6 || jpeg[0] != 0xff || jpeg[1] != 0xd8 {
		t.Fatalf("unexpected JPEG bytes: %x", jpeg)
	}
}

func TestRequiresSecureURL(t *testing.T) {
	if _, err := New("ws://192.168.1.50:8080/ws", Options{Password: "x"}); err == nil {
		t.Fatal("a ws:// URL must be rejected")
	}
}
