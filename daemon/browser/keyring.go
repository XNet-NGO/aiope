package browser

import (
	"os"
	"os/exec"
	"runtime"
	"strings"
)

// Keyring/cookie-encryption handling is OS-specific:
//
//   - Linux: Chrome encrypts cookies with a key from the OS keyring (Secret
//     Service) — GNOME libsecret (gnome-keyring) or KDE KWallet. The key is NOT
//     in Local State; it lives in the keyring. To decrypt seeded cookies the
//     AIOPE Chrome must use the SAME backend the source used, AND be able to
//     reach the session bus. We DETECT the desktop's backend rather than
//     hardcoding it, and fall back to "basic" only when no keyring exists.
//   - macOS: Chrome uses the macOS Keychain (per-user); a copied profile run as
//     the same user decrypts fine. No flag needed.
//   - Windows: Chrome uses DPAPI (per-user); same-user copy decrypts fine. No
//     flag needed.
//
// Firefox does NOT use the OS keyring for cookies (its cookies.sqlite is not
// encrypted at rest); saved logins use key4.db which we copy. So Firefox needs
// no keyring handling — only the file copies.

// chromePasswordStoreArgs returns the Chrome "--password-store" argument(s)
// appropriate for the current OS/desktop, or nil when Chrome's default is
// correct (macOS/Windows) or no keyring is available (Linux headless server).
//
// Detection order on Linux:
//  1. If neither libsecret nor kwallet is reachable -> "basic" (deterministic;
//     matches a source that also had no keyring).
//  2. KDE/Plasma session with kwalletd running -> "kwallet5"/"kwallet".
//  3. Otherwise, if a Secret Service (gnome-keyring/keepassxc/etc.) is present
//     -> "gnome-libsecret" (the generic libsecret backend, not GNOME-specific).
func chromePasswordStoreArgs() []string {
	if runtime.GOOS != "linux" {
		return nil // macOS Keychain / Windows DPAPI: Chrome default is correct
	}
	switch detectLinuxKeyring() {
	case keyringKWallet:
		return []string{"--password-store=kwallet5"}
	case keyringLibsecret:
		return []string{"--password-store=gnome-libsecret"}
	default:
		// No reachable keyring: use the deterministic hardcoded key. This lets
		// cookies carry when the SOURCE also had no keyring (headless servers).
		return []string{"--password-store=basic"}
	}
}

type linuxKeyring int

const (
	keyringNone linuxKeyring = iota
	keyringLibsecret
	keyringKWallet
)

func detectLinuxKeyring() linuxKeyring {
	// No session bus at all -> no Secret Service reachable -> basic.
	if !sessionBusReachable() {
		return keyringNone
	}
	desktop := strings.ToLower(os.Getenv("XDG_CURRENT_DESKTOP") + " " + os.Getenv("DESKTOP_SESSION"))
	kdeSession := strings.Contains(desktop, "kde") || strings.Contains(desktop, "plasma")

	if kdeSession && processRunning("kwalletd5", "kwalletd6", "kwalletd") {
		return keyringKWallet
	}
	// gnome-keyring or any Secret Service provider.
	if processRunning("gnome-keyring-d", "gnome-keyring-daemon", "keepassxc") {
		return keyringLibsecret
	}
	// KWallet even outside a detected KDE session.
	if processRunning("kwalletd5", "kwalletd6", "kwalletd") {
		return keyringKWallet
	}
	// Session bus exists but no known keyring daemon detected.
	return keyringNone
}

// sessionBusReachable reports whether a D-Bus session bus is available (needed
// for any Secret Service keyring).
func sessionBusReachable() bool {
	if os.Getenv("DBUS_SESSION_BUS_ADDRESS") != "" {
		return true
	}
	rd := os.Getenv("XDG_RUNTIME_DIR")
	if rd == "" {
		rd = "/run/user/" + itoa(os.Getuid())
	}
	return fileExists(rd + "/bus")
}

// processRunning reports whether any process whose name matches one of the
// given (comm-truncated) prefixes is running. Uses pgrep when available, else a
// /proc scan — no external assumptions, works across distros.
func processRunning(names ...string) bool {
	if _, err := exec.LookPath("pgrep"); err == nil {
		for _, n := range names {
			if err := exec.Command("pgrep", "-x", n).Run(); err == nil {
				return true
			}
			// pgrep -x needs exact comm (max 15 chars); also try substring.
			if err := exec.Command("pgrep", n).Run(); err == nil {
				return true
			}
		}
		return false
	}
	// Fallback: scan /proc/*/comm.
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return false
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		comm, err := os.ReadFile("/proc/" + e.Name() + "/comm")
		if err != nil {
			continue
		}
		c := strings.TrimSpace(string(comm))
		for _, n := range names {
			if c == n || strings.HasPrefix(c, n) {
				return true
			}
		}
	}
	return false
}
