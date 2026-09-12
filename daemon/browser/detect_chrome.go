package browser

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

var errNoChrome = errors.New("no chrome/chromium binary found")

// ChromeDetection is the result of probing the host for a usable Chrome/Chromium.
type ChromeDetection struct {
	Binary       string
	Version      string
	DisplayFound bool
	SuggestMode  LaunchMode
	IsSnap       bool
	Warning      string
}

// chromeBinaryCandidates returns candidate Chrome/Chromium binaries for the OS.
func chromeBinaryCandidates() []string {
	switch runtime.GOOS {
	case "windows":
		pf := os.Getenv("ProgramFiles")
		pf86 := os.Getenv("ProgramFiles(x86)")
		local := os.Getenv("LOCALAPPDATA")
		if pf == "" {
			pf = `C:\Program Files`
		}
		if pf86 == "" {
			pf86 = `C:\Program Files (x86)`
		}
		c := []string{
			filepath.Join(pf, "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(pf86, "Google", "Chrome", "Application", "chrome.exe"),
		}
		if local != "" {
			c = append(c, filepath.Join(local, "Google", "Chrome", "Application", "chrome.exe"))
		}
		return append(c, "chrome.exe", "chrome")
	case "darwin":
		return []string{
			"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
			"/Applications/Chromium.app/Contents/MacOS/Chromium",
			"google-chrome", "chromium",
		}
	default:
		return chromeCandidates
	}
}

// chromeCandidates on Linux, in preference order.
var chromeCandidates = []string{
	"google-chrome",
	"google-chrome-stable",
	"/usr/lib/chromium/chromium",
	"/usr/lib/chromium-browser/chromium-browser",
	"chromium",
	"chromium-browser",
}

// isLikelySnapChrome reports whether a chromium path is the snap wrapper.
func isLikelySnapChrome(path string) bool {
	return strings.Contains(path, "/snap/") ||
		path == "/usr/bin/chromium" || path == "/usr/bin/chromium-browser"
}

// DetectChrome probes for a Chrome/Chromium binary and the display.
func DetectChrome(ctx context.Context) (*ChromeDetection, error) {
	d := &ChromeDetection{}
	candidates := chromeBinaryCandidates()
	for _, cand := range candidates {
		if filepath.IsAbs(cand) {
			if fi, err := os.Stat(cand); err == nil && !fi.IsDir() {
				d.Binary = cand
				break
			}
			continue
		}
		if p, err := exec.LookPath(cand); err == nil {
			d.Binary = p
			break
		}
	}
	if d.Binary == "" {
		return nil, errNoChrome
	}
	vctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if out, err := exec.CommandContext(vctx, d.Binary, "--version").Output(); err == nil {
		d.Version = strings.TrimSpace(string(out))
	}
	d.DisplayFound = os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != "" || sessionDisplay() != ""
	if d.DisplayFound {
		d.SuggestMode = ModeHead
	} else {
		d.SuggestMode = ModeHeadless
	}
	if isLikelySnapChrome(d.Binary) {
		d.IsSnap = true
		d.Warning = "selected Chromium is snap-confined; use a snap-legal --user-data-dir"
	}
	return d, nil
}

// AutomationUserDataDir returns a --user-data-dir for Chrome automation. Chrome
// refuses remote-debugging on the DEFAULT user-data-dir, so we always use a
// dedicated dir; snap chromium additionally needs it under a snap-legal path.
func AutomationUserDataDir(binary, name string) string {
	if isLikelySnapChrome(binary) {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, "snap", "chromium", "common", "chromium", name)
	}
	return filepath.Join(os.TempDir(), name)
}

