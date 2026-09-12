package browser

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

// LaunchMode selects headed (shared with the user) vs headless (unattended).
type LaunchMode string

const (
	ModeHead     LaunchMode = "head"
	ModeHeadless LaunchMode = "headless"
)

// FirefoxProfile is a discovered Gecko profile.
type FirefoxProfile struct {
	Name     string
	Path     string
	IsLocked bool
}

// Detection is the result of probing the host for a usable Firefox.
type Detection struct {
	Binary       string
	Version      string
	Profiles     []FirefoxProfile
	DisplayFound bool
	SuggestMode  LaunchMode
	IsSnap       bool   // selected binary is the snap wrapper (driving discouraged)
	Warning      string // human-readable caveat, if any
}

// firefoxBinaryCandidates returns candidate firefox binaries in preference
// order for the current OS. Linux prefers unconfined builds before the snap
// wrapper (see notes below).
func firefoxBinaryCandidates() []string {
	return firefoxBinaryCandidatesFor(runtime.GOOS, os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)"))
}

// firefoxBinaryCandidatesFor returns the candidate list for a given OS, taking
// the Windows Program Files env values explicitly so all OS branches are unit-
// testable regardless of the host platform.
func firefoxBinaryCandidatesFor(goos, programFiles, programFiles86 string) []string {
	switch goos {
	case "windows":
		pf := programFiles
		pf86 := programFiles86
		if pf == "" {
			pf = `C:\Program Files`
		}
		if pf86 == "" {
			pf86 = `C:\Program Files (x86)`
		}
		return []string{
			filepath.Join(pf, "Mozilla Firefox", "firefox.exe"),
			filepath.Join(pf86, "Mozilla Firefox", "firefox.exe"),
			"firefox.exe",
			"firefox",
		}
	case "darwin":
		return []string{
			"/Applications/Firefox.app/Contents/MacOS/firefox",
			"/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox",
			"/Applications/Firefox Nightly.app/Contents/MacOS/firefox",
			"firefox",
		}
	default:
		return firefoxCandidates
	}
}

// candidate firefox binaries on Linux, in preference order.
//
// UNCONFINED IS PREFERRED. Snap Firefox is problematic for BiDi driving on two
// counts observed in testing: (1) its sandbox refuses profiles outside the snap
// dir (e.g. /tmp), and (2) it runs a shared supervisory process model where
// SIGTERM'ing "the firefox on this profile" can cascade into unrelated Firefox
// instances / the caller's process group. Distro/tarball/ESR builds do not have
// these issues, so we probe common unconfined locations before falling back to
// whatever `firefox` on PATH resolves to (which is often the snap wrapper).
var firefoxCandidates = []string{
	// Explicit unconfined install locations (distro packages, Mozilla tarballs).
	// Channel builds package under /usr/lib/firefox-<channel>/firefox on most
	// distros (Debian/Ubuntu/Mozilla apt repo) and /opt on others.
	"/usr/lib/firefox-devedition/firefox",
	"/usr/lib/firefox-beta/firefox",
	"/usr/lib/firefox-nightly/firefox",
	"/usr/lib/firefox/firefox",
	"/usr/lib64/firefox/firefox",
	"/opt/firefox-devedition/firefox",
	"/opt/firefox-beta/firefox",
	"/opt/firefox-nightly/firefox",
	"/opt/firefox/firefox",
	// Channel-specific launcher names on PATH (Mozilla's official packaging and
	// several distros ship these as /usr/bin/firefox-<channel>).
	"firefox-devedition",
	"firefox-developer-edition",
	"firefox-beta",
	"firefox-nightly",
	"firefox-esr",
	// Plain firefox last. Post-snap-removal this is a real unconfined binary on
	// most systems (often a symlink to the installed channel); on legacy Ubuntu
	// it may still be the snap wrapper.
	"firefox",
}

