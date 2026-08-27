// Command camera-control controls Camera Control through its verified-TLS WSS
// API: status, idempotent single capture, and cancellable time lapse.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"flutterdev.app/camera_control_github/go/cameracontrol"
)

var (
	resolutions = []string{"low", "medium", "high", "veryHigh", "ultraHigh", "max"}
	flashModes  = []string{"off", "auto", "always", "torch"}
)

type connectionFlags struct {
	ca                string
	passwordEnv       string
	timeout           float64
	reconnectAttempts int
}

type captureFlags struct {
	camera      int
	resolution  string
	flash       string
	jpegQuality int
}

func main() {
	if err := run(os.Args[1:]); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			os.Exit(2)
		}
		fmt.Fprintf(os.Stderr, "camera-control: %v\n", err)
		os.Exit(1)
	}
}

func usage() error {
	fmt.Fprint(os.Stderr, `Control Camera Control through its verified-TLS WSS API.

usage: camera-control <command> <wss-url> [options]

commands:
  status       print camera, device, capture-session, and reliability status
  capture      capture and save one JPEG through the idempotent job API
  time-lapse   start, poll, and cancel a time-lapse capture session

Run "camera-control <command> --help" for the options of one command.
`)
	return flag.ErrHelp
}

func run(argv []string) error {
	if len(argv) == 0 {
		return usage()
	}
	command := argv[0]
	if command == "-h" || command == "--help" || command == "help" {
		return usage()
	}

	rest := argv[1:]
	endpoint := ""
	if len(rest) > 0 && !strings.HasPrefix(rest[0], "-") {
		endpoint, rest = rest[0], rest[1:]
	}

	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	connection := addConnectionFlags(flags)
	var capture captureFlags
	var compact bool
	var output, requestID string
	var interval, duration, poll float64
	var count int

	switch command {
	case "status":
		flags.BoolVar(&compact, "compact", false, "print compact JSON")
	case "capture":
		addCaptureFlags(flags, &capture)
		flags.StringVar(&output, "output", "photo.jpg", "JPEG output path")
		flags.StringVar(&requestID, "request-id", "", "stable idempotency key; generated when omitted")
	case "time-lapse":
		addCaptureFlags(flags, &capture)
		flags.Float64Var(&interval, "interval", 0, "seconds between photos (required)")
		flags.IntVar(&count, "count", 0, "stop after this many photos")
		flags.Float64Var(&duration, "duration", 0, "cancel after this many seconds")
		flags.Float64Var(&poll, "poll", 1.0, "status poll interval in seconds")
	default:
		return fmt.Errorf("unknown command %q", command)
	}
	if err := flags.Parse(rest); err != nil {
		return err
	}
	if endpoint == "" {
		return errors.New("the wss:// URL shown by the app is required")
	}
	if connection.ca == "" {
		return errors.New("--ca is required")
	}

	if command != "status" {
		if err := validateChoice("--resolution", capture.resolution, resolutions); err != nil {
			return err
		}
		if err := validateChoice("--flash", capture.flash, flashModes); err != nil {
			return err
		}
	}
	controls, err := controlsFrom(capture)
	if err != nil {
		return err
	}
	password, err := readPassword(connection.passwordEnv)
	if err != nil {
		return err
	}

	client, err := cameracontrol.New(endpoint, cameracontrol.Options{
		Password:          password,
		CAFile:            connection.ca,
		Timeout:           time.Duration(connection.timeout * float64(time.Second)),
		ReconnectAttempts: connection.reconnectAttempts,
	})
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := client.Connect(ctx); err != nil {
		return err
	}
	defer client.Close()

	switch command {
	case "status":
		return runStatus(ctx, client, compact)
	case "capture":
		return runCapture(ctx, client, capture, controls, output, requestID)
	default:
		return runTimeLapse(ctx, stop, client, capture, controls, interval, count, duration, poll)
	}
}

func addConnectionFlags(flags *flag.FlagSet) *connectionFlags {
	connection := &connectionFlags{}
	flags.StringVar(&connection.ca, "ca", "", "verified Camera Control local CA certificate")
	flags.StringVar(&connection.passwordEnv, "password-env", "CAMERA_CONTROL_PASSWORD",
		"environment variable containing the password")
	flags.Float64Var(&connection.timeout, "timeout", 30, "request timeout in seconds")
	flags.IntVar(&connection.reconnectAttempts, "reconnect-attempts", 5, "idempotent retry attempts")
	return connection
}

func addCaptureFlags(flags *flag.FlagSet, capture *captureFlags) {
	flags.IntVar(&capture.camera, "camera", 1, "camera index")
	flags.StringVar(&capture.resolution, "resolution", "high",
		"one of "+strings.Join(resolutions, ", "))
	flags.StringVar(&capture.flash, "flash", "off", "one of "+strings.Join(flashModes, ", "))
	flags.IntVar(&capture.jpegQuality, "jpeg-quality", 0, "JPEG quality between 1 and 100")
}

func runStatus(ctx context.Context, client *cameracontrol.Client, compact bool) error {
	status, err := client.Status(ctx)
	if err != nil {
		return err
	}
	return printJSON(status, compact)
}