// DefaultChromeUserDataDir returns the OS-standard "primary" Chrome profile dir.
// Chrome >= 136 HARD-BLOCKS remote-debugging on this directory (see
// https://issues.chromium.org/issues/417456892), and AIOPE must never point
// --user-data-dir here for driving. It is the read-only GOLDEN MASTER: AIOPE
// seeds/refreshes the driveable AIOPE profile FROM it, but never writes TO it.
func DefaultChromeUserDataDir() string {
	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "windows":
		local := os.Getenv("LOCALAPPDATA")
		if local == "" {
			local = filepath.Join(home, "AppData", "Local")
		}
		return filepath.Join(local, "Google", "Chrome", "User Data")
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Google", "Chrome")
	default:
		return filepath.Join(home, ".config", "google-chrome")
	}
}

// AiopeChromeProfileDir is the PERSISTENT, driveable AIOPE Chrome profile — a
// stable working copy (NOT /tmp, NOT the default dir). It accumulates the
// agent's sessions across runs and is the profile that gets seeded/refreshed
// from the default golden master. If it is ever corrupted, Reseed restores it
// from the pristine default with zero risk to the user's real profile.
func AiopeChromeProfileDir() string {
	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "windows":
		local := os.Getenv("LOCALAPPDATA")
		if local == "" {
			local = filepath.Join(home, "AppData", "Local")
		}
		return filepath.Join(local, "Google", "Chrome-aiope")
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Google", "Chrome-aiope")
	default:
		return filepath.Join(home, ".config", "google-chrome-aiope")
	}
}

// isDefaultChromeDir reports whether dir is the default profile.
func isDefaultChromeDir(dir string) bool {
	def := DefaultChromeUserDataDir()
	if def == "" || dir == "" {
		return false
	}
	return filepath.Clean(dir) == filepath.Clean(def)
}

// EnsureAiopeChromeProfile prepares the persistent AIOPE profile for driving and
// returns its path. Behavior:
//   - if it does not exist            -> SEED (full copy from default)
//   - if refresh==true                -> auth-only overlay from default (keeps divergence)
//   - if reseed==true                 -> full wipe + copy from default (recovery/reset)
//   - otherwise                       -> reuse as-is (persist prior sessions)
//
// Auto-staleness: when neither flag is set but the default's Cookies is NEWER
// than the AIOPE profile's, an auth-only refresh is performed automatically.
//
// The caller MUST ensure the AIOPE Chrome is stopped before calling this, and
// ideally the user's Chrome is closed so the golden master's WAL is checkpointed.
func EnsureAiopeChromeProfile(ctx context.Context, refresh, reseed bool) (string, error) {
	src := DefaultChromeUserDataDir()
	dst := AiopeChromeProfileDir()

	_, dstErr := os.Stat(dst)
	exists := dstErr == nil

	switch {
	case reseed || !exists:
		if _, err := os.Stat(src); err != nil {
			return "", fmt.Errorf("no default chrome profile at %s: %w", src, err)
		}
		// A full copy must read a consistent, UNLOCKED source. Close every
		// browser process (any engine, including stray/lingering/crashed ones)
		// holding the master user-data-dir so its SQLite stores (Cookies, Login
		// Data, etc.) are unlocked and WAL is checkpointed before we copy.
		if err := CloseBrowsersOnProfile(ctx, src, 15*time.Second); err != nil {
			return "", fmt.Errorf("close browsers on master chrome profile before copy: %w", err)
		}
		if err := os.RemoveAll(dst); err != nil {
			return "", fmt.Errorf("wipe aiope profile: %w", err)
		}
		if err := copyChromeFull(src, dst); err != nil {
			return "", fmt.Errorf("seed/reseed aiope profile: %w", err)
		}
	case refresh || defaultCookiesNewer(src, dst):
		if err := copyChromeAuthOnly(src, dst); err != nil {
			return "", fmt.Errorf("refresh aiope profile auth: %w", err)
		}
	default:
		// reuse as-is
	}
	return dst, nil
}

// defaultCookiesNewer reports whether the golden master's cookie DB is newer
// than the AIOPE profile's — a cheap staleness heuristic to auto-refresh auth.
func defaultCookiesNewer(src, dst string) bool {
	s := newestCookieMtime(src)
	d := newestCookieMtime(dst)
	return s.After(d)
}

