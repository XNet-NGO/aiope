// Command browser-smoke validates the step-1 premise end to end:
//
//	detect firefox -> (kill running instance on the profile) -> relaunch with BiDi
//	-> connect locally -> navigate -> script.evaluate (CSP-immune) -> getTree.
//
// It is intentionally standalone (not wired into the SSH daemon yet) so the core
// can be proven before building the browser subsystem.
//
// Usage:
//
//	go run ./cmd/browser-smoke [-url https://example.com] [-headless] [-profile /path]
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/xnet-ngo/aiope-remote/browser"
)

func main() {
	var (
		url         = flag.String("url", "https://github.com", "URL to navigate to")
		headless    = flag.Bool("headless", false, "force headless mode")
		profileFlag = flag.String("profile", "", "explicit profile path (else first discovered)")
		port        = flag.Int("port", 9222, "remote debugging port")
		evalExpr    = flag.String("eval", "JSON.stringify({url: location.href, title: document.title})", "JS expression to evaluate after navigation")
		connectURL  = flag.String("connect", "", "drive an ALREADY-RUNNING BiDi endpoint at this ws:// URL (skips detect/launch; use for Chrome or a pre-launched browser)")
	)
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	// -connect mode: skip detect/launch, drive an existing BiDi endpoint.
	if *connectURL != "" {
		fmt.Printf("[connect] using existing BiDi endpoint: %s\n", *connectURL)
		if err := drive(ctx, *connectURL, *url, *evalExpr); err != nil {
			fmt.Fprintf(os.Stderr, "\nSMOKE FAILED: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("\nSMOKE PASSED")
		return
	}

	if err := run(ctx, *url, *headless, *profileFlag, *port, *evalExpr); err != nil {
		fmt.Fprintf(os.Stderr, "\nSMOKE FAILED: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("\nSMOKE PASSED")
}

func run(ctx context.Context, url string, headless bool, profileFlag string, port int, evalExpr string) error {
	// 1. Detect
	det, err := browser.DetectFirefox(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("[detect] binary=%s version=%q display=%v suggest=%s profiles=%d\n",
		det.Binary, det.Version, det.DisplayFound, det.SuggestMode, len(det.Profiles))
	for _, p := range det.Profiles {
		fmt.Printf("         profile %q path=%s locked=%v\n", p.Name, p.Path, p.IsLocked)
	}

	// 2. Choose profile
	profilePath := profileFlag
	if profilePath == "" {
		if len(det.Profiles) == 0 {
			return fmt.Errorf("no profiles discovered; pass -profile to point at one")
		}
		profilePath = det.Profiles[0].Path
	}
	fmt.Printf("[profile] using %s\n", profilePath)

	// 3. Determine mode
	mode := det.SuggestMode
	if headless {
		mode = browser.ModeHeadless
	}
	fmt.Printf("[mode] %s\n", mode)

	// 4. Kill any running firefox on this profile, wait for lock release
	procs, err := browser.FindRunningFirefox(ctx, profilePath)
	if err != nil {
		return fmt.Errorf("find running firefox: %w", err)
	}
	fmt.Printf("[kill] running instances on profile: %d\n", len(procs))
	if len(procs) > 0 {
		if err := browser.TerminateAndWait(ctx, procs, profilePath, 15*time.Second); err != nil {
			return fmt.Errorf("terminate running firefox: %w", err)
		}
		fmt.Printf("[kill] terminated; lock released\n")
	}

	// 5. Launch with BiDi
	fmt.Printf("[launch] starting firefox (%s) with BiDi on port %d...\n", mode, port)
	lr, err := browser.Launch(ctx, browser.LaunchOptions{
		Binary:         det.Binary,
		ProfilePath:    profilePath,
		Mode:           mode,
		DebugPort:      port,
		RestoreSession: true,
	})
	if err != nil {
		return fmt.Errorf("launch: %w", err)
	}
	defer func() {
		if lr.Cmd != nil && lr.Cmd.Process != nil {
			_ = lr.Cmd.Process.Kill()
		}
	}()
	fmt.Printf("[launch] BiDi endpoint: %s\n", lr.BiDiURL)

	// 6. Connect BiDi
	fmt.Printf("[launch] BiDi endpoint: %s\n", lr.BiDiURL)

	// 6+. Connect and drive.
	return drive(ctx, lr.BiDiURL, url, evalExpr)
}

// drive connects to a BiDi endpoint and runs the navigate + eval proof sequence.
// Used both after launching Firefox and by -connect (e.g. to test Chrome).
func drive(ctx context.Context, bidiURL, url, evalExpr string) error {
	conn := browser.NewBiDiConnection(bidiURL)
	if err := conn.Connect(ctx); err != nil {
		return fmt.Errorf("bidi connect: %w", err)
	}
	defer conn.Close()
	fmt.Printf("[bidi] connected\n")

	// session.new (some builds require an explicit session)
	if _, err := conn.SendCommand(ctx, "session.new", map[string]any{
		"capabilities": map[string]any{},
	}, 15*time.Second); err != nil {
		// Not fatal on Firefox (BiDi session may already exist); log and continue.
		fmt.Printf("[bidi] session.new: %v (continuing)\n", err)
	}

	// getTree -> first context (restored tab)
	treeRaw, err := conn.SendCommand(ctx, "browsingContext.getTree", map[string]any{}, 15*time.Second)
	if err != nil {
		return fmt.Errorf("getTree: %w", err)
	}
	var tree struct {
		Contexts []struct {
			Context string `json:"context"`
			URL     string `json:"url"`
		} `json:"contexts"`
	}
	if err := json.Unmarshal(treeRaw, &tree); err != nil {
		return fmt.Errorf("parse tree: %w", err)
	}
	fmt.Printf("[tree] restored contexts (tabs): %d\n", len(tree.Contexts))
	for i, c := range tree.Contexts {
		fmt.Printf("       [%d] %s\n", i, c.URL)
	}
	if len(tree.Contexts) == 0 {
		return fmt.Errorf("no browsing contexts")
	}
	target := tree.Contexts[0].Context

	// navigate
	fmt.Printf("[nav] navigating to %s...\n", url)
	if _, err := conn.SendCommand(ctx, "browsingContext.navigate", map[string]any{
		"context": target,
		"url":     url,
		"wait":    "complete",
	}, 30*time.Second); err != nil {
		return fmt.Errorf("navigate: %w", err)
	}

	// script.evaluate — the CSP-immunity proof
	fmt.Printf("[eval] evaluating %q (out-of-page-context)...\n", evalExpr)
	evalRaw, err := conn.SendCommand(ctx, "script.evaluate", map[string]any{
		"expression":   evalExpr,
		"target":       map[string]any{"context": target},
		"awaitPromise": true,
	}, 20*time.Second)
	if err != nil {
		return fmt.Errorf("script.evaluate: %w", err)
	}
	var evalRes struct {
		Type   string `json:"type"`
		Result struct {
			Type  string `json:"type"`
			Value any    `json:"value"`
		} `json:"result"`
		ExceptionDetails json.RawMessage `json:"exceptionDetails"`
	}
	if err := json.Unmarshal(evalRaw, &evalRes); err != nil {
		return fmt.Errorf("parse eval result: %w", err)
	}
	if len(evalRes.ExceptionDetails) > 0 {
		return fmt.Errorf("eval threw: %s", string(evalRes.ExceptionDetails))
	}
	fmt.Printf("[eval] result type=%s value=%v\n", evalRes.Result.Type, evalRes.Result.Value)

	fmt.Printf("\n[proof] navigate + out-of-context script.evaluate succeeded with no CSP block.\n")
	return nil
}
