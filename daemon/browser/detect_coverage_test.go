package browser

import (
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"testing"

	_ "modernc.org/sqlite"
)

// ── Binary candidate coverage ────────────────────────────────────────────────

// TestFirefoxCandidatesCoverAllChannels asserts every Firefox channel (stable,
// ESR, Developer Edition, Beta, Nightly) is represented in the Linux candidate
// list, both as a /usr/lib(64) or /opt install path AND as a PATH launcher name.
func TestFirefoxCandidatesCoverAllChannels(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux candidate list")
	}
	joined := strings.Join(firefoxCandidates, "\n")

	// Channel launcher names that must be probed on PATH.
	requiredNames := []string{
		"firefox",                   // stable / symlink to installed channel
		"firefox-esr",               // ESR
		"firefox-devedition",        // Developer Edition (Mozilla apt, Arch)
		"firefox-developer-edition", // Developer Edition (alt naming)
		"firefox-beta",              // Beta
		"firefox-nightly",           // Nightly
	}
	for _, n := range requiredNames {
		if !containsExact(firefoxCandidates, n) {
			t.Errorf("candidate PATH name %q missing from firefoxCandidates", n)
		}
	}

	// Channel install directories that must be probed absolutely.
	requiredPaths := []string{
		"/usr/lib/firefox/firefox",
		"/usr/lib64/firefox/firefox",
		"/usr/lib/firefox-devedition/firefox",
		"/usr/lib/firefox-beta/firefox",
		"/usr/lib/firefox-nightly/firefox",
		"/opt/firefox/firefox",
		"/opt/firefox-devedition/firefox",
		"/opt/firefox-beta/firefox",
		"/opt/firefox-nightly/firefox",
	}
	for _, p := range requiredPaths {
		if !containsExact(firefoxCandidates, p) {
			t.Errorf("candidate install path %q missing:\n%s", p, joined)
		}
	}
}

// TestFirefoxCandidatesOrdering asserts absolute channel paths are probed before
// the bare "firefox" fallback (so a specific channel install wins over a
// possibly-snap PATH firefox), and "firefox" is last.
func TestFirefoxCandidatesOrdering(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux candidate list")
	}
	last := firefoxCandidates[len(firefoxCandidates)-1]
	if last != "firefox" {
		t.Errorf("expected bare \"firefox\" last, got %q", last)
	}
	devIdx := indexOf(firefoxCandidates, "/usr/lib/firefox-devedition/firefox")
	fxIdx := indexOf(firefoxCandidates, "firefox")
	if devIdx == -1 || devIdx > fxIdx {
		t.Errorf("dev-edition path (idx %d) should come before bare firefox (idx %d)", devIdx, fxIdx)
	}
}

func TestFirefoxBinaryCandidatesPerOS(t *testing.T) {
	// Windows: includes versioned Program Files paths + firefox.exe.
	win := firefoxBinaryCandidatesFor("windows", `C:\PF`, `C:\PF86`)
	if !anyContains(win, `C:\PF`) || !anyHasSuffix(win, "firefox.exe") {
		t.Errorf("windows candidates wrong: %v", win)
	}
	// Windows with empty env falls back to default Program Files.
	winDef := firefoxBinaryCandidatesFor("windows", "", "")
	if !anyContains(winDef, `C:\Program Files`) {
		t.Errorf("windows default PF fallback missing: %v", winDef)
	}
	// darwin: includes stable + Developer Edition + Nightly .app bundles.
	mac := firefoxBinaryCandidatesFor("darwin", "", "")
	for _, want := range []string{
		"/Applications/Firefox.app/Contents/MacOS/firefox",
		"/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox",
		"/Applications/Firefox Nightly.app/Contents/MacOS/firefox",
	} {
		if !containsExact(mac, want) {
			t.Errorf("darwin candidates missing %q: %v", want, mac)
		}
	}
	// linux: the full channel candidate list.
	lin := firefoxBinaryCandidatesFor("linux", "", "")
	if !containsExact(lin, "firefox-devedition") {
		t.Errorf("linux candidates missing firefox-devedition")
	}
}

