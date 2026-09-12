package browser

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	_ "modernc.org/sqlite"
)

// This file gives Firefox the same persistent-profile model as Chrome:
//   - the user's real profile is the read-only GOLDEN MASTER (never driven),
//   - a persistent AIOPE Firefox profile is the driveable working copy,
//   - seed (full) / refresh (auth-only) / reseed (full reset) from the master.
//
// This avoids both (a) driving an empty profile and (b) killing the user's live
// Firefox to steal its locked profile. Auth + history carry; the master is safe.

// AiopeFirefoxProfileDir is the persistent, driveable AIOPE Firefox profile.
// It is NOT the user's default profile (which may be locked by a running
// Firefox and, on snap, is confinement-restricted). For snap Firefox it must
// live on a snap-legal path.
func AiopeFirefoxProfileDir(binary string) string {
	if isLikelySnap(binary) {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, "snap", "firefox", "common", ".mozilla", "firefox", "aiope-profile")
	}
	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "windows":
		appData := os.Getenv("APPDATA")
		if appData == "" {
			appData = filepath.Join(home, "AppData", "Roaming")
		}
		return filepath.Join(appData, "Mozilla", "Firefox", "aiope-profile")
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Firefox", "aiope-profile")
	default:
		return filepath.Join(home, ".mozilla", "firefox", "aiope-profile")
	}
}

// SelectMasterFirefoxProfile chooses the best golden-master profile to seed
// from: among discovered profiles (excluding AIOPE's own driveable profiles),
// it prefers the one with the most RECENT cookie activity that also actually
// contains cookies — i.e. the profile the user actually logs in with. This
// avoids seeding from an empty throwaway profile when a logged-in one exists.
// Falls back to the newest-by-mtime, then the first, if none have cookies.
func SelectMasterFirefoxProfile(profiles []FirefoxProfile, aiopeDir string) string {
	type cand struct {
		path    string
		mtime   time.Time
		hasData bool
		rows    int
	}
	var cands []cand
	for _, p := range profiles {
		base := filepath.Base(p.Path)
		if p.Path == aiopeDir || base == "aiope-profile" || base == "aiope-auto" {
			continue // never seed from our own driveable profiles
		}
		ck := filepath.Join(p.Path, "cookies.sqlite")
		fi, err := os.Stat(ck)
		if err != nil {
			cands = append(cands, cand{path: p.Path})
			continue
		}
		// hasData must reflect ACTUAL cookies, not file size — an empty Firefox
		// cookies.sqlite is still ~512KB (page-preallocated). Count rows.
		n := firefoxCookieRowCount(ck)
		cands = append(cands, cand{path: p.Path, mtime: fi.ModTime(), hasData: n > 0, rows: n})
	}
	if len(cands) == 0 {
		return ""
	}
	best := ""
	var bestT time.Time
	bestRows := -1
	for _, withData := range []bool{true, false} {
		for _, c := range cands {
			if c.hasData != withData {
				continue
			}
			// Prefer most cookies; tie-break by newest mtime.
			if best == "" || c.rows > bestRows || (c.rows == bestRows && c.mtime.After(bestT)) {
				best, bestT, bestRows = c.path, c.mtime, c.rows
			}
		}
		if best != "" {
			return best
		}
	}
	return cands[0].path
}

