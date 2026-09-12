package browser

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"
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

// firefoxAuthFiles are the auth/session/history files copied on an auth-only
// refresh. WAL sidecars come along for the sqlite DBs.
//   cookies.sqlite  — cookies (session/auth)
//   places.sqlite   — history + bookmarks
//   key4.db         — master key store for logins
//   logins.json     — saved logins (encrypted with key4.db)
//   cert9.db        — cert overrides
//   sessionstore.jsonlz4 — open tabs/session
var firefoxAuthFiles = []string{
	"cookies.sqlite",
	"places.sqlite",
	"key4.db",
	"logins.json",
	"cert9.db",
	"sessionstore.jsonlz4",
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

// copyFirefoxAuthOnly overlays only the auth/session/history files onto dst,
// preserving dst's accumulated divergence (prefs, extensions, etc.).
func copyFirefoxAuthOnly(master, dst string) error {
	if _, err := os.Stat(master); err != nil {
		return fmt.Errorf("master firefox profile not found at %s: %w", master, err)
	}
	if err := os.MkdirAll(dst, 0o700); err != nil {
		return err
	}
	for _, f := range firefoxAuthFiles {
		for _, suffix := range []string{"", "-wal", "-shm"} {
			_ = copyFileIfExists(filepath.Join(master, f+suffix), filepath.Join(dst, f+suffix))
		}
	}
	return nil
}

// copyFirefoxFull copies the whole profile tree (seed/reseed), skipping the lock.
func copyFirefoxFull(master, dst string) error {
	return filepath.Walk(master, func(path string, info os.FileInfo, err error) error {
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
		_ = copyFileIfExists(path, target)
		return nil
	})
}
