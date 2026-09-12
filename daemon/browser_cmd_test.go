package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/xnet-ngo/aiope-remote/browser"
)

func TestParseBrowserCmd(t *testing.T) {
	cases := []struct {
		in       string
		wantVerb string
		wantPay  string
	}{
		{"__aiope_browser__detect", "detect", ""},
		{"__aiope_browser__navigate {\"url\":\"https://x.com\"}", "navigate", `{"url":"https://x.com"}`},
		{"__aiope_browser__start ", "start", ""},
		{"__aiope_browser__fill {\"selector\":\"#a\",\"value\":\"b\"}", "fill", `{"selector":"#a","value":"b"}`},
	}
	for _, tc := range cases {
		v, p := parseBrowserCmd(tc.in)
		if v != tc.wantVerb || p != tc.wantPay {
			t.Errorf("parseBrowserCmd(%q) = (%q,%q), want (%q,%q)", tc.in, v, p, tc.wantVerb, tc.wantPay)
		}
	}
}

func TestDispatch_NoSessionErrors(t *testing.T) {
	m := &browserManager{}
	ctx := context.Background()
	// verbs requiring a session should error cleanly when none exists
	for _, verb := range []string{"navigate", "content", "click", "fill", "eval", "screenshot", "status", "back", "scroll"} {
		resp := m.dispatch(ctx, verb, browserRequest{}, NewProcessTracker())
		if resp.Status != "error" {
			t.Errorf("verb %q with no session: expected error, got %q", verb, resp.Status)
		}
		if !strings.Contains(resp.Error, "no active browser session") {
			t.Errorf("verb %q: expected 'no active browser session' error, got %q", verb, resp.Error)
		}
	}
}

func TestDispatch_UnknownVerb(t *testing.T) {
	m := &browserManager{session: &browserSession{fw: browser.NewFirewall()}}
	resp := m.dispatch(context.Background(), "frobnicate", browserRequest{}, NewProcessTracker())
	if resp.Status != "error" || !strings.Contains(resp.Error, "unknown browser verb") {
		t.Errorf("expected unknown verb error, got %+v", resp)
	}
}

func TestDispatch_StopNoSession(t *testing.T) {
	m := &browserManager{}
	resp := m.dispatch(context.Background(), "stop", browserRequest{}, NewProcessTracker())
	if resp.Status != "ok" {
		t.Errorf("stop with no session should be ok, got %+v", resp)
	}
}

// fakeBrowser is a stub Browser for exercising dispatch without a real browser.
type fakeBrowser struct {
	url        string
	clicks     int
	fills      int
	lastFilled string
}

func (f *fakeBrowser) Engine() browser.Engine { return browser.EngineFirefox }
func (f *fakeBrowser) Contexts(ctx context.Context) ([]browser.PageContext, error) {
	return []browser.PageContext{{ID: "c1", URL: f.url}}, nil
}
func (f *fakeBrowser) Target(ctx context.Context) (string, error) { return "c1", nil }
func (f *fakeBrowser) Navigate(ctx context.Context, url string) error {
	f.url = url
	return nil
}
func (f *fakeBrowser) Eval(ctx context.Context, e string) ([]byte, error) { return []byte(`"ok"`), nil }
func (f *fakeBrowser) Content(ctx context.Context, o, l int) (string, error) { return "content", nil }
func (f *fakeBrowser) Elements(ctx context.Context) (string, error)          { return "elements", nil }
func (f *fakeBrowser) Click(ctx context.Context, sel string) (string, error) {
	f.clicks++
	return "clicked", nil
}
func (f *fakeBrowser) Fill(ctx context.Context, sel, val string) (string, error) {
	f.fills++
	f.lastFilled = val
	return "filled", nil
}
func (f *fakeBrowser) Screenshot(ctx context.Context) ([]byte, error) { return []byte{0xFF, 0xD8}, nil }
func (f *fakeBrowser) Back(ctx context.Context) (bool, error)         { return true, nil }
func (f *fakeBrowser) Scroll(ctx context.Context, d browser.ScrollDir, px int) (string, error) {
	return "scrolled", nil
}
func (f *fakeBrowser) Status(ctx context.Context) (browser.Status, error) {
	return browser.Status{Engine: browser.EngineFirefox, URL: f.url, Title: "t"}, nil
}
func (f *fakeBrowser) Close() error { return nil }