// isLikelySnap reports whether a resolved binary path is the snap wrapper.
// It resolves symlinks first: on snap-free systems /usr/bin/firefox is often a
// symlink to a real channel build (e.g. firefox-devedition), so we must NOT
// treat /usr/bin/firefox as snap by name — only a path that actually lives
// under /snap/ (directly or after symlink resolution) is the snap wrapper.
func isLikelySnap(path string) bool {
	if strings.Contains(path, "/snap/") {
		return true
	}
	if resolved, err := filepath.EvalSymlinks(path); err == nil {
		if strings.Contains(resolved, "/snap/") {
			return true
		}
	}
	return false
}

// standard profiles.ini locations across OSes (native, snap, flatpak on Linux).
func profileRoots() []string {
	home, _ := os.UserHomeDir()
	return profileRootsFor(runtime.GOOS, home, os.Getenv("APPDATA"))
}

// profileRootsFor returns Firefox profiles.ini roots for a given OS/home, with
// APPDATA passed explicitly so all branches are unit-testable cross-platform.
func profileRootsFor(goos, home, appData string) []string {
	switch goos {
	case "windows":
		if appData == "" {
			appData = filepath.Join(home, "AppData", "Roaming")
		}
		return []string{filepath.Join(appData, "Mozilla", "Firefox")}
	case "darwin":
		return []string{filepath.Join(home, "Library", "Application Support", "Firefox")}
	default: // linux and other unix
		return []string{
			filepath.Join(home, ".mozilla", "firefox"),
			filepath.Join(home, "snap", "firefox", "common", ".mozilla", "firefox"),
			filepath.Join(home, ".var", "app", "org.mozilla.firefox", ".mozilla", "firefox"),
		}
	}
}

// AutomationProfileDir returns a writable, browser-readable directory in which to
// create an automation/copy profile named `name`.
//
// CRITICAL for Ubuntu (snap is the default Firefox): snap Firefox's sandbox
// REFUSES profiles under /tmp ("Your Firefox profile cannot be loaded"). It can
// only read profiles under snap-accessible paths. So when the selected binary is
// snap, we place automation profiles under ~/snap/firefox/common/.mozilla/firefox
// (guaranteed readable/writable by the snap). For unconfined Firefox, /tmp is
// fine and preferred (auto-cleaned, isolated). Verified working: snap firefox +
// snap-legal profile → BiDi connect + navigate + eval all succeed.
func AutomationProfileDir(binary, name string) string {
	if isLikelySnap(binary) {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, "snap", "firefox", "common", ".mozilla", "firefox", name)
	}
	return filepath.Join(os.TempDir(), name)
}

// DetectFirefox probes for a firefox binary, its profiles, and the display.
func DetectFirefox(ctx context.Context) (*Detection, error) {
	d := &Detection{}

	candidates := firefoxBinaryCandidates()
	for _, cand := range candidates {
		// Absolute paths: check executable directly. Bare names: use PATH.
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
		return nil, fmt.Errorf("no firefox binary found (tried %v)", candidates)
	}
	if isLikelySnap(d.Binary) {
		d.IsSnap = true
		d.Warning = "selected Firefox is snap-confined; BiDi driving may fail (profile paths restricted to snap dir, and process termination can affect other Firefox instances). Prefer an unconfined Firefox."
	}

	// Version (best-effort).
	vctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if out, err := exec.CommandContext(vctx, d.Binary, "--version").Output(); err == nil {
		d.Version = strings.TrimSpace(string(out))
	}

	d.Profiles = discoverProfiles()

	d.DisplayFound = os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != "" || sessionDisplay() != ""
	if d.DisplayFound {
		d.SuggestMode = ModeHead
	} else {
		d.SuggestMode = ModeHeadless
	}
	return d, nil
}