func TestProfileRootsPerOS(t *testing.T) {
	win := profileRootsFor("windows", `C:\Users\u`, `C:\Users\u\AppData\Roaming`)
	if !anyContains(win, filepath.Join("Mozilla", "Firefox")) {
		t.Errorf("windows roots wrong: %v", win)
	}
	if got := profileRootsFor("windows", `C:\Users\u`, ""); !anyContains(got, "AppData") {
		t.Errorf("windows empty-APPDATA fallback missing: %v", got)
	}
	mac := profileRootsFor("darwin", "/Users/u", "")
	if !anyContains(mac, "Library/Application Support/Firefox") {
		t.Errorf("darwin roots wrong: %v", mac)
	}
	lin := profileRootsFor("linux", "/home/u", "")
	for _, want := range []string{"/home/u/.mozilla/firefox", "/home/u/snap/firefox/common/.mozilla/firefox", "/home/u/.var/app/org.mozilla.firefox/.mozilla/firefox"} {
		if !containsExact(lin, want) {
			t.Errorf("linux roots missing %q: %v", want, lin)
		}
	}
}

// TestDiscoverProfilesIntegration exercises the real discoverProfiles against a
// temp HOME containing BOTH a profiles.ini-registered profile and an
// unregistered custom profile dir beside it (the firefox-beta-unsigned case).
func TestDiscoverProfilesIntegration(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("uses linux profile roots")
	}
	home := t.TempDir()
	t.Setenv("HOME", home)
	ffRoot := filepath.Join(home, ".mozilla", "firefox")
	mustMkdirAll(t, ffRoot)

	// Registered profile via profiles.ini (relative path under the root).
	reg := filepath.Join(ffRoot, "abcd.default-release")
	mustMkdirAll(t, reg)
	mustWrite(t, filepath.Join(reg, "cookies.sqlite"))
	iniBody := "[Profile0]\nName=default\nIsRelative=1\nPath=abcd.default-release\n"
	if err := os.WriteFile(filepath.Join(ffRoot, "profiles.ini"), []byte(iniBody), 0o600); err != nil {
		t.Fatal(err)
	}

	// Unregistered custom profile BESIDE the root: ~/.mozilla/firefox-beta-unsigned
	custom := filepath.Join(home, ".mozilla", "firefox-beta-unsigned")
	mustMkdirAll(t, custom)
	mustWrite(t, filepath.Join(custom, "cookies.sqlite"))

	// Our own copy — must be excluded even though it looks like a profile.
	ours := filepath.Join(ffRoot, "aiope-profile")
	mustMkdirAll(t, ours)
	mustWrite(t, filepath.Join(ours, "cookies.sqlite"))

	got := discoverProfiles()
	paths := map[string]bool{}
	for _, p := range got {
		abs, _ := filepath.Abs(p.Path)
		paths[abs] = true
	}
	regAbs, _ := filepath.Abs(reg)
	customAbs, _ := filepath.Abs(custom)
	oursAbs, _ := filepath.Abs(ours)
	if !paths[regAbs] {
		t.Errorf("registered profile %q not discovered; got %v", reg, keys(paths))
	}
	if !paths[customAbs] {
		t.Errorf("unregistered custom profile %q not discovered; got %v", custom, keys(paths))
	}
	if paths[oursAbs] {
		t.Errorf("aiope-profile %q must NOT be discovered as a source profile", ours)
	}
}

func keys(m map[string]bool) []string {
	var k []string
	for x := range m {
		k = append(k, x)
	}
	sort.Strings(k)
	return k
}

// ── Chrome candidate + snap coverage ─────────────────────────────────────────

func TestChromeCandidatesCoverChannels(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux chrome candidates")
	}
	requiredNames := []string{
		"google-chrome", "google-chrome-stable", "google-chrome-beta",
		"google-chrome-dev", "google-chrome-unstable", "chromium", "chromium-browser",
	}
	for _, n := range requiredNames {
		if !containsExact(chromeCandidates, n) {
			t.Errorf("chrome candidate name %q missing", n)
		}
	}
	requiredPaths := []string{
		"/opt/google/chrome/chrome",
		"/opt/google/chrome-beta/chrome",
		"/opt/google/chrome-unstable/chrome",
		"/usr/lib/chromium/chromium",
		"/usr/lib/chromium-browser/chromium-browser",
	}
	for _, p := range requiredPaths {
		if !containsExact(chromeCandidates, p) {
			t.Errorf("chrome candidate path %q missing", p)
		}
	}
}

