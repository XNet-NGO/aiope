# On-device Browser

keywords: browser, webview, in-app browser, shared browser, browser_navigate, browser_content, browser_elements, browser_click, browser_fill, browser_eval, browser_back, browser_scroll, browser_open, browser_close, browser_maximize, react-safe fill, controlled input, _valueTracker, native value setter, InputEvent, CookieManager, cookie persistence, visit history, user agent, Pixel 8, Chrome 140, BrowserPanel, BrowserHolder, BrowserServer, split screen, maximize, remote browser distinction

AIOPE ships a **real Android `WebView`** that the user and the AI agent drive
**together** — the same page, the same session, the same cookies. When the agent
navigates or fills a form, the user sees it happen live in the browser panel; when
the user taps around, the agent can read the resulting page. This is a genuine
on-device browser, not a headless scraper and not a screenshot of a remote machine.

This page is written from the actual source. Primary files:
- `feature-chat/.../browser/WebBrowser.kt` — the controllable `WebView` wrapper (all agent actions)
- `feature-chat/.../browser/BrowserPanel.kt` — the Compose UI panel + `BrowserHolder` singleton
- `feature-chat/.../browser/BrowserServer.kt` — an optional localhost HTTP control server

## One shared WebView

There is a **single** `WebBrowser` instance for the whole app, held by the
`BrowserHolder` object (`BrowserPanel.kt`):

- `BrowserHolder.browser: WebBrowser?` — the one instance.
- `BrowserHolder.getOrCreate(context)` — returns it, creating it if needed. A `WebView`
  must be constructed on the main thread, so if called off-thread it posts creation to
  the main-thread `Handler` and blocks on a `CountDownLatch` (up to 5 seconds).
- `BrowserHolder.release()` — nulls the instance and posts `WebBrowser.destroy()` to free
  the `WebView` memory when the panel is dismissed.

Because both the UI panel and the agent's tools resolve the browser through the same
`BrowserHolder`, they share exactly one page, one history, and one cookie jar. The
`BrowserPanel` composable renders that same `webView` via `AndroidView` — it detaches
the view from any previous parent (`(wv.parent as? ViewGroup)?.removeView(wv)`) and
re-hosts it, so the live view moves between split-screen and full-screen without losing
state.

`WebBrowser` is designed for coroutine use: **all public action methods are `suspend`**
and safe to call from coroutines, while the `WebView` itself is always touched on the
main-thread `Handler` (`Handler(Looper.getMainLooper())`).

## The browser panel (user-facing UI)

`BrowserPanel(maximized, onToggleMaximize, modifier)` is the Compose UI. Its chrome:

- **Back** button → `browser.goBack()`
- An editable **URL bar** (`BasicTextField`) with an `ImeAction.Go` keyboard action that
  calls `browser.navigate(urlInput)` on submit; placeholder text is `"Enter URL…"`.
- **Forward** button → `browser.goForward()`
- **Refresh** button → `browser.navigate(browser.currentUrl())`
- **Maximize / Restore** button → invokes `onToggleMaximize` (icon toggles between
  `OpenInFull` and `CloseFullscreen`). This is what backs the `browser_maximize` tool —
  the panel can render **split** with chat or **full screen**.
- A thin `LinearProgressIndicator` (cyan `0xFF00E5FF`) appears only while
  `loadProgress < 100`.

On first show, `LaunchedEffect` navigates to `https://xnet.ngo` if the current URL is
still `about:blank`, then polls every 200ms to keep the URL bar and progress bar in sync
with whatever the agent (or the page itself) does.

## The `browser_*` tools

The agent controls this shared `WebView` through eleven `browser_*` tools. The eight
**action** tools map directly to `WebBrowser` methods; the three **panel** tools drive
the UI visibility/layout:

