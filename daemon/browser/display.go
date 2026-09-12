package browser

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// DisplayEnv is the set of environment variables a browser needs to render into
// an active graphical session (X11/Xwayland). Empty Display means no usable
// session was found → the caller should fall back to headless.
type DisplayEnv struct {
	Display    string // e.g. ":0"
	XAuthority string // magic-cookie file
	RuntimeDir string // XDG_RUNTIME_DIR (for Wayland socket access)
	Wayland    string // WAYLAND_DISPLAY, if applicable
	DBus       string // DBUS_SESSION_BUS_ADDRESS (for OS keyring / Secret Service)
}

// Usable reports whether a headed launch is possible with this env.
func (d DisplayEnv) Usable() bool { return d.Display != "" || d.Wayland != "" }

// EnvPairs returns "KEY=VALUE" entries to append to a browser's environment.
func (d DisplayEnv) EnvPairs() []string {
	var e []string
	if d.Display != "" {
		e = append(e, "DISPLAY="+d.Display)
	}
	if d.XAuthority != "" {
		e = append(e, "XAUTHORITY="+d.XAuthority)
	}
	if d.RuntimeDir != "" {
		e = append(e, "XDG_RUNTIME_DIR="+d.RuntimeDir)
	}
	if d.Wayland != "" {
		e = append(e, "WAYLAND_DISPLAY="+d.Wayland)
	}
	if d.DBus != "" {
		e = append(e, "DBUS_SESSION_BUS_ADDRESS="+d.DBus)
	}
	return e
}

// SessionKeyringEnv returns env entries a browser needs to reach the OS keyring
// (Secret Service) so it can decrypt keyring-encrypted (Chrome "v11") cookies —
// REQUIRED for auth to carry over, and needed even in HEADLESS mode (it is not
// tied to a display). Resolves DBUS_SESSION_BUS_ADDRESS + XDG_RUNTIME_DIR from
// the daemon's own env or from the standard per-user runtime path.
func SessionKeyringEnv() []string {
	if runtime.GOOS != "linux" {
		return nil
	}
	rd := os.Getenv("XDG_RUNTIME_DIR")
	if rd == "" {
		rd = filepath.Join("/run/user", itoa(os.Getuid()))
	}
	dbus := os.Getenv("DBUS_SESSION_BUS_ADDRESS")
	if dbus == "" {
		// The session bus socket is conventionally at $XDG_RUNTIME_DIR/bus.
		if busPath := filepath.Join(rd, "bus"); fileExists(busPath) {
			dbus = "unix:path=" + busPath
		}
	}
	var env []string
	if rd != "" {
		env = append(env, "XDG_RUNTIME_DIR="+rd)
	}
	if dbus != "" {
		env = append(env, "DBUS_SESSION_BUS_ADDRESS="+dbus)
	}
	return env
}

// ResolveActiveDisplay discovers the CURRENT active graphical session's display
// environment so a system daemon (running outside the session) can launch a
// headed browser into it. Headed intentionally lives-and-dies with that session
// (logout/suspend ends it) — that is inherent to rendering to a real display.
//
// Order of preference:
//  1. the daemon's own env (if it was started in-session),
//  2. loginctl: the active graphical session's env,
//  3. best-effort probe of /tmp/.X11-unix + a discovered XAUTHORITY.
//
// Returns an empty (unusable) DisplayEnv when no active session is found, so the
// caller falls back to headless.
func ResolveActiveDisplay() DisplayEnv {
	if runtime.GOOS != "linux" {
		return DisplayEnv{}
	}
	var d DisplayEnv

	// (1) Daemon's own env (in-session launch).
	d.Display = os.Getenv("DISPLAY")
	d.Wayland = os.Getenv("WAYLAND_DISPLAY")
	d.XAuthority = os.Getenv("XAUTHORITY")
	d.RuntimeDir = os.Getenv("XDG_RUNTIME_DIR")
	d.DBus = os.Getenv("DBUS_SESSION_BUS_ADDRESS")
	if d.Usable() && d.XAuthority != "" {
		return d
	}

	// (2) loginctl: find an active graphical session and read its env.
	if le := displayFromLoginctl(); le.Usable() {
		// keep any XAuthority/runtime we already had if loginctl didn't supply
		if le.XAuthority == "" {
			le.XAuthority = d.XAuthority
		}
		if le.RuntimeDir == "" {
			le.RuntimeDir = d.RuntimeDir
		}
		return le
	}

	// (3) Best-effort probe: an X socket + a discoverable XAUTHORITY means a
	// same-user session is very likely usable via Xwayland/X11.
	rd := d.RuntimeDir
	if rd == "" {
		rd = filepath.Join("/run/user", itoa(os.Getuid()))
	}
	if socketDisplay := probeX11Socket(); socketDisplay != "" {
		xauth := d.XAuthority
		if xauth == "" {
			xauth = probeXAuthority(rd)
		}
		if xauth != "" { // only claim usable when we also have auth
			return DisplayEnv{Display: socketDisplay, XAuthority: xauth, RuntimeDir: rd}
		}
	}
	return DisplayEnv{}
}

