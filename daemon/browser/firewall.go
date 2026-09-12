package browser

import (
	_ "embed"
	"bufio"
	"strings"
	"sync"

	"golang.org/x/net/publicsuffix"
)

//go:embed tranco.txt
var trancoData string

// Firewall gates fill/submit actions against untrusted page content. It is the
// structural defense against prompt-injection: a malicious page cannot silently
// drive the agent to fill+submit data to an attacker-controlled host, because a
// submit is only allowed when BOTH the page host AND the form-action host are on
// the allowlist. Otherwise the action returns a permission_required decision to
// the AGENT (not a human), which may re-issue with an explicit allow.
//
// The seed allowlist is the Tranco top-N (embedded at build time). Per-session
// and user-added entries layer on top.
type Firewall struct {
	mu        sync.RWMutex
	base      map[string]struct{} // Tranco + user-added, registrable domains
	session   map[string]struct{} // per-session allowlist (allow:"session")
	unsafe    bool                // global bypass (off by default)
}

// Decision is the firewall's verdict for a gated action.
type Decision struct {
	Allowed bool   `json:"allowed"`
	Reason  string `json:"reason,omitempty"`
	// When not allowed, these help the agent decide whether to re-issue.
	PageHost   string `json:"page_host,omitempty"`
	FormHost   string `json:"form_host,omitempty"`
	Status     string `json:"status,omitempty"` // "permission_required" when gated
}

// NewFirewall builds a firewall seeded from the embedded Tranco allowlist.
func NewFirewall() *Firewall {
	fw := &Firewall{
		base:    make(map[string]struct{}),
		session: make(map[string]struct{}),
	}
	sc := bufio.NewScanner(strings.NewReader(trancoData))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fw.base[registrable(line)] = struct{}{}
	}
	return fw
}

// SetUnsafe toggles the global bypass. Off by default; enabling weakens the
// data-protection guarantee and should be explicit.
func (fw *Firewall) SetUnsafe(v bool) {
	fw.mu.Lock()
	fw.unsafe = v
	fw.mu.Unlock()
}

// AllowHost adds a registrable domain to the persistent user allowlist.
func (fw *Firewall) AllowHost(host string) {
	fw.mu.Lock()
	fw.base[registrable(host)] = struct{}{}
	fw.mu.Unlock()
}

// AllowSession adds a registrable domain to the per-session allowlist.
func (fw *Firewall) AllowSession(host string) {
	fw.mu.Lock()
	fw.session[registrable(host)] = struct{}{}
	fw.mu.Unlock()
}

// allowed reports whether a registrable domain is on any allowlist.
func (fw *Firewall) allowed(host string) bool {
	d := registrable(host)
	if d == "" {
		return false
	}
	_, ok := fw.base[d]
	if ok {
		return true
	}
	_, ok = fw.session[d]
	return ok
}

// CheckSubmit evaluates a fill/submit. pageHost is the current page's host;
// formActionHost is the host the form posts to (may equal pageHost, or be the
// same when there is no distinct action host). A confirm flag from the agent
// (allow:"once") bypasses the gate for this single call.
func (fw *Firewall) CheckSubmit(pageHost, formActionHost string, confirm bool) Decision {
	fw.mu.RLock()
	unsafe := fw.unsafe
	fw.mu.RUnlock()

	if unsafe || confirm {
		return Decision{Allowed: true}
	}
	if formActionHost == "" {
		formActionHost = pageHost
	}
	pageOK := fw.allowed(pageHost)
	formOK := fw.allowed(formActionHost)
	if pageOK && formOK {
		return Decision{Allowed: true}
	}
	return Decision{
		Allowed:  false,
		Status:   "permission_required",
		Reason:   "fill/submit to non-allowlisted host; re-issue with allow:once|session or add the host",
		PageHost: pageHost,
		FormHost: formActionHost,
	}
}

// registrable reduces a host (or URL host) to its eTLD+1 registrable domain
// (e.g. accounts.google.com -> google.com). Falls back to the lowercased host
// when the public-suffix lookup fails (e.g. IPs, localhost).
func registrable(host string) string {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "" {
		return ""
	}
	// Strip any scheme/path if a full URL slipped through.
	if i := strings.Index(h, "://"); i >= 0 {
		h = h[i+3:]
	}
	if i := strings.IndexByte(h, '/'); i >= 0 {
		h = h[:i]
	}
	// Strip port.
	if i := strings.LastIndexByte(h, ':'); i >= 0 {
		// avoid stripping inside IPv6 without brackets; simple heuristic ok here
		if !strings.Contains(h[i:], "]") {
			h = h[:i]
		}
	}
	etld1, err := publicsuffix.EffectiveTLDPlusOne(h)
	if err != nil {
		return h
	}
	return etld1
}