// firefoxCookieRowCount returns the number of cookies in a Firefox
// cookies.sqlite, opened read-only (tolerant of a live writer). Returns 0 on any
// error so an unreadable/empty DB is treated as "no data".
func firefoxCookieRowCount(path string) int {
	db, err := sql.Open("sqlite", "file:"+path+"?mode=ro&_pragma=busy_timeout(2000)")
	if err != nil {
		return 0
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	var n int
	if err := db.QueryRow("SELECT COUNT(*) FROM moz_cookies").Scan(&n); err != nil {
		return 0
	}
	return n
}

// realFirefoxProfile picks the user's default (golden master) profile path from
// discovered profiles, preferring the one that is NOT the AIOPE profile.
func realFirefoxProfile(profiles []FirefoxProfile) string {
	for _, p := range profiles {
		if filepath.Base(p.Path) != "aiope-profile" {
			return p.Path
		}
	}
	if len(profiles) > 0 {
		return profiles[0].Path
	}
	return ""
}

// EnsureAiopeFirefoxProfile prepares the persistent AIOPE Firefox profile and
// returns its path. Behavior mirrors EnsureAiopeChromeProfile:
//   - not exist        -> SEED (full copy from the real profile)
//   - refresh          -> auth-only overlay from the real profile (keeps divergence)
//   - reseed           -> full wipe + copy (reset/recovery)
//   - else             -> reuse as-is; auto auth-refresh if the master's cookies are newer
//
// masterProfile is the user's real profile (golden master); it is only READ.
// The caller must ensure no Firefox is running on the AIOPE profile first.
func EnsureAiopeFirefoxProfile(ctx context.Context, binary, masterProfile string, refresh, reseed bool) (string, error) {
	dst := AiopeFirefoxProfileDir(binary)
	if masterProfile == "" {
		// No master to seed from — just use an empty dedicated profile.
		if err := os.MkdirAll(dst, 0o700); err != nil {
			return "", err
		}
		return dst, nil
	}

	_, dstErr := os.Stat(dst)
	exists := dstErr == nil

	switch {
	case reseed || !exists:
		if _, err := os.Stat(masterProfile); err != nil {
			return "", fmt.Errorf("master firefox profile not found at %s: %w", masterProfile, err)
		}
		// A full copy must read a consistent, UNLOCKED source. Close every
		// browser process (any engine, including stray/lingering/crashed ones)
		// holding the master profile so its SQLite DBs are unlocked and the WAL
		// is checkpointed by a clean shutdown before we copy.
		if err := CloseBrowsersOnProfile(ctx, masterProfile, 15*time.Second); err != nil {
			return "", fmt.Errorf("close browsers on master firefox profile before copy: %w", err)
		}
		if err := os.RemoveAll(dst); err != nil {
			return "", fmt.Errorf("wipe aiope firefox profile: %w", err)
		}
		if err := copyFirefoxFull(masterProfile, dst); err != nil {
			return "", fmt.Errorf("seed/reseed aiope firefox profile: %w", err)
		}
	case refresh || firefoxCookiesNewer(masterProfile, dst):
		if err := copyFirefoxAuthOnly(masterProfile, dst); err != nil {
			return "", fmt.Errorf("refresh aiope firefox profile: %w", err)
		}
	default:
		// reuse as-is
	}
	return dst, nil
}

func firefoxCookiesNewer(master, dst string) bool {
	return firefoxCookieMtime(master).After(firefoxCookieMtime(dst))
}

func firefoxCookieMtime(profile string) time.Time {
	if fi, err := os.Stat(filepath.Join(profile, "cookies.sqlite")); err == nil {
		return fi.ModTime()
	}
	return time.Time{}
}

// copyFirefoxAuthOnly overlays only the auth/session/history stores onto dst,
// preserving dst's accumulated divergence (prefs, extensions, etc.). SQLite
// stores are copied consistently (VACUUM INTO) so refresh is safe even while
// Firefox holds them; plain stores (logins.json, sessionstore) are byte-copied.
func copyFirefoxAuthOnly(master, dst string) error {
	if _, err := os.Stat(master); err != nil {
		return fmt.Errorf("master firefox profile not found at %s: %w", master, err)
	}
	if err := os.MkdirAll(dst, 0o700); err != nil {
		return err
	}
	return copyAuthStores(master, dst, firefoxAuthStores)
}

// copyFirefoxFull copies the whole profile tree (seed/reseed), skipping the lock.
func copyFirefoxFull(master, dst string) error {
	walkErr := filepath.Walk(master, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		rel, rerr := filepath.Rel(master, path)
		if rerr != nil {
			return nil
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		base := filepath.Base(path)
		if base == "parent.lock" || base == ".parentlock" || base == "lock" {
			return nil
		}
		// Skip version-pinning files: a profile last used by a NEWER Firefox
		// (e.g. seeding a beta profile into an older stable) triggers downgrade
		// protection ("profile was last used with a newer version") and Firefox
		// refuses to start. Dropping compatibility.ini lets the target rebuild
		// it for its own version — makes seeding version/channel-agnostic.
		if base == "compatibility.ini" {
			return nil
		}
		_ = copyFileIfExists(path, target)
		return nil
	})
	if walkErr != nil {
		return walkErr
	}
	// Guarantee the auth SQLite stores are transactionally consistent snapshots,
	// uniform with the refresh path and with Chrome.
	return copyAuthStores(master, dst, firefoxAuthStores)
}
