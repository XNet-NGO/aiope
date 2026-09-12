package browser

import "testing"

func TestRegistrable(t *testing.T) {
	cases := map[string]string{
		"google.com":               "google.com",
		"accounts.google.com":      "google.com",
		"www.linkedin.com":         "linkedin.com",
		"https://github.com/x/y":   "github.com",
		"sub.a.stackexchange.com":  "stackexchange.com",
		"example.co.uk":            "example.co.uk",
		"a.b.example.co.uk":        "example.co.uk",
		"127.0.0.1:8080":           "127.0.0.1",
		"HTTPS://WWW.Google.com/":  "google.com",
	}
	for in, want := range cases {
		if got := registrable(in); got != want {
			t.Errorf("registrable(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestFirewall_BothAllowlisted(t *testing.T) {
	fw := NewFirewall() // github.com and google.com are in the seed
	d := fw.CheckSubmit("github.com", "github.com", false)
	if !d.Allowed {
		t.Fatalf("expected allow when both hosts allowlisted, got gate: %+v", d)
	}
	// subdomains reduce to the same registrable domain
	d = fw.CheckSubmit("gist.github.com", "api.github.com", false)
	if !d.Allowed {
		t.Fatalf("expected allow for subdomains of allowlisted domain, got: %+v", d)
	}
}

func TestFirewall_PageOnlyGated(t *testing.T) {
	fw := NewFirewall()
	// page on allowlisted host, but form posts to a foreign host → GATE (exfil vector)
	d := fw.CheckSubmit("github.com", "evil.example", false)
	if d.Allowed {
		t.Fatalf("expected gate when form-action host is foreign, got allow")
	}
	if d.Status != "permission_required" {
		t.Errorf("expected permission_required, got %q", d.Status)
	}
	if d.FormHost != "evil.example" {
		t.Errorf("expected FormHost reported, got %q", d.FormHost)
	}
}

func TestFirewall_ForeignPageGated(t *testing.T) {
	fw := NewFirewall()
	d := fw.CheckSubmit("evil.example", "evil.example", false)
	if d.Allowed {
		t.Fatalf("expected gate for non-allowlisted page, got allow")
	}
	if d.Status != "permission_required" {
		t.Errorf("expected permission_required, got %q", d.Status)
	}
}

func TestFirewall_ConfirmBypassesOnce(t *testing.T) {
	fw := NewFirewall()
	d := fw.CheckSubmit("evil.example", "evil.example", true)
	if !d.Allowed {
		t.Fatalf("expected confirm=true to allow once, got gate: %+v", d)
	}
	// confirm is per-call; without it, still gated
	d = fw.CheckSubmit("evil.example", "evil.example", false)
	if d.Allowed {
		t.Fatalf("expected gate after confirm expires, got allow")
	}
}

func TestFirewall_SessionAllow(t *testing.T) {
	fw := NewFirewall()
	if fw.CheckSubmit("intranet.corp", "intranet.corp", false).Allowed {
		t.Fatalf("precondition: intranet.corp should start gated")
	}
	fw.AllowSession("intranet.corp")
	if !fw.CheckSubmit("intranet.corp", "intranet.corp", false).Allowed {
		t.Fatalf("expected allow after AllowSession")
	}
	// a subdomain of the session-allowed domain is also allowed
	if !fw.CheckSubmit("app.intranet.corp", "intranet.corp", false).Allowed {
		t.Fatalf("expected subdomain allow after AllowSession")
	}
}

func TestFirewall_UserAllowHost(t *testing.T) {
	fw := NewFirewall()
	fw.AllowHost("mycompany.io")
	if !fw.CheckSubmit("mycompany.io", "mycompany.io", false).Allowed {
		t.Fatalf("expected allow after AllowHost")
	}
}

func TestFirewall_UnsafeBypass(t *testing.T) {
	fw := NewFirewall()
	if fw.CheckSubmit("evil.example", "attacker.test", false).Allowed {
		t.Fatalf("precondition: should be gated with unsafe off")
	}
	fw.SetUnsafe(true)
	if !fw.CheckSubmit("evil.example", "attacker.test", false).Allowed {
		t.Fatalf("expected allow with unsafe mode on")
	}
}

func TestFirewall_EmptyFormActionDefaultsToPage(t *testing.T) {
	fw := NewFirewall()
	// empty form-action host means same-origin submit → judged by page host only
	if !fw.CheckSubmit("github.com", "", false).Allowed {
		t.Fatalf("expected allow: empty form host defaults to allowlisted page host")
	}
	if fw.CheckSubmit("evil.example", "", false).Allowed {
		t.Fatalf("expected gate: empty form host defaults to non-allowlisted page host")
	}
}

func TestFirewall_SeedLoaded(t *testing.T) {
	fw := NewFirewall()
	// spot-check a few seed entries are present
	for _, h := range []string{"google.com", "github.com", "reddit.com", "linkedin.com"} {
		if !fw.allowed(h) {
			t.Errorf("expected seed domain %q to be allowlisted", h)
		}
	}
}
