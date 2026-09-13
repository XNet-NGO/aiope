package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/log"
	"github.com/charmbracelet/ssh"

	"github.com/xnet-ngo/aiope-remote/browser"
)

const browserCmdPrefix = "__aiope_browser__"

// browserRequest is the JSON payload passed as the single argument after the
// verb, e.g.  __aiope_browser__navigate {"url":"https://example.com"}
type browserRequest struct {
	URL      string `json:"url,omitempty"`
	Selector string `json:"selector,omitempty"`
	Value    string `json:"value,omitempty"`
	Script   string `json:"script,omitempty"`
	Offset   int    `json:"offset,omitempty"`
	Limit    int    `json:"limit,omitempty"`
	Dir      string `json:"dir,omitempty"`
	Px       int    `json:"px,omitempty"`
	Engine   string `json:"engine,omitempty"`  // "firefox" | "chrome" for start
	Mode     string `json:"mode,omitempty"`    // "headed" | "headless" — explicit override; default = headed with auto-fallback to headless when no active display
	ShareSession *bool `json:"share_session,omitempty"` // both engines: drive the persistent AIOPE profile seeded from the user's real profile (auth sharing). DEFAULT true (omitted) — pass false for a clean throwaway profile.
	Refresh  bool   `json:"refresh,omitempty"` // both engines: auth-only refresh of the AIOPE profile from the real profile (implies share_session)
	Reseed   bool   `json:"reseed,omitempty"`  // both engines: full wipe+recopy of the AIOPE profile from the real profile (implies share_session)
	Confirm  bool   `json:"confirm,omitempty"` // firewall allow:once
	Allow    string `json:"allow,omitempty"`   // "session" to persist for session
	// form_action_host lets the caller/agent declare the submit target host for
	// firewall evaluation on fill/click; empty = same origin as page.
	FormActionHost string `json:"form_action_host,omitempty"`
}

// sharesSession reports the effective share_session value: it DEFAULTS TO TRUE
// (drive the seeded auth-sharing profile) when the flag is omitted; an explicit
// false opts into a clean throwaway profile. refresh/reseed always force true.
func (r browserRequest) sharesSession() bool {
	if r.Refresh || r.Reseed {
		return true
	}
	if r.ShareSession == nil {
		return true // default: share
	}
	return *r.ShareSession
}

// browserResponse is the JSON envelope returned on stdout for every verb.
type browserResponse struct {
	Status  string          `json:"status"` // "ok" | "error" | "permission_required"
	Result  string          `json:"result,omitempty"`
	Data    json.RawMessage `json:"data,omitempty"`
	Error   string          `json:"error,omitempty"`
	// firewall fields when status == permission_required
	Reason   string `json:"reason,omitempty"`
	PageHost string `json:"page_host,omitempty"`
	FormHost string `json:"form_host,omitempty"`
}

// browserSession holds the live browser + its launched process for reaping.
type browserSession struct {
	br       browser.Browser
	launched *browser.LaunchResult
	fw       *browser.Firewall
	// connCtx governs the browser connection lifetime (session-scoped, NOT tied
	// to any single SSH request). connCancel tears it down on stop.
	connCtx    context.Context
	connCancel context.CancelFunc
}

// browserManager is the daemon-global singleton holding the active session.
// Each SSH exec is a separate connection, so browser state must live here, not
// per-session.
type browserManager struct {
	mu      sync.Mutex
	session *browserSession
}

var globalBrowserMgr = &browserManager{}

// handleBrowser dispatches a __aiope_browser__<verb> [json] command.
func handleBrowser(sess ssh.Session, cmdStr string, tracker *ProcessTracker) {
	verb, payload := parseBrowserCmd(cmdStr)
	var req browserRequest
	if payload != "" {
		if err := json.Unmarshal([]byte(payload), &req); err != nil {
			writeBrowserResp(sess, browserResponse{Status: "error", Error: fmt.Sprintf("bad json payload: %v", err)})
			return
		}
	}

	log.Info("Browser", "verb", verb, "user", sess.User())
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	resp := globalBrowserMgr.dispatch(ctx, verb, req, tracker)
	writeBrowserResp(sess, resp)
}

