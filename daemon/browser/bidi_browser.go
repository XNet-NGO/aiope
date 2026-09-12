package browser

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"
)

// bidiBrowser implements Browser over a WebDriver BiDi connection (Firefox).
type bidiBrowser struct {
	conn    *BiDiConnection
	context string // cached target browsingContext id
}

// NewBiDiBrowser wraps an established BiDiConnection as a Browser.
func NewBiDiBrowser(conn *BiDiConnection) Browser {
	return &bidiBrowser{conn: conn}
}

// EstablishSession creates the BiDi session (session.new) and subscribes to the
// browsingContext load events. Firefox requires an explicit session before most
// commands; without it, commands fail with "invalid session id". Safe to call
// once after Connect. Errors from session.new are tolerated (some builds
// auto-create a session), but the subscribe is best-effort.
func (b *bidiBrowser) EstablishSession(ctx context.Context) error {
	// session.new — establish the BiDi session. Tolerate "already created".
	if _, err := b.conn.SendCommand(ctx, "session.new", map[string]any{
		"capabilities": map[string]any{},
	}, 15*time.Second); err != nil {
		// Not necessarily fatal; verify with session.status before giving up.
		if _, serr := b.conn.SendCommand(ctx, "session.status", map[string]any{}, 5*time.Second); serr != nil {
			return fmt.Errorf("bidi session.new failed and status unavailable: %w", err)
		}
	}
	// Subscribe to load/domContentLoaded so navigate waits are reliable (best-effort).
	_, _ = b.conn.SendCommand(ctx, "session.subscribe", map[string]any{
		"events": []string{"browsingContext.load", "browsingContext.domContentLoaded"},
	}, 10*time.Second)
	return nil
}

// ensure interface compliance at compile time
var _ Browser = (*bidiBrowser)(nil)

func (b *bidiBrowser) Engine() Engine { return EngineFirefox }

func (b *bidiBrowser) Contexts(ctx context.Context) ([]PageContext, error) {
	raw, err := b.conn.SendCommand(ctx, "browsingContext.getTree", map[string]any{}, 15*time.Second)
	if err != nil {
		return nil, err
	}
	var tree struct {
		Contexts []struct {
			Context string `json:"context"`
			URL     string `json:"url"`
		} `json:"contexts"`
	}
	if err := json.Unmarshal(raw, &tree); err != nil {
		return nil, fmt.Errorf("parse getTree: %w", err)
	}
	out := make([]PageContext, 0, len(tree.Contexts))
	for _, c := range tree.Contexts {
		out = append(out, PageContext{ID: c.Context, URL: c.URL})
	}
	return out, nil
}

func (b *bidiBrowser) Target(ctx context.Context) (string, error) {
	if b.context != "" {
		return b.context, nil
	}
	ctxs, err := b.Contexts(ctx)
	if err != nil {
		return "", err
	}
	if len(ctxs) == 0 {
		return "", fmt.Errorf("no browsing contexts")
	}
	b.context = ctxs[0].ID
	return b.context, nil
}

func (b *bidiBrowser) Navigate(ctx context.Context, url string) error {
	target, err := b.Target(ctx)
	if err != nil {
		return err
	}
	_, err = b.conn.SendCommand(ctx, "browsingContext.navigate", map[string]any{
		"context": target,
		"url":     url,
		"wait":    "complete",
	}, 45*time.Second)
	return err
}

// Eval runs expression via BiDi script.evaluate (out of page context = CSP-immune).
func (b *bidiBrowser) Eval(ctx context.Context, expression string) ([]byte, error) {
	target, err := b.Target(ctx)
	if err != nil {
		return nil, err
	}
	raw, err := b.conn.SendCommand(ctx, "script.evaluate", map[string]any{
		"expression":   expression,
		"target":       map[string]any{"context": target},
		"awaitPromise": true,
	}, 20*time.Second)
	if err != nil {
		return nil, err
	}
	var res struct {
		Result struct {
			Value json.RawMessage `json:"value"`
		} `json:"result"`
		ExceptionDetails json.RawMessage `json:"exceptionDetails"`
	}
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, fmt.Errorf("parse eval: %w", err)
	}
	if len(res.ExceptionDetails) > 0 {
		return nil, fmt.Errorf("eval threw: %s", string(res.ExceptionDetails))
	}
	return res.Result.Value, nil
}

// evalString runs a JS expression expected to yield a string, unwrapping the
// BiDi value envelope.
func (b *bidiBrowser) evalString(ctx context.Context, expression string) (string, error) {
	v, err := b.Eval(ctx, expression)
	if err != nil {
		return "", err
	}
	var s string
	if err := json.Unmarshal(v, &s); err != nil {
		// Non-string result; return raw JSON.
		return string(v), nil
	}
	return s, nil
}

func (b *bidiBrowser) Content(ctx context.Context, offset, limit int) (string, error) {
	// Return full innerText; caller paginates. Kept simple + engine-agnostic.
	text, err := b.evalString(ctx, "document.body ? document.body.innerText : ''")
	if err != nil {
		return "", err
	}
	return paginate(text, offset, limit), nil
}

func (b *bidiBrowser) Elements(ctx context.Context) (string, error) {
	return b.evalString(ctx, elementsScript)
}

func (b *bidiBrowser) Click(ctx context.Context, selector string) (string, error) {
	return b.evalString(ctx, clickScript(selector))
}

func (b *bidiBrowser) Fill(ctx context.Context, selector, value string) (string, error) {
	return b.evalString(ctx, fillScript(selector, value))
}

func (b *bidiBrowser) Screenshot(ctx context.Context) ([]byte, error) {
	target, err := b.Target(ctx)
	if err != nil {
		return nil, err
	}
	raw, err := b.conn.SendCommand(ctx, "browsingContext.captureScreenshot", map[string]any{
		"context": target,
	}, 20*time.Second)
	if err != nil {
		return nil, err
	}
	var res struct {
		Data string `json:"data"` // base64
	}
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, fmt.Errorf("parse screenshot: %w", err)
	}
	return base64.StdEncoding.DecodeString(res.Data)
}

func (b *bidiBrowser) Back(ctx context.Context) (bool, error) {
	target, err := b.Target(ctx)
	if err != nil {
		return false, err
	}
	_, err = b.conn.SendCommand(ctx, "browsingContext.traverseHistory", map[string]any{
		"context": target,
		"delta":   -1,
	}, 10*time.Second)
	if err != nil {
		return false, nil // no history / cannot go back
	}
	return true, nil
}

func (b *bidiBrowser) Scroll(ctx context.Context, dir ScrollDir, px int) (string, error) {
	return b.evalString(ctx, scrollScript(dir, px))
}

func (b *bidiBrowser) Status(ctx context.Context) (Status, error) {
	s, err := b.evalString(ctx, "JSON.stringify({url: location.href, title: document.title})")
	if err != nil {
		return Status{Engine: EngineFirefox}, err
	}
	var st struct {
		URL   string `json:"url"`
		Title string `json:"title"`
	}
	_ = json.Unmarshal([]byte(s), &st)
	return Status{Engine: EngineFirefox, URL: st.URL, Title: st.Title}, nil
}

func (b *bidiBrowser) Close() error {
	b.conn.Close()
	return nil
}
