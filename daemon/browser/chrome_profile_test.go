package browser

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return string(b)
}

func TestCopyChromeFull_SeedAndReseed(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	// golden master content
	writeFile(t, filepath.Join(src, "Local State"), "key-material")
	writeFile(t, filepath.Join(src, "Default", "Cookies"), "master-cookies")
	writeFile(t, filepath.Join(src, "Default", "History"), "master-history")
	writeFile(t, filepath.Join(src, "SingletonLock"), "should-not-copy")

	if err := copyChromeFull(src, dst); err != nil {
		t.Fatal(err)
	}
	if got := readFile(t, filepath.Join(dst, "Default", "Cookies")); got != "master-cookies" {
		t.Errorf("cookies not seeded: %q", got)
	}
	if got := readFile(t, filepath.Join(dst, "Default", "History")); got != "master-history" {
		t.Errorf("history not seeded: %q", got)
	}
	if got := readFile(t, filepath.Join(dst, "Local State")); got != "key-material" {
		t.Errorf("Local State not seeded: %q", got)
	}
	// lock/singleton files must be skipped
	if _, err := os.Stat(filepath.Join(dst, "SingletonLock")); err == nil {
		t.Errorf("SingletonLock should not have been copied")
	}
}

func TestCopyChromeAuthOnly_PreservesDivergence(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	// AIOPE profile has diverged: its own history + old cookies
	writeFile(t, filepath.Join(dst, "Default", "History"), "aiope-accumulated-history")
	writeFile(t, filepath.Join(dst, "Default", "Cookies"), "old-cookies")
	writeFile(t, filepath.Join(dst, "Local State"), "old-key")
	// golden master has NEW auth
	writeFile(t, filepath.Join(src, "Default", "Cookies"), "fresh-cookies")
	writeFile(t, filepath.Join(src, "Local State"), "fresh-key")
	writeFile(t, filepath.Join(src, "Default", "History"), "master-history-should-not-overwrite")

	if err := copyChromeAuthOnly(src, dst); err != nil {
		t.Fatal(err)
	}
	// auth overlaid
	if got := readFile(t, filepath.Join(dst, "Default", "Cookies")); got != "fresh-cookies" {
		t.Errorf("auth-only refresh did not update cookies: %q", got)
	}
	if got := readFile(t, filepath.Join(dst, "Local State")); got != "fresh-key" {
		t.Errorf("auth-only refresh did not update Local State: %q", got)
	}
	// divergence PRESERVED: AIOPE history untouched (not overwritten by master)
	if got := readFile(t, filepath.Join(dst, "Default", "History")); got != "aiope-accumulated-history" {
		t.Errorf("auth-only refresh clobbered AIOPE history (should preserve divergence): %q", got)
	}
}

func TestDefaultCookiesNewer(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	writeFile(t, filepath.Join(dst, "Default", "Cookies"), "old")
	time.Sleep(20 * time.Millisecond)
	writeFile(t, filepath.Join(src, "Default", "Cookies"), "new")

	if !defaultCookiesNewer(src, dst) {
		t.Errorf("expected source cookies (newer) to trigger staleness refresh")
	}
	// after refresh (touch dst newer), no longer stale
	time.Sleep(20 * time.Millisecond)
	writeFile(t, filepath.Join(dst, "Default", "Cookies"), "refreshed")
	if defaultCookiesNewer(src, dst) {
		t.Errorf("expected no staleness after dst updated")
	}
}