func parseBrowserCmd(cmdStr string) (verb, payload string) {
	rest := strings.TrimPrefix(cmdStr, browserCmdPrefix)
	rest = strings.TrimSpace(rest)
	if i := strings.IndexByte(rest, ' '); i >= 0 {
		return rest[:i], strings.TrimSpace(rest[i+1:])
	}
	return rest, ""
}

func (m *browserManager) dispatch(ctx context.Context, verb string, req browserRequest, tracker *ProcessTracker) browserResponse {
	m.mu.Lock()
	defer m.mu.Unlock()

	switch verb {
	case "detect":
		return m.doDetect(ctx)
	case "start":
		return m.doStart(ctx, req, tracker)
	case "stop":
		return m.doStop()
	}

	// All remaining verbs require an active session.
	if m.session == nil {
		return browserResponse{Status: "error", Error: "no active browser session; call __aiope_browser__start first"}
	}
	s := m.session

	switch verb {
	case "navigate":
		if err := s.br.Navigate(ctx, req.URL); err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Result: "navigated to " + req.URL}
	case "content":
		txt, err := s.br.Content(ctx, req.Offset, req.Limit)
		if err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Result: txt}
	case "elements":
		el, err := s.br.Elements(ctx)
		if err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Result: el}
	case "eval":
		v, err := s.br.Eval(ctx, req.Script)
		if err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Data: json.RawMessage(v)}
	case "click":
		if d := m.gate(ctx, s, req); !d.Allowed {
			return gateResp(d)
		}
		r, err := s.br.Click(ctx, req.Selector)
		if err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Result: r}
	case "fill":
		if d := m.gate(ctx, s, req); !d.Allowed {
			return gateResp(d)
		}
		r, err := s.br.Fill(ctx, req.Selector, req.Value)
		if err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Result: r}
	case "screenshot":
		img, err := s.br.Screenshot(ctx)
		if err != nil {
			return errResp(err)
		}
		b64, _ := json.Marshal(base64.StdEncoding.EncodeToString(img))
		return browserResponse{Status: "ok", Data: b64}
	case "back":
		ok, err := s.br.Back(ctx)
		if err != nil {
			return errResp(err)
		}
		if ok {
			return browserResponse{Status: "ok", Result: "navigated back"}
		}
		return browserResponse{Status: "ok", Result: "no history"}
	case "scroll":
		dir := browser.ScrollDown
		if req.Dir == "up" {
			dir = browser.ScrollUp
		}
		px := req.Px
		if px == 0 {
			px = 500
		}
		r, err := s.br.Scroll(ctx, dir, px)
		if err != nil {
			return errResp(err)
		}
		return browserResponse{Status: "ok", Result: r}
	case "status":
		st, err := s.br.Status(ctx)
		if err != nil {
			return errResp(err)
		}
		data, _ := json.Marshal(st)
		return browserResponse{Status: "ok", Data: data}
	default:
		return browserResponse{Status: "error", Error: "unknown browser verb: " + verb}
	}
}

// gate applies the firewall to a fill/click, deriving the page host from the
// current status and using the caller-declared form-action host.
func (m *browserManager) gate(ctx context.Context, s *browserSession, req browserRequest) browser.Decision {
	if req.Allow == "session" {
		// caller opted to persist allow for the page host for this session
		st, _ := s.br.Status(ctx)
		s.fw.AllowSession(hostOf(st.URL))
	}
	st, err := s.br.Status(ctx)
	pageHost := ""
	if err == nil {
		pageHost = hostOf(st.URL)
	}
	return s.fw.CheckSubmit(pageHost, req.FormActionHost, req.Confirm)
}

func (m *browserManager) doDetect(ctx context.Context) browserResponse {
	host := browser.DetectAll(ctx)
	data, _ := json.Marshal(host)
	return browserResponse{Status: "ok", Data: data}
}