func runCapture(
	ctx context.Context,
	client *cameracontrol.Client,
	capture captureFlags,
	controls cameracontrol.Object,
	output string,
	requestID string,
) error {
	if requestID == "" {
		requestID = cameracontrol.NewRequestID("go-cli")
	}
	job, err := client.CaptureJob(ctx, cameracontrol.CaptureOptions{
		Camera:     capture.camera,
		Resolution: capture.resolution,
		Flash:      capture.flash,
		Controls:   controls,
		RequestID:  requestID,
	})
	if err != nil {
		return err
	}
	if job["state"] != "completed" {
		message := ""
		if failure, ok := job["error"].(map[string]any); ok {
			message, _ = failure["message"].(string)
		}
		return fmt.Errorf("capture ended as %v: %s", job["state"], message)
	}
	result, ok := job["result"].(map[string]any)
	if !ok {
		return errors.New("completed capture job has no result")
	}
	photo, ok := result["photo"].(map[string]any)
	if !ok {
		return errors.New("completed capture job has no photo")
	}
	jpeg, err := cameracontrol.DecodePhoto(photo)
	if err != nil {
		return err
	}
	if parent := filepath.Dir(output); parent != "." {
		if err := os.MkdirAll(parent, 0o755); err != nil {
			return err
		}
	}
	if err := os.WriteFile(output, jpeg, 0o644); err != nil {
		return err
	}
	fmt.Printf("Saved %d bytes to %s (requestId=%s)\n", len(jpeg), output, requestID)
	return nil
}

func runTimeLapse(
	ctx context.Context,
	stop context.CancelFunc,
	client *cameracontrol.Client,
	capture captureFlags,
	controls cameracontrol.Object,
	interval float64,
	count int,
	duration float64,
	poll float64,
) error {
	if interval < 1 || interval > 86400 {
		return errors.New("--interval must be between 1 and 86400 seconds")
	}
	if count != 0 && (count < 2 || count > 1000) {
		return errors.New("--count must be between 2 and 1000")
	}
	if duration < 0 {
		return errors.New("--duration must be positive")
	}
	if poll <= 0 {
		return errors.New("--poll must be positive")
	}

	session, err := client.StartTimeLapse(ctx, cameracontrol.TimeLapseOptions{
		CaptureOptions: cameracontrol.CaptureOptions{
			Camera:     capture.camera,
			Resolution: capture.resolution,
			Flash:      capture.flash,
			Controls:   controls,
		},
		IntervalSeconds: interval,
		Count:           count,
	})
	if err != nil {
		return err
	}
	sessionID, _ := session["id"].(string)
	fmt.Printf("Started %s; press Ctrl+C to stop.\n", sessionID)

	var deadline time.Time
	if duration > 0 {
		deadline = time.Now().Add(time.Duration(duration * float64(time.Second)))
	}
	lastSignature := ""
	// Cancellation still needs a live connection, so the polling loop owns the
	// interrupt instead of letting it tear the context down.
	cancelled := false
	for !cameracontrol.TerminalSessionStates[stringField(session["state"])] {
		signature := fmt.Sprintf("%v/%v", session["state"], session["capturedCount"])
		if signature != lastSignature {
			target := "∞"
			if session["targetCount"] != nil {
				target = fmt.Sprintf("%v", session["targetCount"])
			}
			fmt.Printf("%v: %v/%s\n", session["state"], session["capturedCount"], target)
			lastSignature = signature
		}
		if !deadline.IsZero() && !time.Now().Before(deadline) {
			cancelled = true
			break
		}
		select {
		case <-ctx.Done():
			fmt.Fprintln(os.Stderr, "\nCancellation requested…")
			cancelled = true
		case <-time.After(time.Duration(poll * float64(time.Second))):
		}
		if cancelled {
			break
		}
		session, err = client.CaptureSessionStatus(ctx, sessionID)
		if err != nil {
			return err
		}
	}
	if cancelled {
		stop() // Restore the default interrupt handling for a second Ctrl+C.
		session, err = client.CancelCaptureSession(context.Background(), sessionID)
		if err != nil {
			return err
		}
	}

	if err := printJSON(session, false); err != nil {
		return err
	}
	state := stringField(session["state"])
	if state != "completed" && state != "cancelled" {
		return fmt.Errorf("session ended as %s", state)
	}
	return nil
}

func validateChoice(name, value string, allowed []string) error {
	for _, candidate := range allowed {
		if candidate == value {
			return nil
		}
	}
	return fmt.Errorf("%s must be one of %s", name, strings.Join(allowed, ", "))
}

func controlsFrom(capture captureFlags) (cameracontrol.Object, error) {
	if capture.jpegQuality == 0 {
		return cameracontrol.Object{}, nil
	}
	if capture.jpegQuality < 1 || capture.jpegQuality > 100 {
		return nil, errors.New("--jpeg-quality must be between 1 and 100")
	}
	return cameracontrol.Object{"jpegQuality": capture.jpegQuality}, nil
}

func printJSON(value any, compact bool) error {
	var encoded []byte
	var err error
	if compact {
		encoded, err = json.Marshal(value)
	} else {
		encoded, err = json.MarshalIndent(value, "", "  ")
	}
	if err != nil {
		return err
	}
	fmt.Println(string(encoded))
	return nil
}

func stringField(value any) string {
	text, _ := value.(string)
	return text
}

// readPassword prefers the environment variable so that the password never
// appears in process arguments, and prompts without echo otherwise.
func readPassword(variable string) (string, error) {
	if password, ok := os.LookupEnv(variable); ok {
		return password, nil
	}
	fmt.Fprint(os.Stderr, "Camera Control password: ")
	restore := disableEcho()
	defer restore()
	var password string
	if _, err := fmt.Scanln(&password); err != nil && password == "" {
		return "", errors.New("no password was supplied")
	}
	fmt.Fprintln(os.Stderr)
	return password, nil
}

func disableEcho() func() {
	if err := exec.Command("stty", "-F", "/dev/tty", "-echo").Run(); err != nil {
		// BSD/macOS stty reads the terminal from standard input instead.
		command := exec.Command("stty", "-echo")
		command.Stdin = os.Stdin
		if command.Run() != nil {
			return func() {}
		}
	}
	return func() {
		command := exec.Command("stty", "echo")
		command.Stdin = os.Stdin
		_ = command.Run()
	}
}