func newestCookieMtime(userDataDir string) time.Time {
	var newest time.Time
	for _, rel := range []string{"Default/Cookies", "Default/Network/Cookies"} {
		if fi, err := os.Stat(filepath.Join(userDataDir, rel)); err == nil {
			if fi.ModTime().After(newest) {
				newest = fi.ModTime()
			}
		}
	}
	return newest
}

// copyChromeAuthOnly overlays ONLY the auth stores from src onto dst, preserving
// dst's accumulated history/divergence (this is "refresh, building on the auto
// db"). SQLite stores are copied consistently (VACUUM INTO) so refresh is safe
// even while Chrome holds the DBs; plain stores (Local State) are byte-copied.
func copyChromeAuthOnly(src, dst string) error {
	if _, err := os.Stat(src); err != nil {
		return fmt.Errorf("no default chrome profile at %s: %w", src, err)
	}
	return copyAuthStores(src, dst, chromeAuthStores)
}

// copyChromeFull copies the whole user-data-dir tree (seed / reseed).
func copyChromeFull(src, dst string) error {
	walkErr := filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // skip unreadable entries rather than abort
		}
		rel, rerr := filepath.Rel(src, path)
		if rerr != nil {
			return nil
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		// skip lock/socket files that shouldn't be copied
		base := filepath.Base(path)
		if base == "SingletonLock" || base == "SingletonSocket" || base == "SingletonCookie" {
			return nil
		}
		_ = copyFileIfExists(path, target)
		return nil
	})
	if walkErr != nil {
		return walkErr
	}
	// Guarantee the auth SQLite stores are transactionally consistent snapshots
	// (VACUUM INTO), regardless of what the tree copy captured. Uniform with the
	// refresh path and with Firefox.
	return copyAuthStores(src, dst, chromeAuthStores)
}

