//go:build !windows

package browser

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
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

// isBrowserExe reports whether an exe path looks like Firefox or a
// Chrome/Chromium-family browser (so seed/reseed can close either engine).
func isBrowserExe(exe string) bool {
	e := strings.ToLower(exe)
	return strings.Contains(e, "firefox") ||
		strings.Contains(e, "chrome") ||
		strings.Contains(e, "chromium")
}

// allBrowserPidsReferencingProfile finds EVERY process (any browser engine,
// including stray/lingering/crashed instances and their helper children) that
// holds files under profilePath. It matches by open files / maps / cwd via
// /proc, not argv, so orphaned processes whose argv no longer reflects the
// profile are still caught.
func allBrowserPidsReferencingProfile(profilePath string) []int {
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
		if !isBrowserExe(exe) {
			continue
		}
		if procReferencesProfile(pid, profilePath) || procHasOpenFileUnder(pid, profilePath) {
			pids = append(pids, pid)
		}
	}
	return pids
}

// procHasOpenFileUnder checks /proc/<pid>/fd for any descriptor pointing at a
// file under profilePath — this catches a process holding a locked SQLite DB
// even if it's a stray helper whose maps/cwd don't mention the profile.
func procHasOpenFileUnder(pid int, profilePath string) bool {
	fdDir := fmt.Sprintf("/proc/%d/fd", pid)
	entries, err := os.ReadDir(fdDir)
	if err != nil {
		return false
	}
	for _, e := range entries {
		target, err := os.Readlink(filepath.Join(fdDir, e.Name()))
		if err != nil {
			continue
		}
		if strings.HasPrefix(target, profilePath) {
			return true
		}
	}
	return false
}

// CloseBrowsersOnProfile stops ALL browser processes (any engine, including
// stray/lingering/crashed instances) that hold profilePath, and waits until the
// profile's file locks are actually released. Graceful SIGTERM first, then
// SIGKILL for stragglers. Used before a seed/reseed FULL COPY so the source
// profile's SQLite DBs (cookies/places/key4/logins) are unlocked and its WAL is
// checkpointed by a clean shutdown, yielding a consistent copy.
//
// NOTE: for the user's real profile this closes the user's running browser —
// that is required for a consistent full copy and is intentional on reseed.
func CloseBrowsersOnProfile(ctx context.Context, profilePath string, wait time.Duration) error {
	pids := allBrowserPidsReferencingProfile(profilePath)
	if len(pids) == 0 {
		// No live holder — but a crashed/stray instance may have left lock files
		// behind. Remove them immediately so the copy isn't blocked.
		removeLockFiles(profilePath)
		return nil
	}
	for _, pid := range pids {
		_ = signalPID(pid, "TERM")
	}
	if waitBrowserProfileFree(ctx, profilePath, wait) {
		return waitLockFilesGone(ctx, profilePath, 3*time.Second)
	}
	// Stragglers: force-kill everything still holding the profile.
	for _, pid := range allBrowserPidsReferencingProfile(profilePath) {
		_ = signalPID(pid, "KILL")
	}
	if !waitBrowserProfileFree(ctx, profilePath, 8*time.Second) {
		return fmt.Errorf("profile still held after SIGKILL: %s", profilePath)
	}
	return waitLockFilesGone(ctx, profilePath, 3*time.Second)
}

func waitBrowserProfileFree(ctx context.Context, profilePath string, wait time.Duration) bool {
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		if len(allBrowserPidsReferencingProfile(profilePath)) == 0 {
			return true
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(200 * time.Millisecond):
		}
	}
	return len(allBrowserPidsReferencingProfile(profilePath)) == 0
}

// browserLockFiles are on-disk lock markers left by browsers (Firefox:
// parent.lock/.parentlock/lock; Chrome: Singleton*). A clean shutdown removes
// them and checkpoints -wal into the main DB.
var browserLockFiles = []string{"parent.lock", ".parentlock", "lock", "SingletonLock", "SingletonCookie", "SingletonSocket"}

func removeLockFiles(profilePath string) {
	for _, l := range browserLockFiles {
		_ = os.Remove(filepath.Join(profilePath, l))
	}
}

// waitLockFilesGone waits for on-disk lock markers to disappear after the
// processes exit. A clean shutdown removes them; if they linger past the
// deadline (stale), remove them so the copy proceeds.
func waitLockFilesGone(ctx context.Context, profilePath string, wait time.Duration) error {
	deadline := time.Now().Add(wait)
	for {
		held := false
		for _, l := range browserLockFiles {
			if _, err := os.Lstat(filepath.Join(profilePath, l)); err == nil {
				held = true
				break
			}
		}
		if !held {
			return nil
		}
		if time.Now().After(deadline) {
			removeLockFiles(profilePath) // best-effort: clear stale markers
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(150 * time.Millisecond):
		}
	}
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