// displayFromLoginctl queries systemd-logind for the active graphical session.
func displayFromLoginctl() DisplayEnv {
	if _, err := exec.LookPath("loginctl"); err != nil {
		return DisplayEnv{}
	}
	uid := itoa(os.Getuid())
	// Find sessions for this user; pick an active graphical (seat0) one.
	out, err := exec.Command("loginctl", "list-sessions", "--no-legend").Output()
	if err != nil {
		return DisplayEnv{}
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) < 3 {
			continue
		}
		sid := f[0]
		// Show session properties.
		p, err := exec.Command("loginctl", "show-session", sid,
			"-p", "Type", "-p", "Active", "-p", "Display", "-p", "State", "-p", "User").Output()
		if err != nil {
			continue
		}
		props := parseKV(string(p))
		if props["User"] != uid {
			continue
		}
		if props["Active"] != "yes" && props["State"] != "active" {
			continue
		}
		t := props["Type"] // "wayland" | "x11" | "tty"
		if t != "wayland" && t != "x11" {
			continue
		}
		de := DisplayEnv{RuntimeDir: filepath.Join("/run/user", uid)}
		if disp := props["Display"]; disp != "" {
			de.Display = disp
		} else {
			de.Display = probeX11Socket() // Wayland session: use Xwayland
		}
		de.XAuthority = probeXAuthority(de.RuntimeDir)
		if t == "wayland" {
			if w := probeWaylandSocket(de.RuntimeDir); w != "" {
				de.Wayland = w
			}
		}
		if de.Usable() {
			return de
		}
	}
	return DisplayEnv{}
}

func parseKV(s string) map[string]string {
	m := map[string]string{}
	for _, line := range strings.Split(s, "\n") {
		if k, v, ok := strings.Cut(strings.TrimSpace(line), "="); ok {
			m[k] = v
		}
	}
	return m
}

func probeX11Socket() string {
	entries, err := os.ReadDir("/tmp/.X11-unix")
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "X") {
			return ":" + strings.TrimPrefix(e.Name(), "X")
		}
	}
	return ""
}

func probeXAuthority(runtimeDir string) string {
	if x := os.Getenv("XAUTHORITY"); x != "" {
		if _, err := os.Stat(x); err == nil {
			return x
		}
	}
	// mutter/GNOME Wayland writes .mutter-Xwaylandauth.* in the runtime dir.
	if entries, err := os.ReadDir(runtimeDir); err == nil {
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), ".mutter-Xwaylandauth") {
				return filepath.Join(runtimeDir, e.Name())
			}
		}
	}
	home, _ := os.UserHomeDir()
	if p := filepath.Join(home, ".Xauthority"); fileExists(p) {
		return p
	}
	return ""
}

func probeWaylandSocket(runtimeDir string) string {
	entries, err := os.ReadDir(runtimeDir)
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "wayland-") && !strings.HasSuffix(e.Name(), ".lock") {
			return e.Name()
		}
	}
	return ""
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	neg := i < 0
	if neg {
		i = -i
	}
	var b [20]byte
	p := len(b)
	for i > 0 {
		p--
		b[p] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		p--
		b[p] = '-'
	}
	return string(b[p:])
}

// sessionDisplay kept for detection callers: true only if a headed session is
// resolvable right now.
func sessionDisplay() string {
	return ResolveActiveDisplay().Display
}