| Tool | Backing method / effect |
| --- | --- |
| `browser_navigate` | `navigate(url)` |
| `browser_content` | `getPageContent(offset, limit)` |
| `browser_elements` | `getElements()` |
| `browser_click` | `click(selector)` |
| `browser_fill` | `fill(selector, value)` |
| `browser_eval` | `evaluateJs(script)` |
| `browser_back` | `goBack()` |
| `browser_scroll` | `scroll(direction, amount)` |
| `browser_open` | show the browser panel |
| `browser_close` | hide the browser panel |
| `browser_maximize` | maximize / restore the panel |

### navigate
`navigate(url)` prepends `https://` if the string has no `://`, then loads and **waits
for the page to finish** (via a `loadDone` continuation resumed in `onPageFinished`),
with a **30-second** `withTimeout`. It returns `"Navigated to <url> — <title>"`.

### content
`getPageContent(offset = 0, limit = null)` reads `document.body?.innerText`, plus the
title and current URL, and returns a header block
(`URL: … / Title: … / Content Length: … / Showing: <offset> to <end>`) followed by the
text. When more text remains it appends `"...(truncated. Use offset=<end> to read more)"`,
so long pages paginate.

### elements
`getElements()` returns a numbered list of **interactive** elements — it selects
`a, button, input, select, textarea, [role=button], [onclick]`, skips zero-size
(hidden) elements, and for each builds a **best-effort unique CSS selector**. Selector
preference order: `#id` → `tag[name="…"]` → `[aria-label="…"]` →
`input[type="…"][placeholder="…"]` → tag + first class + `:nth-of-type(n)` for
uniqueness. It also attaches a human label from an associated `<label for>`, a wrapping
`<label>`, `textContent`, `value`, `placeholder`, or `title`. Each line looks like
`[3] #search — "Label: Query | Search"`. This is what the model reasons over to pick
selectors for `browser_click` / `browser_fill`.

### click
`click(selector)` runs `querySelector(selector).click()`. If nothing matches it returns
`"Element not found: <selector>"`; otherwise it returns the clicked tag and up to 50
characters of its text. The selector is escaped for backslashes and single quotes before
injection.

### scroll
`scroll(direction, amount = 500)` calls `window.scrollBy(0, ±amount)` — negative when
`direction == "up"` — and reports the new `window.scrollY`.

### eval
`evaluateJs(script)` runs arbitrary JavaScript in the page and returns the string result
(surrounding quotes stripped, `null` if none), under a **15-second** `withTimeout`. All
of the higher-level actions above are implemented on top of this.

### back / forward
`goBack()` and `goForward()` check `canGoBack()` / `canGoForward()` first and only move
if possible, returning a `Boolean`, under a **5-second** timeout. (Only `goBack` is
exposed as a tool, as `browser_back`.)

## React-safe form filling (and why it matters)

`fill(selector, value)` is deliberately **not** a simple `el.value = value`. Modern
front-end frameworks (React, Vue, and similar) wrap the input's `value` property with
their **own** setter and track edits through an internal value-tracker. If you assign
`el.value` directly, the framework's synthetic-event system **ignores** it — so the
field looks filled on screen but is treated as **empty on submit**. This is exactly why
naive automation fails on sites like X (Twitter), Reddit, and other modern SPAs.

`fill` uses the standard **controlled-input actuation** technique instead:

1. `el.focus()`.
2. Look up the **native** value setter from the element's prototype —
   `window.HTMLTextAreaElement.prototype` for `<textarea>`, otherwise
   `window.HTMLInputElement.prototype` — via
   `Object.getOwnPropertyDescriptor(proto, 'value').set`, and call it with
   `nativeSetter.call(el, value)`. This bypasses the framework's overriding setter and
   writes the real DOM value. (Falls back to `el.value = value` if no native setter is
   found or the call throws.)
3. **Clear the framework value-tracker** if present: `if (el._valueTracker)
   el._valueTracker.setValue('')` — so the framework notices the value actually changed.
