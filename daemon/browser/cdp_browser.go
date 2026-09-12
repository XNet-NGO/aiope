package browser

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/mafredri/cdp"
	"github.com/mafredri/cdp/devtool"
	"github.com/mafredri/cdp/protocol/page"
	"github.com/mafredri/cdp/protocol/runtime"
	"github.com/mafredri/cdp/rpcc"
)

// cdpBrowser implements Browser over the Chrome DevTools Protocol (Chrome).
type cdpBrowser struct {
	devtoolsURL string
	devt        *devtool.DevTools
	conn        *rpcc.Conn
	client      *cdp.Client
	target      string // page target id (WebSocketDebuggerURL fragment)
}

// NewCDPBrowser connects to a running Chrome's DevTools HTTP endpoint (e.g.
// http://127.0.0.1:9222), attaches to a page target, and returns a Browser.
//
// IMPORTANT: connCtx governs the LIFETIME of the underlying rpcc WebSocket. It
// MUST be a long-lived (session) context, NOT a per-request context — rpcc ties
// the connection to this ctx and closes it when the ctx is cancelled. Passing a
// request ctx here caused the connection to drop right after `start` returned
// ("websocket close 1006 EOF" on the next verb). opCtx is used only for the
// initial setup calls.
func NewCDPBrowser(connCtx context.Context, opCtx context.Context, devtoolsURL string) (Browser, error) {
	devt := devtool.New(devtoolsURL)
	pt, err := devt.Get(opCtx, devtool.Page)
	if err != nil {
		pt, err = devt.Create(opCtx)
		if err != nil {
			return nil, fmt.Errorf("cdp get/create page target: %w", err)
		}
	}
	conn, err := rpcc.DialContext(connCtx, pt.WebSocketDebuggerURL)
	if err != nil {
		return nil, fmt.Errorf("cdp rpcc dial: %w", err)
	}
	c := cdp.NewClient(conn)
	if err := c.Page.Enable(opCtx); err != nil {
		conn.Close()
		return nil, fmt.Errorf("cdp page.enable: %w", err)
	}
	return &cdpBrowser{
		devtoolsURL: devtoolsURL,
		devt:        devt,
		conn:        conn,
		client:      c,
		target:      pt.ID,
	}, nil
}

func (b *cdpBrowser) Engine() Engine { return EngineChrome }

func (b *cdpBrowser) Contexts(ctx context.Context) ([]PageContext, error) {
	targets, err := b.devt.List(ctx)
	if err != nil {
		return nil, err
	}
	var out []PageContext
	for _, t := range targets {
		if t.Type == devtool.Page {
			out = append(out, PageContext{ID: t.ID, URL: t.URL})
		}
	}
	return out, nil
}

func (b *cdpBrowser) Target(ctx context.Context) (string, error) {
	if b.target != "" {
		return b.target, nil
	}
	ctxs, err := b.Contexts(ctx)
	if err != nil {
		return "", err
	}
	if len(ctxs) == 0 {
		return "", fmt.Errorf("no page targets")
	}
	b.target = ctxs[0].ID
	return b.target, nil
}

func (b *cdpBrowser) Navigate(ctx context.Context, url string) error {
	domContent, err := b.client.Page.DOMContentEventFired(ctx)
	if err != nil {
		return err
	}
	defer domContent.Close()
	if _, err := b.client.Page.Navigate(ctx, page.NewNavigateArgs(url)); err != nil {
		return err
	}
	_, err = domContent.Recv()
	return err
}

// Eval runs expression via CDP Runtime.Evaluate (out of page context = CSP-immune).
func (b *cdpBrowser) Eval(ctx context.Context, expression string) ([]byte, error) {
	args := runtime.NewEvaluateArgs(expression).SetReturnByValue(true).SetAwaitPromise(true)
	res, err := b.client.Runtime.Evaluate(ctx, args)
	if err != nil {
		return nil, err
	}
	if res.ExceptionDetails != nil {
		return nil, fmt.Errorf("eval threw: %v", res.ExceptionDetails)
	}
	return res.Result.Value, nil
}

func (b *cdpBrowser) evalString(ctx context.Context, expression string) (string, error) {
	v, err := b.Eval(ctx, expression)
	if err != nil {
		return "", err
	}
	var s string
	if err := json.Unmarshal(v, &s); err != nil {
		return string(v), nil
	}
	return s, nil
}

func (b *cdpBrowser) Content(ctx context.Context, offset, limit int) (string, error) {
	text, err := b.evalString(ctx, "document.body ? document.body.innerText : ''")
	if err != nil {
		return "", err
	}
	return paginate(text, offset, limit), nil
}

func (b *cdpBrowser) Elements(ctx context.Context) (string, error) {
	return b.evalString(ctx, elementsScript)
}

func (b *cdpBrowser) Click(ctx context.Context, selector string) (string, error) {
	return b.evalString(ctx, clickScript(selector))
}

func (b *cdpBrowser) Fill(ctx context.Context, selector, value string) (string, error) {
	return b.evalString(ctx, fillScript(selector, value))
}

func (b *cdpBrowser) Screenshot(ctx context.Context) ([]byte, error) {
	args := page.NewCaptureScreenshotArgs().SetFormat("jpeg").SetQuality(70)
	res, err := b.client.Page.CaptureScreenshot(ctx, args)
	if err != nil {
		return nil, err
	}
	return res.Data, nil // already []byte (base64-decoded by the binding)
}

func (b *cdpBrowser) Back(ctx context.Context) (bool, error) {
	hist, err := b.client.Page.GetNavigationHistory(ctx)
	if err != nil {
		return false, err
	}
	if hist.CurrentIndex <= 0 {
		return false, nil // no previous entry
	}
	prev := hist.Entries[hist.CurrentIndex-1]
	err = b.client.Page.NavigateToHistoryEntry(ctx, page.NewNavigateToHistoryEntryArgs(prev.ID))
	if err != nil {
		return false, err
	}
	return true, nil
}

func (b *cdpBrowser) Scroll(ctx context.Context, dir ScrollDir, px int) (string, error) {
	return b.evalString(ctx, scrollScript(dir, px))
}

func (b *cdpBrowser) Status(ctx context.Context) (Status, error) {
	s, err := b.evalString(ctx, "JSON.stringify({url: location.href, title: document.title})")
	if err != nil {
		return Status{Engine: EngineChrome}, err
	}
	var st struct {
		URL   string `json:"url"`
		Title string `json:"title"`
	}
	_ = json.Unmarshal([]byte(s), &st)
	return Status{Engine: EngineChrome, URL: st.URL, Title: st.Title}, nil
}

func (b *cdpBrowser) Close() error {
	if b.conn != nil {
		return b.conn.Close()
	}
	return nil
}

// ensure interface compliance at compile time
var _ Browser = (*cdpBrowser)(nil)