func (m *browserManager) doStart(ctx context.Context, req browserRequest, tracker *ProcessTracker) browserResponse {
	if m.session != nil {
		return browserResponse{Status: "ok", Result: "session already active (" + string(m.session.br.Engine()) + ")"}
	}
	// Ensure no stale browser from a prior/crashed session is holding the debug
	// port (a leftover Chrome on 9222 caused Firefox BiDi "bad handshake").
	browser.FreeDebugPort(9222)
	switch strings.ToLower(req.Engine) {
	case "chrome", "chromium":
		return m.startChrome(ctx, req, tracker)
	case "", "firefox":
		return m.startFirefox(ctx, req, tracker)
	default:
		return browserResponse{Status: "error", Error: "unknown engine: " + req.Engine}
	}
}

// resolveMode decides the launch mode from an explicit request override and the
// current display availability. Default policy: HEADED, but auto-fall-back to
// HEADLESS when no active graphical session is resolvable. An explicit
// req.Mode ("headed"/"headless") always wins (with an honest note if headed is
// requested but no display exists). Returns the mode and a human note.
func resolveMode(reqMode string) (browser.LaunchMode, string) {
	displayUsable := browser.ResolveActiveDisplay().Usable()
	switch strings.ToLower(strings.TrimSpace(reqMode)) {
	case "headless":
		return browser.ModeHeadless, "headless (requested)"
	case "headed", "head":
		if displayUsable {
			return browser.ModeHead, "headed (requested)"
		}
		return browser.ModeHeadless, "headless (requested headed, but no active display — fell back)"
	default:
		if displayUsable {
			return browser.ModeHead, "headed (default)"
		}
		return browser.ModeHeadless, "headless (default; no active display)"
	}
}

func (m *browserManager) startFirefox(ctx context.Context, req browserRequest, tracker *ProcessTracker) browserResponse {
	det, err := browser.DetectFirefox(ctx)
	if err != nil {
		return errResp(err)
	}
	// Profile selection is 1:1 with Chrome: share_session (or refresh/reseed)
	// drives the persistent AIOPE profile SEEDED from the user's real logged-in
	// profile (golden master, never driven; auth/history carry over). Without
	// any of those flags, use a clean throwaway automation profile. seed/refresh/
	// reseed semantics are identical across engines.
	var profile string
	if req.sharesSession() {
		// Pick the ACTUALLY-USED master (most cookies), not merely the first
		// profile — the first can be an empty throwaway while the logged-in one
		// is elsewhere.
		master := browser.SelectMasterFirefoxProfile(det.Profiles, browser.AiopeFirefoxProfileDir(det.Binary))
		p, perr := browser.EnsureAiopeFirefoxProfile(ctx, det.Binary, master, req.Refresh, req.Reseed)
		if perr != nil {
			return errResp(perr)
		}
		profile = p
	} else {
		profile = browser.AutomationProfileDir(det.Binary, "aiope-firefox-auto")
	}
	// Kill anything holding the driven profile (not the user's master), then launch.
	procs, _ := browser.FindRunningFirefox(ctx, profile)
	if len(procs) > 0 {
		_ = browser.TerminateAndWait(ctx, procs, profile, 15*time.Second)
	}
	mode, modeNote := resolveMode(req.Mode)
	lr, err := browser.Launch(ctx, browser.LaunchOptions{
		Binary:         det.Binary,
		ProfilePath:    profile,
		Mode:           mode,
		DebugPort:      9222,
		RestoreSession: true,
	})
	if err != nil {
		return errResp(err)
	}
	if lr.Cmd != nil && lr.Cmd.Process != nil {
		tracker.Track(lr.Cmd.Process)
	}
	connCtx, connCancel := context.WithCancel(context.Background())
	conn := browser.NewBiDiConnection(lr.BiDiURL)
	if err := conn.Connect(connCtx); err != nil {
		connCancel()
		return errResp(err)
	}
	bb := browser.NewBiDiBrowser(conn)
	// Establish the BiDi session (session.new + subscribe). Without this,
	// commands fail with "invalid session id" even though the WS handshake
	// succeeded. Use a short request-scoped ctx for the setup calls.
	if est, ok := bb.(interface {
		EstablishSession(context.Context) error
	}); ok {
		if err := est.EstablishSession(ctx); err != nil {
			connCancel()
			return errResp(fmt.Errorf("bidi session setup: %w", err))
		}
	}
	m.session = &browserSession{
		br:         bb,
		launched:   lr,
		fw:         browser.NewFirewall(),
		connCtx:    connCtx,
		connCancel: connCancel,
	}
	return browserResponse{Status: "ok", Result: "started firefox (" + modeNote + ")"}
}

