//go:build !windows

package browser

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
)

// This file holds the Unix/Linux implementations of browser process discovery
// and termination (via /proc + POSIX signals). The Windows equivalents live in
// procmgmt_windows.go. The exported functions (FindRunningFirefox,
// TerminateAndWait) have identical signatures on both platforms.

// FindRunningFirefox returns firefox PIDs that actually hold the given profile
// (i.e. have the profile dir open), by scanning /proc rather than trusting argv.
func FindRunningFirefox(ctx context.Context, profilePath string) ([]RunningFirefox, error) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, fmt.Errorf("read /proc: %w", err)
	}
	var found []RunningFirefox
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		var pid int
		if _, err := fmt.Sscanf(e.Name(), "%d", &pid); err != nil {
			continue
		}
		exe, _ := os.Readlink(fmt.Sprintf("/proc/%d/exe", pid))
		if !strings.Contains(strings.ToLower(exe), "firefox") {
			continue
		}
		if isFirefoxChild(pid) {
			continue
		}
		if procReferencesProfile(pid, profilePath) {
			found = append(found, RunningFirefox{PID: pid, Exe: exe})
		}
	}
	return found, nil
}

func isFirefoxChild(pid int) bool {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil {
		return true
	}
	argv := strings.ReplaceAll(string(data), "\x00", " ")
	return strings.Contains(argv, "-contentproc") ||
		strings.Contains(argv, "crashhelper") ||
		strings.Contains(argv, "forkserver")
}

func procReferencesProfile(pid int, profilePath string) bool {
	if cwd, err := os.Readlink(fmt.Sprintf("/proc/%d/cwd", pid)); err == nil {
		if strings.HasPrefix(cwd, profilePath) {
			return true
		}
	}
	if data, err := os.ReadFile(fmt.Sprintf("/proc/%d/maps", pid)); err == nil {
		if strings.Contains(string(data), profilePath) {
			return true
		}
	}
	return false
}

func allPidsReferencingProfile(profilePath string) []int {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	var pids []int
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		var pid int
		if _, err := fmt.Sscanf(e.Name(), "%d", &pid); err != nil {
			continue
		}
		exe, _ := os.Readlink(fmt.Sprintf("/proc/%d/exe", pid))
		if !strings.Contains(strings.ToLower(exe), "firefox") {
			continue
		}
		if procReferencesProfile(pid, profilePath) {
			pids = append(pids, pid)
		}
	}
	return pids
}

func signalPID(pid int, sig string) error {
	return exec.Command("kill", "-"+sig, fmt.Sprintf("%d", pid)).Run()
}

// FreeDebugPort kills any process currently listening on the given TCP port.
// Used before launching a browser so a stale/crashed browser holding the debug
// port (e.g. a leftover Chrome on 9222) can't cause a wrong-protocol handshake.
// Best-effort: uses fuser if present, else lsof.
func FreeDebugPort(port int) {
	p := fmt.Sprintf("%d", port)
	if _, err := exec.LookPath("fuser"); err == nil {
		_ = exec.Command("fuser", "-k", p+"/tcp").Run()
	} else if _, err := exec.LookPath("lsof"); err == nil {
		out, _ := exec.Command("lsof", "-t", "-i", "tcp:"+p, "-sTCP:LISTEN").Output()
		for _, line := range strings.Fields(string(out)) {
			var pid int
			if _, e := fmt.Sscanf(line, "%d", &pid); e == nil {
				_ = signalPID(pid, "KILL")
			}
		}
	}
	time.Sleep(500 * time.Millisecond)
}

// TerminateAndWait gracefully stops Firefox on a profile, then waits for release.
func TerminateAndWait(ctx context.Context, procs []RunningFirefox, profilePath string, wait time.Duration) error {
	if len(procs) == 0 {
		return nil
	}
	for _, p := range procs {
		_ = signalPID(p.PID, "TERM")
	}
	if waitProfileFree(ctx, profilePath, wait) {
		return nil
	}
	for _, pid := range allPidsReferencingProfile(profilePath) {
		_ = signalPID(pid, "KILL")
	}
	if waitProfileFree(ctx, profilePath, 8*time.Second) {
		return nil
	}
	return fmt.Errorf("profile still held after SIGKILL: %s", profilePath)
}

func waitProfileFree(ctx context.Context, profilePath string, wait time.Duration) bool {
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		if len(allPidsReferencingProfile(profilePath)) == 0 {
			return true
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(200 * time.Millisecond):
		}
	}
	return len(allPidsReferencingProfile(profilePath)) == 0
}