func TestIsLikelySnapChromePaths(t *testing.T) {
	snap := []string{
		"/snap/chromium/current/usr/lib/chromium/chromium",
		"/snap/bin/chromium",
	}
	unconfined := []string{
		"/usr/bin/chromium",
		"/usr/bin/chromium-browser",
		"/usr/lib/chromium/chromium",
		"/opt/google/chrome/chrome",
		"/opt/google/chrome-beta/chrome",
	}
	for _, p := range snap {
		if !isLikelySnapChrome(p) {
			t.Errorf("isLikelySnapChrome(%q) = false, want true", p)
		}
	}
	for _, p := range unconfined {
		if isLikelySnapChrome(p) {
			t.Errorf("isLikelySnapChrome(%q) = true, want false", p)
		}
	}
}

// ── Snap detection coverage ──────────────────────────────────────────────────

func TestIsLikelySnapPaths(t *testing.T) {
	snap := []string{
		"/snap/firefox/current/usr/lib/firefox/firefox",
		"/snap/bin/firefox",
		"/var/lib/snapd/snap/firefox/x1/usr/lib/firefox/firefox",
	}
	unconfined := []string{
		"/usr/lib/firefox/firefox",
		"/usr/lib64/firefox/firefox",
		"/usr/lib/firefox-devedition/firefox",
		"/usr/lib/firefox-beta/firefox",
		"/usr/lib/firefox-nightly/firefox",
		"/opt/firefox/firefox",
		"/opt/firefox-devedition/firefox",
		"/home/u/Downloads/firefox-156/firefox/firefox",
	}
	for _, p := range snap {
		if !isLikelySnap(p) {
			t.Errorf("isLikelySnap(%q) = false, want true", p)
		}
	}
	for _, p := range unconfined {
		if isLikelySnap(p) {
			t.Errorf("isLikelySnap(%q) = true, want false", p)
		}
	}
}

// TestIsLikelySnapSymlinkResolution: a symlink whose target is under /snap is
// snap; a symlink to a real channel build is NOT (the /usr/bin/firefox case).
func TestIsLikelySnapSymlinkResolution(t *testing.T) {
	dir := t.TempDir()
	// Fake a non-snap target and a "snap" target, symlink to each.
	realTarget := filepath.Join(dir, "usr", "lib", "firefox-devedition", "firefox")
	snapDir := filepath.Join(dir, "snap", "firefox", "x", "usr", "lib", "firefox")
	mustMkdirAll(t, filepath.Dir(realTarget))
	mustMkdirAll(t, snapDir)
	mustWrite(t, realTarget)
	snapTarget := filepath.Join(snapDir, "firefox")
	mustWrite(t, snapTarget)

	realLink := filepath.Join(dir, "firefox-real")
	snapLink := filepath.Join(dir, "firefox-snap")
	if err := os.Symlink(realTarget, realLink); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(snapTarget, snapLink); err != nil {
		t.Fatal(err)
	}
	if isLikelySnap(realLink) {
		t.Errorf("symlink to real channel build wrongly flagged snap")
	}
	if !isLikelySnap(snapLink) {
		t.Errorf("symlink resolving under /snap not flagged snap")
	}
}

// ── Profile discovery coverage ───────────────────────────────────────────────

func TestLooksLikeProfileDir(t *testing.T) {
	// Each marker independently qualifies a dir as a profile.
	for _, marker := range []string{"cookies.sqlite", "prefs.js", "times.json"} {
		d := t.TempDir()
		mustWrite(t, filepath.Join(d, marker))
		if !looksLikeProfileDir(d) {
			t.Errorf("dir with %q not recognized as profile", marker)
		}
	}
	// Empty / non-profile dir.
	empty := t.TempDir()
	if looksLikeProfileDir(empty) {
		t.Error("empty dir wrongly recognized as profile")
	}
	// A marker that is a directory (not a file) must not qualify.
	weird := t.TempDir()
	mustMkdirAll(t, filepath.Join(weird, "cookies.sqlite"))
	if looksLikeProfileDir(weird) {
		t.Error("dir named cookies.sqlite wrongly qualifies")
	}
}

func TestIsAiopeProfileDirName(t *testing.T) {
	aiope := []string{"aiope-profile", "aiope-auto", "aiope-chrome-auto", "aiope-anything"}
	real := []string{"x83jbw1w.default", "dev-edition-default", "firefox-beta-unsigned", "default-release"}
	for _, n := range aiope {
		if !isAiopeProfileDirName(n) {
			t.Errorf("%q should be recognized as an AIOPE profile dir", n)
		}
	}
	for _, n := range real {
		if isAiopeProfileDirName(n) {
			t.Errorf("%q wrongly recognized as an AIOPE profile dir", n)
		}
	}
}