func (m *browserManager) startChrome(ctx context.Context, req browserRequest, tracker *ProcessTracker) browserResponse {
	det, err := browser.DetectChrome(ctx)
	if err != nil {
		return errResp(err)
	}
	var userDataDir string
	if req.sharesSession() {
		// Persistent AIOPE Chrome profile: seeded from the user's real profile
		// (golden master, never driven), reused across sessions, refreshed
		// (auth-only) or reseeded (full) on request. Corruption is always
		// recoverable by reseed since the default profile is untouched.
		dir, cerr := browser.EnsureAiopeChromeProfile(ctx, req.Refresh, req.Reseed)
		if cerr != nil {
			return errResp(fmt.Errorf("aiope chrome profile prep failed: %w", cerr))
		}
		userDataDir = dir
	} else {
		userDataDir = browser.AutomationUserDataDir(det.Binary, "aiope-chrome-auto")
	}
	mode, modeNote := resolveMode(req.Mode)
	lr, err := browser.LaunchChrome(ctx, browser.ChromeLaunchOptions{
		Binary:      det.Binary,
		UserDataDir: userDataDir,
		Mode:        mode,
		DebugPort:   9222,
	})
	if err != nil {
		return errResp(err)
	}
	if lr.Cmd != nil && lr.Cmd.Process != nil {
		tracker.Track(lr.Cmd.Process)
	}
	// Session-lifetime context for the connection (survives past this request).
	connCtx, connCancel := context.WithCancel(context.Background())
	br, err := browser.NewCDPBrowser(connCtx, ctx, lr.DevToolsURL)
	if err != nil {
		connCancel()
		return errResp(err)
	}
	m.session = &browserSession{
		br:         br,
		launched:   &browser.LaunchResult{Cmd: lr.Cmd},
		fw:         browser.NewFirewall(),
		connCtx:    connCtx,
		connCancel: connCancel,
	}
	return browserResponse{Status: "ok", Result: "started chrome (" + modeNote + ")"}
}

func (m *browserManager) doStop() browserResponse {
	if m.session == nil {
		return browserResponse{Status: "ok", Result: "no active session"}
	}
	_ = m.session.br.Close()
	if m.session.connCancel != nil {
		m.session.connCancel()
	}
	if m.session.launched != nil && m.session.launched.Cmd != nil && m.session.launched.Cmd.Process != nil {
		_ = m.session.launched.Cmd.Process.Kill()
	}
	m.session = nil
	return browserResponse{Status: "ok", Result: "stopped"}
}

func errResp(err error) browserResponse {
	return browserResponse{Status: "error", Error: err.Error()}
}

func gateResp(d browser.Decision) browserResponse {
	return browserResponse{
		Status:   d.Status,
		Reason:   d.Reason,
		PageHost: d.PageHost,
		FormHost: d.FormHost,
	}
}

func hostOf(rawurl string) string {
	s := rawurl
	if i := strings.Index(s, "://"); i >= 0 {
		s = s[i+3:]
	}
	if i := strings.IndexByte(s, '/'); i >= 0 {
		s = s[:i]
	}
	return s
}

func writeBrowserResp(sess ssh.Session, resp browserResponse) {
	data, err := json.Marshal(resp)
	if err != nil {
		fmt.Fprintf(sess.Stderr(), "marshal error: %v\n", err)
		sess.Exit(1)
		return
	}
	sess.Write(data)
	if resp.Status == "error" {
		sess.Exit(1)
		return
	}
	sess.Exit(0)
}
