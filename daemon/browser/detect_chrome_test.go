package browser

import (
	"context"
	"strings"
	"testing"
)

// The primary Chrome profile must never be driven directly (Chrome >=136 blocks
// remote-debugging on it; driving it is an account-takeover vector). Verify our
// explicit guard refuses it with a clear error before we even launch.
func TestLaunchChrome_RefusesDefaultProfile(t *testing.T) {
	ctx := context.Background()
	def := DefaultChromeUserDataDir()

	// pointing at the default dir must be refused
	_, err := LaunchChrome(ctx, ChromeLaunchOptions{
		Binary:      "/usr/bin/google-chrome",
		UserDataDir: def,
		DebugPort:   9222,
	})
	if err == nil {
		t.Fatalf("expected LaunchChrome to REFUSE the default profile dir %q, got nil error", def)
	}
	if !strings.Contains(err.Error(), "refusing to drive the default Chrome profile") {
		t.Errorf("expected explicit default-profile refusal, got: %v", err)
	}

	// empty user-data-dir must also be refused (would default → blocked)
	_, err = LaunchChrome(ctx, ChromeLaunchOptions{
		Binary:      "/usr/bin/google-chrome",
		UserDataDir: "",
		DebugPort:   9222,
	})
	if err == nil || !strings.Contains(err.Error(), "refusing to drive the default Chrome profile") {
		t.Errorf("expected empty user-data-dir to be refused, got: %v", err)
	}
}

func TestIsDefaultChromeDir(t *testing.T) {
	def := DefaultChromeUserDataDir()
	if !isDefaultChromeDir(def) {
		t.Errorf("isDefaultChromeDir(%q) = false, want true", def)
	}
	if isDefaultChromeDir("/tmp/aiope-chrome-auto") {
		t.Errorf("isDefaultChromeDir(/tmp/...) = true, want false")
	}
	// trailing-slash / clean equivalence
	if !isDefaultChromeDir(def + "/") {
		t.Errorf("isDefaultChromeDir(%q+/) should still match", def)
	}
}
