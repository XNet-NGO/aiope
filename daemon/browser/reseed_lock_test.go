package browser

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestCloseBrowsersClearsStaleLocks verifies that, with NO live process holding
// the profile, CloseBrowsersOnProfile clears stale on-disk lock markers so a
// subsequent full copy is not blocked by a leftover lock (the crashed/stray
// case where the process is gone but its lock file remains).
func TestCloseBrowsersClearsStaleLocks(t *testing.T) {
	dir := t.TempDir()
	// Simulate a crashed browser's leftovers.
	for _, f := range []string{"parent.lock", "SingletonLock", "cookies.sqlite", "cookies.sqlite-wal"} {
		if err := os.WriteFile(filepath.Join(dir, f), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := CloseBrowsersOnProfile(context.Background(), dir, 2*time.Second); err != nil {
		t.Fatalf("CloseBrowsersOnProfile: %v", err)
	}
	for _, l := range []string{"parent.lock", "SingletonLock"} {
		if _, err := os.Lstat(filepath.Join(dir, l)); err == nil {
			t.Errorf("stale lock %q not cleared", l)
		}
	}
	// Non-lock data must be preserved.
	if _, err := os.Stat(filepath.Join(dir, "cookies.sqlite")); err != nil {
		t.Errorf("cookies.sqlite should be preserved: %v", err)
	}
}

// TestCloseBrowsersNoProcNoError: empty/no-process profile returns nil quickly.
func TestCloseBrowsersNoProcNoError(t *testing.T) {
	if err := CloseBrowsersOnProfile(context.Background(), t.TempDir(), time.Second); err != nil {
		t.Fatalf("expected nil for empty profile, got %v", err)
	}
}