// discoverProfiles finds Firefox profiles two ways, deduped by path:
//  1. profiles.ini entries under each known root (the normal case), and
//  2. a best-effort scan for UNREGISTERED profile directories — folders that
//     look like a Firefox profile (contain cookies.sqlite or prefs.js) but are
//     not listed in any profiles.ini. This covers manually-created profiles
//     (e.g. ~/.mozilla/firefox-beta-unsigned) and fresh channel installs whose
//     profiles.ini hasn't been written yet.
func discoverProfiles() []FirefoxProfile {
	seen := map[string]bool{}
	var out []FirefoxProfile
	add := func(p FirefoxProfile) {
		key, err := filepath.Abs(p.Path)
		if err != nil {
			key = p.Path
		}
		if seen[key] {
			return
		}
		seen[key] = true
		out = append(out, p)
	}

	// (1) profiles.ini entries.
	for _, root := range profileRoots() {
		ini := filepath.Join(root, "profiles.ini")
		f, err := os.Open(ini)
		if err != nil {
			continue
		}
		profs := parseProfilesIni(f, root)
		f.Close()
		for _, p := range profs {
			add(p)
		}
	}

	// (2) Unregistered profile-directory scan.
	for _, dir := range profileScanDirs() {
		for _, p := range scanProfileDirs(dir) {
			add(p)
		}
	}
	return out
}

// profileScanDirs returns directories to scan for unregistered profile folders.
// Includes each profiles.ini root AND its parent (custom profiles like
// ~/.mozilla/firefox-beta-unsigned live beside ~/.mozilla/firefox, not inside).
func profileScanDirs() []string {
	seen := map[string]bool{}
	var dirs []string
	push := func(d string) {
		if d == "" || seen[d] {
			return
		}
		seen[d] = true
		dirs = append(dirs, d)
	}
	for _, root := range profileRoots() {
		push(root)
		push(filepath.Dir(root)) // parent (e.g. ~/.mozilla)
	}
	return dirs
}

// looksLikeProfileDir reports whether a directory is a Firefox profile: it must
// contain a recognizable profile marker file. cookies.sqlite is the strongest
// signal (auth); prefs.js/times.json indicate a real profile even if empty.
func looksLikeProfileDir(dir string) bool {
	for _, marker := range []string{"cookies.sqlite", "prefs.js", "times.json"} {
		if fi, err := os.Stat(filepath.Join(dir, marker)); err == nil && !fi.IsDir() {
			return true
		}
	}
	return false
}

// scanProfileDirs returns profile-like immediate subdirectories of dir. It does
// NOT recurse (Firefox profiles are one level deep) and skips our own AIOPE
// automation/driveable copies so we never seed from them.
func scanProfileDirs(dir string) []FirefoxProfile {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []FirefoxProfile
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		name := e.Name()
		if isAiopeProfileDirName(name) {
			continue // never treat our own copies as a source profile
		}
		full := filepath.Join(dir, name)
		if looksLikeProfileDir(full) {
			out = append(out, FirefoxProfile{Path: full, IsLocked: profileLocked(full)})
		}
	}
	return out
}

// isAiopeProfileDirName reports whether a directory basename is one of AIOPE's
// own driveable/automation profiles (which must never be seeded FROM).
func isAiopeProfileDirName(name string) bool {
	return name == "aiope-profile" || name == "aiope-auto" ||
		strings.HasPrefix(name, "aiope-")
}

// parseProfilesIni extracts [ProfileN] entries. IsRelative=1 => Path is under root.
func parseProfilesIni(f *os.File, root string) []FirefoxProfile {
	var profs []FirefoxProfile
	var cur *FirefoxProfile
	var isRelative bool

	flush := func() {
		if cur != nil && cur.Path != "" {
			p := cur.Path
			if isRelative {
				p = filepath.Join(root, p)
			}
			cur.Path = p
			cur.IsLocked = profileLocked(p)
			profs = append(profs, *cur)
		}
		cur = nil
		isRelative = false
	}

	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(line, "[Profile") {
			flush()
			cur = &FirefoxProfile{}
			continue
		}
		if cur == nil {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch strings.TrimSpace(k) {
		case "Name":
			cur.Name = strings.TrimSpace(v)
		case "Path":
			cur.Path = strings.TrimSpace(v)
		case "IsRelative":
			isRelative = strings.TrimSpace(v) == "1"
		}
	}
	flush()
	return profs
}