func newFakeSession(url string) (*browserManager, *fakeBrowser) {
	fb := &fakeBrowser{url: url}
	m := &browserManager{session: &browserSession{br: fb, fw: browser.NewFirewall()}}
	return m, fb
}

func TestDispatch_NavigateAndContent(t *testing.T) {
	m, fb := newFakeSession("https://github.com/")
	ctx := context.Background()

	resp := m.dispatch(ctx, "navigate", browserRequest{URL: "https://example.com"}, NewProcessTracker())
	if resp.Status != "ok" || fb.url != "https://example.com" {
		t.Fatalf("navigate failed: %+v (url=%s)", resp, fb.url)
	}
	resp = m.dispatch(ctx, "content", browserRequest{}, NewProcessTracker())
	if resp.Status != "ok" || resp.Result != "content" {
		t.Fatalf("content failed: %+v", resp)
	}
}

func TestDispatch_FillFirewallGate(t *testing.T) {
	// page on a NON-allowlisted host → fill must be gated (permission_required)
	m, fb := newFakeSession("https://evil.example/")
	resp := m.dispatch(context.Background(), "fill", browserRequest{Selector: "#pw", Value: "secret"}, NewProcessTracker())
	if resp.Status != "permission_required" {
		t.Fatalf("expected permission_required on foreign host, got %+v", resp)
	}
	if fb.fills != 0 {
		t.Fatalf("fill should NOT have executed while gated, fills=%d", fb.fills)
	}
}

func TestDispatch_FillAllowlistedHost(t *testing.T) {
	// page on an allowlisted host (github.com is in the Tranco seed) → fill allowed
	m, fb := newFakeSession("https://github.com/login")
	resp := m.dispatch(context.Background(), "fill", browserRequest{Selector: "#pw", Value: "secret"}, NewProcessTracker())
	if resp.Status != "ok" {
		t.Fatalf("expected ok fill on allowlisted host, got %+v", resp)
	}
	if fb.fills != 1 || fb.lastFilled != "secret" {
		t.Fatalf("fill should have executed, fills=%d last=%q", fb.fills, fb.lastFilled)
	}
}

func TestDispatch_FillConfirmBypassesGate(t *testing.T) {
	// foreign host but confirm=true → allowed once
	m, fb := newFakeSession("https://evil.example/")
	resp := m.dispatch(context.Background(), "fill", browserRequest{Selector: "#x", Value: "v", Confirm: true}, NewProcessTracker())
	if resp.Status != "ok" {
		t.Fatalf("expected ok with confirm=true, got %+v", resp)
	}
	if fb.fills != 1 {
		t.Fatalf("fill should have executed with confirm, fills=%d", fb.fills)
	}
}

func TestDispatch_ClickFirewallGate(t *testing.T) {
	m, fb := newFakeSession("https://evil.example/")
	resp := m.dispatch(context.Background(), "click", browserRequest{Selector: "#submit", FormActionHost: "attacker.test"}, NewProcessTracker())
	if resp.Status != "permission_required" {
		t.Fatalf("expected click gated on foreign host, got %+v", resp)
	}
	if fb.clicks != 0 {
		t.Fatalf("click should NOT execute while gated, clicks=%d", fb.clicks)
	}
}

func TestDispatch_StatusShape(t *testing.T) {
	m, _ := newFakeSession("https://github.com/")
	resp := m.dispatch(context.Background(), "status", browserRequest{}, NewProcessTracker())
	if resp.Status != "ok" {
		t.Fatalf("status failed: %+v", resp)
	}
	var st browser.Status
	if err := json.Unmarshal(resp.Data, &st); err != nil {
		t.Fatalf("status data not valid Status json: %v", err)
	}
	if st.URL != "https://github.com/" {
		t.Errorf("status URL mismatch: %q", st.URL)
	}
}

func TestBrowserResponse_JSONEnvelope(t *testing.T) {
	// permission_required envelope should serialize the firewall fields
	d := browser.Decision{Allowed: false, Status: "permission_required", Reason: "r", PageHost: "p", FormHost: "f"}
	resp := gateResp(d)
	b, err := json.Marshal(resp)
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, want := range []string{`"status":"permission_required"`, `"page_host":"p"`, `"form_host":"f"`} {
		if !strings.Contains(s, want) {
			t.Errorf("envelope missing %s in %s", want, s)
		}
	}
}
