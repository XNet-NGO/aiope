//go:build !linux && !windows

package browser

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// Portable process-management fallback for platforms without /proc (macOS, BSD).
// macOS Chrome uses the Keychain and Firefox uses key4.db, so profile copies
// decrypt under the same user; the main need here is closing browsers before a
// seed/reseed and freeing the debug port. We use POSIX tools (pkill/lsof) which
// are present on macOS and BSD. Attribution is coarser than Linux /proc, so
// CloseBrowsersOnProfile closes browser processes by name.

var otherBrowserProcNames = []string{"firefox", "Google Chrome", "chrome", "chromium", "Chromium"}

func signalPID(pid int, sig string) error {
	return exec.Command("kill", "-"+sig, itoa(pid)).Run()
}

// FreeDebugPort kills any process listening on the given TCP port via lsof.
func FreeDebugPort(port int) {
	if _, err := exec.LookPath("lsof"); err == nil {
		out, _ := exec.Command("lsof", "-t", "-i", "tcp:"+itoa(port), "-sTCP:LISTEN").Output()
		for _, f := range fields(string(out)) {
			if pid, ok := atoi(f); ok {
				_ = signalPID(pid, "KILL")
			}
		}
	}
	time.Sleep(500 * time.Millisecond)
}

// FindRunningFirefox reports Firefox processes (best-effort, by name) referencing
// the profile. Attribution is coarse on non-Linux; profilePath is accepted for
// signature parity.
func FindRunningFirefox(ctx context.Context, profilePath string) ([]RunningFirefox, error) {
	out, _ := exec.CommandContext(ctx, "pgrep", "-x", "firefox").Output()
	var procs []RunningFirefox
	for _, f := range fields(string(out)) {
		if pid, ok := atoi(f); ok {
			procs = append(procs, RunningFirefox{PID: pid, Exe: "firefox"})
		}
	}
	return procs, nil
}

// TerminateAndWait stops the given Firefox processes and waits for them to exit.
func TerminateAndWait(ctx context.Context, procs []RunningFirefox, profilePath string, wait time.Duration) error {
	for _, p := range procs {
		_ = signalPID(p.PID, "TERM")
	}
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		still, _ := FindRunningFirefox(ctx, profilePath)
		if len(still) == 0 {
			return nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	for _, p := range procs {
		_ = signalPID(p.PID, "KILL")
	}
	return nil
}

// CloseBrowsersOnProfile closes browser processes (by name) before a seed/reseed
// full copy, then clears stale lock markers so the copy is consistent.
func CloseBrowsersOnProfile(ctx context.Context, profilePath string, wait time.Duration) error {
	for _, name := range otherBrowserProcNames {
		_ = exec.CommandContext(ctx, "pkill", "-TERM", "-f", name).Run()
	}
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		running := false
		for _, name := range otherBrowserProcNames {
			if exec.CommandContext(ctx, "pgrep", "-f", name).Run() == nil {
				running = true
				break
			}
		}
		if !running {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	for _, name := range otherBrowserProcNames {
		_ = exec.CommandContext(ctx, "pkill", "-KILL", "-f", name).Run()
	}
	for _, l := range []string{"parent.lock", ".parentlock", "lock", "SingletonLock", "SingletonCookie", "SingletonSocket"} {
		_ = os.Remove(filepath.Join(profilePath, l))
	}
	return nil
}

// small local helpers to avoid importing strconv/strings just for these.
func fields(s string) []string {
	var out []string
	cur := ""
	for _, r := range s {
		if r == ' ' || r == '\n' || r == '\t' || r == '\r' {
			if cur != "" {
				out = append(out, cur)
				cur = ""
			}
			continue
		}
		cur += string(r)
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

func atoi(s string) (int, bool) {
	n := 0
	if s == "" {
		return 0, false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return 0, false
		}
		n = n*10 + int(r-'0')
	}
	return n, true
}