// profileLocked reports whether a Gecko profile currently holds its instance lock.
func profileLocked(profilePath string) bool {
	for _, name := range []string{"parent.lock", ".parentlock", "lock"} {
		if _, err := os.Stat(filepath.Join(profilePath, name)); err == nil {
			return true
		}
	}
	return false
}

// LaunchOptions configures a Firefox launch for BiDi driving.
type LaunchOptions struct {
	Binary      string
	ProfilePath string
	Mode        LaunchMode
	DebugPort   int
	// RestoreSession sets prefs so the previous session is restored silently.
	RestoreSession bool
}

// RunningFirefox is a firefox main process bound to a given profile.
type RunningFirefox struct {
	PID int
	Exe string
}

// applyRestorePrefs writes prefs into the profile so the prior session restores
// without a recovery prompt (important for unattended/headless launches).
func applyRestorePrefs(profilePath string) error {
	// Ensure the profile dir exists (fresh/automation profiles may not yet).
	if err := os.MkdirAll(profilePath, 0o700); err != nil {
		return fmt.Errorf("create profile dir: %w", err)
	}
	// user.js is read at startup and overrides prefs.js for these keys.
	prefs := strings.Join([]string{
		`user_pref("browser.startup.page", 3);`,                  // restore previous session
		`user_pref("browser.sessionstore.resume_from_crash", true);`,
		`user_pref("toolkit.startup.max_resumed_crashes", -1);`,  // never show crash recovery UI
		`user_pref("browser.sessionstore.max_resumed_crashes", -1);`,
		`user_pref("browser.shell.checkDefaultBrowser", false);`,
		"",
	}, "\n")
	return os.WriteFile(filepath.Join(profilePath, "user.js"), []byte(prefs), 0o600)
}

// LaunchResult carries the spawned process and the discovered BiDi endpoint.
type LaunchResult struct {
	Cmd     *exec.Cmd
	BiDiURL string
}

// Launch starts Firefox with WebDriver BiDi enabled and returns the ws endpoint.
// The daemon owns the launch, so it sets the debug flags itself — no pre-config
// on the target is required.
func Launch(ctx context.Context, opts LaunchOptions) (*LaunchResult, error) {
	if opts.DebugPort == 0 {
		opts.DebugPort = 9222
	}
	if opts.RestoreSession {
		if err := applyRestorePrefs(opts.ProfilePath); err != nil {
			return nil, fmt.Errorf("write restore prefs: %w", err)
		}
	}
	// Remove any stale BiDi server file so waitForBiDi only sees the fresh one
	// from THIS launch (a stale file caused connects to a dead/wrong endpoint).
	_ = os.Remove(filepath.Join(opts.ProfilePath, "WebDriverBiDiServer.json"))

	args := []string{
		"--remote-debugging-port", fmt.Sprintf("%d", opts.DebugPort),
		"--remote-allow-system-access", // required on modern Gecko to attach to a real profile
		"--profile", opts.ProfilePath,
		"--no-remote",
	}
	if opts.Mode == ModeHeadless {
		args = append(args, "--headless")
	}

	// Use exec.Command (NOT CommandContext with the request ctx): the browser is
	// a long-lived session process managed by the daemon's ProcessTracker, and
	// must NOT be killed when the launching request's context is cancelled.
	cmd := exec.Command(opts.Binary, args...)
	// Inherit env, then for headed mode attach the active graphical session's
	// display env (DISPLAY/XAUTHORITY/XDG_RUNTIME_DIR/WAYLAND) so a system daemon
	// running outside the session can still render into it. Xwayland handled via
	// DISPLAY; MOZ_ENABLE_WAYLAND left unset so Firefox uses X/Xwayland uniformly.
	cmd.Env = os.Environ()
	if opts.Mode != ModeHeadless {
		cmd.Env = append(cmd.Env, ResolveActiveDisplay().EnvPairs()...)
	}

	// Capture stderr: Firefox prints the real BiDi ws URL there at startup, e.g.
	//   "WebDriver BiDi listening on ws://127.0.0.1:9222/session"
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, fmt.Errorf("stderr pipe: %w", err)
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("launch firefox: %w", err)
	}

	bidiURL, err := waitForBiDi(ctx, stderr, opts.ProfilePath, opts.DebugPort, 30*time.Second)
	if err != nil {
		_ = cmd.Process.Kill()
		return nil, err
	}
	return &LaunchResult{Cmd: cmd, BiDiURL: bidiURL}, nil
}