func TestScanProfileDirs(t *testing.T) {
	root := t.TempDir()
	// A real profile with cookies.
	real := filepath.Join(root, "x83jbw1w.default")
	mustMkdirAll(t, real)
	mustWrite(t, filepath.Join(real, "cookies.sqlite"))
	// A prefs-only profile (no cookies yet).
	fresh := filepath.Join(root, "dev-edition-default")
	mustMkdirAll(t, fresh)
	mustWrite(t, filepath.Join(fresh, "prefs.js"))
	// Our own copy — must be skipped.
	ours := filepath.Join(root, "aiope-profile")
	mustMkdirAll(t, ours)
	mustWrite(t, filepath.Join(ours, "cookies.sqlite"))
	// A non-profile dir — must be ignored.
	junk := filepath.Join(root, "Crash Reports")
	mustMkdirAll(t, junk)
	mustWrite(t, filepath.Join(junk, "readme.txt"))
	// A file (not a dir) — ignored.
	mustWrite(t, filepath.Join(root, "profiles.ini"))

	got := scanProfileDirs(root)
	var names []string
	for _, p := range got {
		names = append(names, filepath.Base(p.Path))
	}
	sort.Strings(names)
	want := []string{"dev-edition-default", "x83jbw1w.default"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Errorf("scanProfileDirs = %v, want %v", names, want)
	}
}

// TestScanProfileDirsMissing: nonexistent dir yields nothing, no panic.
func TestScanProfileDirsMissing(t *testing.T) {
	if got := scanProfileDirs(filepath.Join(t.TempDir(), "nope")); len(got) != 0 {
		t.Errorf("expected empty for missing dir, got %v", got)
	}
}

// TestSelectMasterFirefoxProfilePrefersMostCookies verifies master selection
// picks the profile with the most cookie rows (real logged-in one) over an
// empty page-allocated cookies DB, and skips AIOPE's own dir.
func TestSelectMasterFirefoxProfilePrefersMostCookies(t *testing.T) {
	base := t.TempDir()
	aiopeDir := filepath.Join(base, "aiope-profile")

	// empty profile (cookies.sqlite exists but 0 rows)
	empty := filepath.Join(base, "empty.default")
	mustMkdirAll(t, empty)
	makeCookieDB(t, filepath.Join(empty, "cookies.sqlite"), 0)

	// logged-in profile (many cookies)
	loggedIn := filepath.Join(base, "loggedin.default")
	mustMkdirAll(t, loggedIn)
	makeCookieDB(t, filepath.Join(loggedIn, "cookies.sqlite"), 50)

	// our own copy — must never be chosen
	mustMkdirAll(t, aiopeDir)
	makeCookieDB(t, filepath.Join(aiopeDir, "cookies.sqlite"), 999)

	profiles := []FirefoxProfile{
		{Path: empty}, {Path: loggedIn}, {Path: aiopeDir},
	}
	got := SelectMasterFirefoxProfile(profiles, aiopeDir)
	if got != loggedIn {
		t.Errorf("SelectMasterFirefoxProfile = %q, want %q (most cookies, non-aiope)", got, loggedIn)
	}
}

// ── helpers ──────────────────────────────────────────────────────────────────

func containsExact(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}
func indexOf(s []string, v string) int {
	for i, x := range s {
		if x == v {
			return i
		}
	}
	return -1
}
func anyHasSuffix(s []string, suf string) bool {
	for _, x := range s {
		if strings.HasSuffix(x, suf) {
			return true
		}
	}
	return false
}
func anyContains(s []string, sub string) bool {
	for _, x := range s {
		if strings.Contains(x, sub) {
			return true
		}
	}
	return false
}
func mustMkdirAll(t *testing.T, d string) {
	t.Helper()
	if err := os.MkdirAll(d, 0o755); err != nil {
		t.Fatal(err)
	}
}
func mustWrite(t *testing.T, p string) {
	t.Helper()
	if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
}

// makeCookieDB creates a Firefox-style cookies.sqlite with a moz_cookies table
// containing n rows, so firefoxCookieRowCount (used by SelectMasterFirefoxProfile)
// reads a real count.
func makeCookieDB(t *testing.T, path string, n int) {
	t.Helper()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`CREATE TABLE moz_cookies(id INTEGER PRIMARY KEY, host TEXT)`); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < n; i++ {
		if _, err := db.Exec(`INSERT INTO moz_cookies(host) VALUES(?)`, "h.example"); err != nil {
			t.Fatal(err)
		}
	}
}
