package browser

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAutomationProfileDir(t *testing.T) {
	home, _ := os.UserHomeDir()

	tests := []struct {
		name    string
		binary  string
		profile string
		wantHas string
	}{
		{
			name:    "snap wrapper uses snap-legal path",
			binary:  "/usr/bin/firefox",
			profile: "auto1",
			wantHas: filepath.Join(home, "snap", "firefox", "common", ".mozilla", "firefox", "auto1"),
		},
		{
			name:    "explicit snap path uses snap-legal path",
			binary:  "/snap/firefox/current/usr/lib/firefox/firefox",
			profile: "auto2",
			wantHas: filepath.Join(home, "snap", "firefox", "common", ".mozilla", "firefox", "auto2"),
		},
		{
			name:    "unconfined tarball uses tmp",
			binary:  "/home/user/Downloads/firefox/firefox",
			profile: "auto3",
			wantHas: filepath.Join(os.TempDir(), "auto3"),
		},
		{
			name:    "unconfined /usr/lib uses tmp",
			binary:  "/usr/lib/firefox/firefox",
			profile: "auto4",
			wantHas: filepath.Join(os.TempDir(), "auto4"),
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := AutomationProfileDir(tc.binary, tc.profile)
			if got != tc.wantHas {
				t.Errorf("AutomationProfileDir(%q, %q) = %q, want %q", tc.binary, tc.profile, got, tc.wantHas)
			}
			// snap paths must never be under /tmp (the bug we're preventing)
			if isLikelySnap(tc.binary) && strings.HasPrefix(got, os.TempDir()) {
				t.Errorf("snap binary got a /tmp profile path %q — snap refuses these", got)
			}
		})
	}
}

func TestIsLikelySnap(t *testing.T) {
	snap := []string{"/usr/bin/firefox", "/snap/firefox/current/usr/lib/firefox/firefox", "/snap/bin/firefox"}
	unconfined := []string{"/usr/lib/firefox/firefox", "/opt/firefox/firefox", "/home/u/Downloads/firefox-156/firefox/firefox"}
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