// bidiURLRe matches the ws URL Firefox logs when the BiDi agent starts.
var bidiURLRe = regexp.MustCompile(`ws://[^\s"]+`)

// normalizeBiDiURL ensures the ws URL includes a session path. Firefox may log a
// bare "ws://127.0.0.1:9222" early in startup; BiDi requires the /session path
// for the WebSocket upgrade, so append it when the matched URL has no path.
func normalizeBiDiURL(raw string, port int) string {
	// Strip scheme to inspect the authority/path.
	rest := strings.TrimPrefix(raw, "ws://")
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		// Has a path already (e.g. /session or /session/<uuid>).
		return raw
	}
	// Bare host:port — append the conventional session path.
	return raw + "/session"
}

// waitForBiDi finds the BiDi ws URL. It concurrently (a) scans Firefox stderr for
// the "listening on ws://..." line (authoritative) and (b) polls the profile's
// WebDriverBiDiServer.json as a fallback. First to yield a URL wins.
func waitForBiDi(ctx context.Context, stderr io.Reader, profilePath string, port int, wait time.Duration) (string, error) {
	found := make(chan string, 2)

	// (a) stderr scanner
	go func() {
		sc := bufio.NewScanner(stderr)
		for sc.Scan() {
			line := sc.Text()
			if strings.Contains(line, "WebDriver BiDi") && strings.Contains(line, "ws://") {
				if m := bidiURLRe.FindString(line); m != "" {
					found <- normalizeBiDiURL(m, port)
					return
				}
			}
		}
	}()

	// (b) profile JSON poller
	go func() {
		jsonPath := filepath.Join(profilePath, "WebDriverBiDiServer.json")
		for {
			if data, err := os.ReadFile(jsonPath); err == nil {
				// Firefox writes one of two shapes depending on version:
				//   {"ws_path":"/session/<uuid>"}          (older)
				//   {"ws_host":"127.0.0.1","ws_port":9222}  (Firefox 154+)
				var info struct {
					WSPath string `json:"ws_path"`
					WSHost string `json:"ws_host"`
					WSPort int    `json:"ws_port"`
				}
				if json.Unmarshal(data, &info) == nil {
					if info.WSPath != "" {
						found <- fmt.Sprintf("ws://127.0.0.1:%d%s", port, info.WSPath)
						return
					}
					if info.WSHost != "" && info.WSPort != 0 {
						found <- fmt.Sprintf("ws://%s:%d/session", info.WSHost, info.WSPort)
						return
					}
				}
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(250 * time.Millisecond):
			}
		}
	}()

	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case url := <-found:
		return url, nil
	case <-timer.C:
		return "", fmt.Errorf("timed out after %s waiting for BiDi endpoint (no 'WebDriver BiDi listening' line and no WebDriverBiDiServer.json)", wait)
	case <-ctx.Done():
		return "", ctx.Err()
	}
}
