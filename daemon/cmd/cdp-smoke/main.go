// Command cdp-smoke proves the Chrome/CDP path.
//
// Two modes:
//
//	# 1) attach to an already-running Chrome (proves CDP navigate + eval):
//	google-chrome --remote-debugging-port=9223 --user-data-dir=/tmp/chrome-cdp --headless=new about:blank
//	go run ./cmd/cdp-smoke -devtools http://127.0.0.1:9223 -url https://example.com
//
//	# 2) auth-share: seed the PERSISTENT AIOPE profile from your real Chrome,
//	#    launch it, and verify a logged-in page. Proves cookies/auth carry over.
//	#    (Close your normal Chrome first so cookies are checkpointed.)
//	go run ./cmd/cdp-smoke -authshare -url https://github.com/settings/profile
//	#    add -reseed for a full reset, or -refresh for auth-only refresh.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/mafredri/cdp"
	"github.com/mafredri/cdp/devtool"
	"github.com/mafredri/cdp/protocol/page"
	"github.com/mafredri/cdp/protocol/runtime"
	"github.com/mafredri/cdp/rpcc"

	"github.com/xnet-ngo/aiope-remote/browser"
)

func main() {
	var (
		devtoolsURL = flag.String("devtools", "http://127.0.0.1:9223", "Chrome DevTools HTTP endpoint (attach mode)")
		url         = flag.String("url", "https://example.com", "URL to navigate to")
		evalExpr    = flag.String("eval", "JSON.stringify({url: location.href, title: document.title})", "JS expression to evaluate")
		authshare   = flag.Bool("authshare", false, "seed+launch the persistent AIOPE Chrome profile from the real profile")
		refresh     = flag.Bool("refresh", false, "authshare: auth-only refresh from the real profile")
		reseed      = flag.Bool("reseed", false, "authshare: full reset from the real profile")
		headless    = flag.Bool("headless", false, "authshare: force headless")
	)
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	var err error
	if *authshare {
		err = runAuthShare(ctx, *url, *evalExpr, *refresh, *reseed, *headless)
	} else {
		err = runAttach(ctx, *devtoolsURL, *url, *evalExpr)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "\nCDP SMOKE FAILED: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("\nCDP SMOKE PASSED")
}

// runAuthShare exercises EnsureAiopeChromeProfile + LaunchChrome + drive.
func runAuthShare(ctx context.Context, url, evalExpr string, refresh, reseed, headless bool) error {
	det, err := browser.DetectChrome(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("[detect] chrome=%s version=%q snap=%v\n", det.Binary, det.Version, det.IsSnap)
	fmt.Printf("[master] default profile (read-only): %s\n", browser.DefaultChromeUserDataDir())

	mode := "seed/reuse"
	if reseed {
		mode = "reseed(full)"
	} else if refresh {
		mode = "refresh(auth-only)"
	}
	fmt.Printf("[profile] preparing AIOPE profile (%s): %s\n", mode, browser.AiopeChromeProfileDir())
	dir, err := browser.EnsureAiopeChromeProfile(ctx, refresh, reseed)
	if err != nil {
		return fmt.Errorf("prepare aiope profile: %w", err)
	}
	fmt.Printf("[profile] ready: %s\n", dir)

	launchMode := det.SuggestMode
	if headless {
		launchMode = browser.ModeHeadless
	}
	fmt.Printf("[launch] starting chrome (%s)...\n", launchMode)
	lr, err := browser.LaunchChrome(ctx, browser.ChromeLaunchOptions{
		Binary:      det.Binary,
		UserDataDir: dir,
		Mode:        launchMode,
		DebugPort:   9223,
	})
	if err != nil {
		return err
	}
	defer func() {
		if lr.Cmd != nil && lr.Cmd.Process != nil {
			_ = lr.Cmd.Process.Kill()
		}
	}()
	fmt.Printf("[launch] devtools: %s\n", lr.DevToolsURL)
	return driveCDP(ctx, lr.DevToolsURL, url, evalExpr)
}

func runAttach(ctx context.Context, devtoolsURL, url, evalExpr string) error {
	fmt.Printf("[cdp] attach: %s\n", devtoolsURL)
	return driveCDP(ctx, devtoolsURL, url, evalExpr)
}

func driveCDP(ctx context.Context, devtoolsURL, url, evalExpr string) error {
	devt := devtool.New(devtoolsURL)
	pt, err := devt.Get(ctx, devtool.Page)
	if err != nil {
		pt, err = devt.Create(ctx)
		if err != nil {
			return fmt.Errorf("get/create page target: %w", err)
		}
	}
	conn, err := rpcc.DialContext(ctx, pt.WebSocketDebuggerURL)
	if err != nil {
		return fmt.Errorf("rpcc dial: %w", err)
	}
	defer conn.Close()
	c := cdp.NewClient(conn)
	fmt.Printf("[cdp] connected\n")

	domContent, err := c.Page.DOMContentEventFired(ctx)
	if err != nil {
		return err
	}
	defer domContent.Close()
	if err := c.Page.Enable(ctx); err != nil {
		return err
	}

	fmt.Printf("[nav] navigating to %s...\n", url)
	if _, err := c.Page.Navigate(ctx, page.NewNavigateArgs(url)); err != nil {
		return err
	}
	if _, err := domContent.Recv(); err != nil {
		return fmt.Errorf("wait DOMContentLoaded: %w", err)
	}

	fmt.Printf("[eval] %s\n", evalExpr)
	res, err := c.Runtime.Evaluate(ctx, runtime.NewEvaluateArgs(evalExpr).SetReturnByValue(true).SetAwaitPromise(true))
	if err != nil {
		return err
	}
	if res.ExceptionDetails != nil {
		return fmt.Errorf("eval threw: %v", res.ExceptionDetails)
	}
	fmt.Printf("[eval] result: %s\n", string(res.Result.Value))
	fmt.Printf("\n[proof] navigate + Runtime.Evaluate via CDP, no CSP block. If the URL is an authenticated page (not a login redirect), auth carried from the real profile.\n")
	return nil
}
