package browser

import "context"

// Engine identifies which browser protocol backs a Browser implementation.
type Engine string

const (
	EngineFirefox Engine = "firefox" // WebDriver BiDi
	EngineChrome  Engine = "chrome"  // Chrome DevTools Protocol
)

// PageContext is one browsing context / tab, engine-agnostic.
//
// ID is the handle the backend uses to target commands: a BiDi browsingContext
// id for Firefox, or a CDP targetId for Chrome. Callers treat it as opaque.
type PageContext struct {
	ID  string `json:"id"`
	URL string `json:"url"`
}

// Status is a lightweight snapshot of the active context.
type Status struct {
	Engine Engine `json:"engine"`
	URL    string `json:"url"`
	Title  string `json:"title"`
}

// ScrollDir is the direction for Scroll.
type ScrollDir string

const (
	ScrollUp   ScrollDir = "up"
	ScrollDown ScrollDir = "down"
)

// Browser is the engine-agnostic contract implemented by both the Firefox/BiDi
// backend (bidiBrowser) and the Chrome/CDP backend (cdpBrowser). The daemon
// verbs and the action firewall are written against THIS interface only, so the
// two engines are interchangeable and a future on-device GeckoView backend can
// slot in the same way.
//
// The verb set intentionally mirrors AIOPE's existing on-device BrowserServer
// surface (navigate/content/elements/click/fill/eval/back/scroll/screenshot/
// status) so the agent-facing tool schema is identical across local and remote.
type Browser interface {
	// Engine reports which protocol backs this instance.
	Engine() Engine

	// Contexts lists open browsing contexts (tabs).
	Contexts(ctx context.Context) ([]PageContext, error)

	// Target returns the context id commands operate on (the active/first tab).
	Target(ctx context.Context) (string, error)

	// Navigate loads url in the target context and waits for load completion.
	Navigate(ctx context.Context, url string) error

	// Eval runs a JS expression OUT OF PAGE CONTEXT (CSP-immune) and returns the
	// JSON-encoded result value. BiDi script.evaluate / CDP Runtime.Evaluate.
	Eval(ctx context.Context, expression string) ([]byte, error)

	// Content returns the page's visible text (innerText of body), paginated.
	Content(ctx context.Context, offset, limit int) (string, error)

	// Elements returns a listing of interactive elements with synthesized
	// selectors for the agent to reason about.
	Elements(ctx context.Context) (string, error)

	// Click clicks the element matching selector. Firewall-gated by the caller.
	Click(ctx context.Context, selector string) (string, error)

	// Fill sets the value of the input matching selector. Firewall-gated.
	Fill(ctx context.Context, selector, value string) (string, error)

	// Screenshot captures the viewport as image bytes (JPEG).
	Screenshot(ctx context.Context) ([]byte, error)

	// Back navigates to the previous history entry. Reports whether it moved.
	Back(ctx context.Context) (bool, error)

	// Scroll scrolls the page by px pixels in dir.
	Scroll(ctx context.Context, dir ScrollDir, px int) (string, error)

	// Status returns a lightweight snapshot (engine/url/title).
	Status(ctx context.Context) (Status, error)

	// Close releases the connection. It does NOT necessarily terminate the
	// browser process (the daemon's ProcessTracker owns process lifecycle).
	Close() error
}