func copyFileIfExists(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// BrowserInfo is one available engine on the host, for the registry/agent context.
type BrowserInfo struct {
	Engine  Engine `json:"engine"`
	Binary  string `json:"binary"`
	Version string `json:"version"`
	IsSnap  bool   `json:"is_snap"`
	Warning string `json:"warning,omitempty"`
}

// HostDetection is the full browser capability of a host, feeding the registry
// and the agent's system context.
type HostDetection struct {
	DisplayFound bool          `json:"display_found"`
	SuggestMode  LaunchMode    `json:"suggest_mode"`
	Browsers     []BrowserInfo `json:"browsers"`
	// Firefox profile info (Chrome uses ephemeral user-data-dirs).
	FirefoxProfiles []FirefoxProfile `json:"firefox_profiles,omitempty"`
}

// DetectAll probes the host for every supported engine.
func DetectAll(ctx context.Context) HostDetection {
	var h HostDetection
	h.DisplayFound = os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != "" || sessionDisplay() != ""
	if h.DisplayFound {
		h.SuggestMode = ModeHead
	} else {
		h.SuggestMode = ModeHeadless
	}
	if ff, err := DetectFirefox(ctx); err == nil {
		h.Browsers = append(h.Browsers, BrowserInfo{
			Engine: EngineFirefox, Binary: ff.Binary, Version: ff.Version,
			IsSnap: ff.IsSnap, Warning: ff.Warning,
		})
		h.FirefoxProfiles = ff.Profiles
	}
	if ch, err := DetectChrome(ctx); err == nil {
		h.Browsers = append(h.Browsers, BrowserInfo{
			Engine: EngineChrome, Binary: ch.Binary, Version: ch.Version,
			IsSnap: ch.IsSnap, Warning: ch.Warning,
		})
	}
	return h
}

// ChromeLaunchOptions configures a Chrome launch for CDP driving.
type ChromeLaunchOptions struct {
	Binary      string
	UserDataDir string
	Mode        LaunchMode
	DebugPort   int
}

// ChromeLaunchResult carries the spawned process and the DevTools HTTP endpoint
// (NewCDPBrowser consumes the http URL, e.g. http://127.0.0.1:9222).
type ChromeLaunchResult struct {
	Cmd         *exec.Cmd
	DevToolsURL string
}

// LaunchChrome starts Chrome/Chromium with remote debugging enabled.
func LaunchChrome(ctx context.Context, opts ChromeLaunchOptions) (*ChromeLaunchResult, error) {
	if opts.DebugPort == 0 {
		opts.DebugPort = 9222
	}
	// GUARD: Chrome >= 136 hard-blocks remote-debugging on the default user-data-dir,
	// and driving the user's primary profile directly is an account-takeover vector.
	// Refuse it explicitly with a clear error rather than let Chrome silently ignore
	// the debug flag (which surfaces as an opaque "DevToolsActivePort" timeout).
	if opts.UserDataDir == "" || isDefaultChromeDir(opts.UserDataDir) {
		return nil, fmt.Errorf("refusing to drive the default Chrome profile (%s): Chrome blocks remote-debugging on it; use a dedicated --user-data-dir (see AiopeChromeProfileDir / EnsureAiopeChromeProfile)", DefaultChromeUserDataDir())
	}
	if err := os.MkdirAll(opts.UserDataDir, 0o700); err != nil {
		return nil, fmt.Errorf("create user-data-dir: %w", err)
	}
	args := []string{
		fmt.Sprintf("--remote-debugging-port=%d", opts.DebugPort),
		"--user-data-dir=" + opts.UserDataDir,
		"--no-first-run",
		"--no-default-browser-check",
	}
	if runtime.GOOS == "linux" {
		// Cookies encrypted as "v11" use the OS keyring (Secret Service). Detect
		// the desktop's actual backend (libsecret / kwallet / none) so the AIOPE
		// profile decrypts the seeded cookies with the SAME key the source used
		// — otherwise Chrome may fall back to a different key and the cookies
		// fail to decrypt (user appears logged out). macOS Keychain / Windows
		// DPAPI need no flag (handled by returning nil there).
		args = append(args, chromePasswordStoreArgs()...)
	}
	if opts.Mode == ModeHeadless {
		args = append(args, "--headless=new")
	}
	args = append(args, "about:blank")

	// Use exec.Command (NOT CommandContext with the request ctx): the browser is
	// a long-lived session process managed by the daemon's ProcessTracker, and
	// must NOT be killed when the launching request's context is cancelled.
	cmd := exec.Command(opts.Binary, args...)
	cmd.Env = os.Environ()
	// Always attach the OS keyring env (D-Bus session) so Chrome can decrypt
	// keyring-encrypted "v11" cookies — needed in headless mode too.
	cmd.Env = append(cmd.Env, SessionKeyringEnv()...)
	if opts.Mode != ModeHeadless {
		cmd.Env = append(cmd.Env, ResolveActiveDisplay().EnvPairs()...)
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("launch chrome: %w", err)
	}

	devtoolsURL := fmt.Sprintf("http://127.0.0.1:%d", opts.DebugPort)
	if err := waitForChromeDevTools(ctx, devtoolsURL, 30*time.Second); err != nil {
		_ = cmd.Process.Kill()
		return nil, err
	}
	return &ChromeLaunchResult{Cmd: cmd, DevToolsURL: devtoolsURL}, nil
}

// waitForChromeDevTools polls the DevTools /json/version endpoint until ready.
func waitForChromeDevTools(ctx context.Context, devtoolsURL string, wait time.Duration) error {
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		c, cancel := context.WithTimeout(ctx, 2*time.Second)
		req, err := http.NewRequestWithContext(c, http.MethodGet, devtoolsURL+"/json/version", nil)
		if err == nil {
			if resp, derr := http.DefaultClient.Do(req); derr == nil {
				resp.Body.Close()
				cancel()
				if resp.StatusCode == 200 {
					return nil
				}
				time.Sleep(250 * time.Millisecond)
				continue
			}
		}
		cancel()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(250 * time.Millisecond):
		}
	}
	return fmt.Errorf("timed out waiting for chrome devtools at %s", devtoolsURL)
}