4. Dispatch a real
   `new InputEvent('input', {bubbles:true, data:value, inputType:'insertText'})`
   followed by `new Event('change', {bubbles:true})`, prompting the framework to re-read
   the DOM value.

It returns the filled tag plus up to 50 characters of the resulting `el.value`. This
sequence is what makes agent form-fills actually register on controlled-component sites.

## Cookie persistence

The browser is configured to keep you **logged in across app restarts**. In
`WebBrowser.init()`:

- `CookieManager.getInstance().setAcceptCookie(true)` and
  `setAcceptThirdPartyCookies(webView, true)` — accept first- and third-party cookies.
- On every `onPageFinished`, `CookieManager.getInstance().flush()` writes cookies to
  disk, so authentication **survives process death**.

Storage is also enabled: `domStorageEnabled`, `databaseEnabled`, and
`cacheMode = LOAD_DEFAULT` persist DOM/local storage and cache across sessions.

## Visit history

`WebBrowser` keeps its **own** queryable visit log, separate from the `WebView`'s
internal back/forward list:

- Backed by a `ConcurrentLinkedDeque<HistoryEntry>` where
  `HistoryEntry(url, title, timestamp)`.
- `recordHistory(url, title)` runs on each `onPageFinished`. It **skips** blank URLs and
  `about:blank`, and **collapses consecutive duplicates** of the same URL.
- Bounded to `maxHistory = 500` entries (oldest evicted).
- `getHistory(limit = 100)` returns entries **most-recent-first**, limited.
- `clearHistory()` empties the log (it does **not** clear cookies or storage).

## Realistic mobile user agent

To avoid degraded or blocked pages, `WebBrowser` sets a **current, mobile-Chrome-shaped**
user agent (replacing an older stripped `"AIOPE/2"` string that some sites rejected):

```
Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36
```

Other init settings: `javaScriptEnabled = true`, `loadWithOverviewMode = true`,
`useWideViewPort = true`, pinch-zoom via `builtInZoomControls = true` with
`displayZoomControls = false`, `mediaPlaybackRequiresUserGesture = false`, and a black
background.

## Optional localhost control server

`BrowserServer` is a **minimal localhost HTTP server** for driving the same shared
browser from outside the app (e.g. scripts on the device). Details from source:

- Listens on **port 8735**, IO-dispatched coroutine, capped at `MAX_CONNECTIONS = 10`
  concurrent connections via a `Semaphore` (excess connections are closed immediately).
- All responses are JSON: `{"status":"ok|error","result":"…"}`, with
  `Access-Control-Allow-Origin: *` and `Connection: close`.
- It resolves the browser lazily through a `WeakReference<() -> WebBrowser>`; if the
  browser isn't ready it replies `"Browser not ready. Open the browser panel in the app
  first."`

Endpoints (all `GET`): `/navigate?url=`, `/content`, `/elements`, `/click?selector=`,
`/fill?selector=&value=`, `/eval?script=`, `/back`, `/scroll?direction=&amount=`,
`/status`, and `/history?limit=`. Unknown paths return an error listing the available
endpoints. `start(getBrowser)` is idempotent (no-op if already running); `stop()` cancels
the job and closes the socket.

## Not the same as the remote browser

This on-device browser is **distinct** from AIOPE's remote browser feature:

- **On-device browser (this page)** — a real `WebView` living inside the app, shared
  live between the user and the AI, driven by the `browser_*` tools. It uses the device's
  own cookies, storage, and network.
- **Remote browser** — a **separate** feature driving a **server-side** browser through
  the `remote_browser_*` tools (e.g. `remote_browser_start`, `remote_browser_navigate`,
  `remote_browser_screenshot`, `remote_browser_detect`, `remote_browser_stop`, …),
  exposed only when the optional remote tool bridge (`feature-remote`) is initialized.
  See the tools manual for the full remote set.

They are independent: the `browser_*` tools never touch the remote browser, and the
`remote_browser_*` tools never touch this on-device `WebView`.
