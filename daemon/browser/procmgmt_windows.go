//go:build windows

package browser

import (
	"context"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Windows implementations of browser process discovery/termination. Windows has
// no /proc and no POSIX signals; we use tasklist to find firefox PIDs and
// taskkill to stop them. Profile-path attribution is coarser than on Linux
// (tasklist doesn't expose per-process open dirs without extra tooling), so on
// Windows we match all firefox.exe processes and stop them when a session start
// needs the profile free. This is acceptable because Windows desktop typically
// runs a single Firefox instance per user.

// FindRunningFirefox returns running firefox.exe main processes. profilePath is
// accepted for signature parity but not used for attribution on Windows.
func FindRunningFirefox(ctx context.Context, profilePath string) ([]RunningFirefox, error) {
	pids, err := firefoxPIDs(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]RunningFirefox, 0, len(pids))
	for _, pid := range pids {
		out = append(out, RunningFirefox{PID: pid, Exe: "firefox.exe"})
	}
	return out, nil
}

// firefoxPIDs lists firefox.exe PIDs via tasklist CSV output.
func firefoxPIDs(ctx context.Context) ([]int, error) {
	out, err := exec.CommandContext(ctx, "tasklist", "/FI", "IMAGENAME eq firefox.exe", "/FO", "CSV", "/NH").Output()
	if err != nil {
		return nil, nil // tasklist returns non-zero / "no tasks" — treat as none
	}
	var pids []int
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "INFO:") {
			continue
		}
		// CSV: "firefox.exe","1234","Console","1","123,456 K"
		fields := strings.Split(line, ",")
		if len(fields) < 2 {
			continue
		}
		pidStr := strings.Trim(fields[1], "\" ")
		if pid, perr := strconv.Atoi(pidStr); perr == nil {
			pids = append(pids, pid)
		}
	}
	return pids, nil
}

func signalPID(pid int, _ string) error {
	// Windows has no signals; taskkill /F terminates. /T also kills children.
	return exec.Command("taskkill", "/F", "/T", "/PID", fmt.Sprintf("%d", pid)).Run()
}

// FreeDebugPort kills any process listening on the given TCP port (Windows).
// Uses netstat to find the owning PID, then taskkill.
func FreeDebugPort(port int) {
	needle := fmt.Sprintf(":%d", port)
	out, err := exec.Command("netstat", "-ano", "-p", "tcp").Output()
	if err != nil {
		return
	}
	seen := map[string]bool{}
	for _, line := range strings.Split(string(out), "\n") {
		if !strings.Contains(line, needle) || !strings.Contains(line, "LISTENING") {
			continue
		}
		f := strings.Fields(line)
		if len(f) == 0 {
			continue
		}
		pid := f[len(f)-1]
		if seen[pid] {
			continue
		}
		seen[pid] = true
		_ = exec.Command("taskkill", "/F", "/PID", pid).Run()
	}
	time.Sleep(500 * time.Millisecond)
}

// TerminateAndWait stops running firefox.exe processes and waits for them to exit.
func TerminateAndWait(ctx context.Context, procs []RunningFirefox, profilePath string, wait time.Duration) error {
	if len(procs) == 0 {
		return nil
	}
	for _, p := range procs {
		_ = signalPID(p.PID, "")
	}
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		pids, _ := firefoxPIDs(ctx)
		if len(pids) == 0 {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(300 * time.Millisecond):
		}
	}
	// Best-effort: report success even if some remain (Windows single-instance
	// usually clears; a lingering process will surface as a launch error).
	return nil
}
