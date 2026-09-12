package ngo.xnet.aiope.feature.chat.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Controllable in-app WebView browser.
 * All public methods are suspend and safe to call from coroutines.
 * The WebView itself must be created/accessed on the main thread.
 */
class WebBrowser(context: Context) {

  val webView: WebView = WebView(context).apply { init() }
  private val handler = Handler(Looper.getMainLooper())
  private val currentUrl = AtomicReference("about:blank")
  private val pageTitle = AtomicReference("")
  private var loadDone: (() -> Unit)? = null
  var loadProgress: Int = 100
    private set

  // In-memory visit history (most-recent-last), bounded. Distinct from WebView's
  // internal back/forward list — this is a queryable visit log for the agent/UI.
  data class HistoryEntry(val url: String, val title: String, val timestamp: Long)
  private val history = java.util.concurrent.ConcurrentLinkedDeque<HistoryEntry>()
  private val maxHistory = 500

  private fun recordHistory(url: String, title: String) {
    if (url.isBlank() || url == "about:blank") return
    // Collapse consecutive duplicates of the same URL.
    val last = history.peekLast()
    if (last != null && last.url == url) return
    history.addLast(HistoryEntry(url, title, System.currentTimeMillis()))
    while (history.size > maxHistory) history.pollFirst()
  }

  /** Returns visit history, most-recent-first, optionally limited. */
  fun getHistory(limit: Int = 100): List<HistoryEntry> =
    history.toList().asReversed().take(limit)

  /** Clears the in-memory visit history (does not clear WebView cookies/storage). */
  fun clearHistory() = history.clear()

  @SuppressLint("SetJavaScriptEnabled")
  private fun WebView.init() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // Persist cache/content across sessions.
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.mediaPlaybackRequiresUserGesture = false
    setBackgroundColor(android.graphics.Color.BLACK)
    // Fuller, current desktop-class UA (was a stripped "AIOPE/2" string that some
    // sites reject / serve degraded pages to). Kept mobile-Chrome shaped but current.
    settings.userAgentString =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    // Cookie persistence: accept + persist cookies (incl. third-party) across restarts.
    CookieManager.getInstance().apply {
      setAcceptCookie(true)
      setAcceptThirdPartyCookies(this@init, true)
    }

    webViewClient = object : WebViewClient() {
      override fun onPageStarted(v: WebView, url: String, fav: Bitmap?) {
        currentUrl.set(url)
      }
      override fun onPageFinished(v: WebView, url: String) {
        currentUrl.set(url)
        pageTitle.set(v.title ?: "")
        recordHistory(url, v.title ?: "")
        // Flush cookies to disk so auth survives process death.
        CookieManager.getInstance().flush()
        loadDone?.invoke()
        loadDone = null
      }
    }
    webChromeClient = object : WebChromeClient() {
      override fun onProgressChanged(view: WebView, newProgress: Int) {
        loadProgress = newProgress
      }
    }
  }

  /** Navigate to a URL and wait for page load (max 30s). */
  suspend fun navigate(url: String): String {
    val target = if (url.contains("://")) url else "https://$url"
    return withTimeout(30_000) {
      suspendCancellableCoroutine { cont ->
        handler.post {
          loadDone = { cont.resume("Navigated to ${currentUrl.get()} — ${pageTitle.get()}") }
          webView.loadUrl(target)
        }
      }
    }
  }

  /** Run arbitrary JS and return the string result. */
  suspend fun evaluateJs(script: String): String = withTimeout(15_000) {
    suspendCancellableCoroutine { cont ->
      handler.post {
        webView.evaluateJavascript(script) { result ->
          cont.resume(result?.removeSurrounding("\"") ?: "null")
        }
      }
    }
  }

  /** Get page text content (innerText of body) with optional pagination. */
  suspend fun getPageContent(offset: Int = 0, limit: Int? = null): String {
    val text = evaluateJs("document.body?.innerText || ''")
    val title = evaluateJs("document.title || ''")
    val url = currentUrl.get()

    val safeOffset = offset.coerceIn(0, text.length)
    val actualLimit = limit ?: text.length
    val end = (safeOffset + actualLimit).coerceAtMost(text.length)

    var trimmed = text.substring(safeOffset, end)
    if (end < text.length) {
      trimmed += "\n...(truncated. Use offset=$end to read more)"
    }

    return "URL: $url\nTitle: $title\nContent Length: ${text.length}\nShowing: $safeOffset to $end\n\n$trimmed"
  }

  /** Click an element by CSS selector. */
  suspend fun click(selector: String): String {
    val sel = selector.replace("\\", "\\\\").replace("'", "\\'")
    val result =
      evaluateJs(
        """
      (function(){
        var el = document.querySelector('$sel');
        if (!el) return 'Element not found: $sel';
        el.click();
        return 'Clicked: ' + (el.tagName || '') + ' ' + (el.textContent || '').substring(0,50);
      })()
        """.trimIndent(),
      )
    return result
  }

  /** Fill an input element by CSS selector. */
  suspend fun fill(selector: String, value: String): String {
    val sel = selector.replace("\\", "\\\\").replace("'", "\\'")
    val escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
    // React/Vue/etc. wrap the input value with their own setter and track changes
    // via an internal value-tracker. Assigning el.value directly is IGNORED by the
    // framework's synthetic-event system, so the field appears empty on submit.
    // The fix: call the NATIVE HTMLInputElement/HTMLTextAreaElement value setter
    // (bypassing the framework's override), then dispatch a proper InputEvent so
    // the framework re-reads the DOM value. This is the standard controlled-input
    // actuation technique and is what makes fills work on X/Reddit/modern SPAs.
    val result =
      evaluateJs(
        """
      (function(){
        var el = document.querySelector('$sel');
        if (!el) return 'Element not found: $sel';
        el.focus();
        var v = '$escaped';
        var proto = (el.tagName === 'TEXTAREA')
          ? window.HTMLTextAreaElement.prototype
          : window.HTMLInputElement.prototype;
        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
        var nativeSetter = desc && desc.set;
        try {
          if (nativeSetter) { nativeSetter.call(el, v); } else { el.value = v; }
        } catch (e) { el.value = v; }
        // Clear any framework value-tracker so it detects the change.
        if (el._valueTracker) { el._valueTracker.setValue(''); }
        el.dispatchEvent(new InputEvent('input', {bubbles:true, data:v, inputType:'insertText'}));
        el.dispatchEvent(new Event('change', {bubbles:true}));
        return 'Filled: ' + (el.tagName || '') + ' with ' + (el.value||'').substring(0,50);
      })()
        """.trimIndent(),
      )
    return result
  }

  /** Get all interactive elements on the page for the LLM to reason about. */
  suspend fun getElements(): String = evaluateJs(
    """
    (function(){
      var els = document.querySelectorAll('a,button,input,select,textarea,[role=button],[onclick]');
      var out = [];
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        var r = e.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) continue;

        // Build a unique selector
        var sel = '';
        if (e.id) { sel = '#' + e.id; }
        else if (e.name) { sel = e.tagName.toLowerCase() + '[name="' + e.name + '"]'; }
        else if (e.getAttribute('aria-label')) { sel = '[aria-label="' + e.getAttribute('aria-label').replace(/"/g, '\\"') + '"]'; }
        else if (e.type && e.tagName === 'INPUT') { sel = 'input[type="' + e.type + '"]'; if (e.placeholder) sel += '[placeholder="' + e.placeholder.substring(0,30).replace(/"/g, '\\"') + '"]'; }
        else {
          sel = e.tagName.toLowerCase();
          if (e.className && typeof e.className === 'string' && e.className.trim()) sel += '.' + e.className.trim().split(/\s+/)[0].replace(/[:\[\]]/g, '\\\\$&');
          // Add nth-of-type for uniqueness
          var parent = e.parentElement;
          if (parent) {
            try {
              var siblings = parent.querySelectorAll(':scope > ' + sel.split('[')[0]);
              if (siblings.length > 1) {
                for (var j = 0; j < siblings.length; j++) { if (siblings[j] === e) { sel += ':nth-of-type(' + (j+1) + ')'; break; } }
              }
            } catch(e) {}
          }
        }

        // Description / Semantic Context
        var label = '';
        if (e.id) {
           try {
               var escapedId = e.id.replace(/"/g, '\\"');
               var labelEl = document.querySelector('label[for="' + escapedId + '"]');
               if (labelEl) label = "Label: " + labelEl.textContent.trim().substring(0,50) + " | ";
           } catch(err) {}
        }
        if (!label) {
           var parentLabel = e.closest('label');
           if (parentLabel) label = "Label: " + parentLabel.textContent.trim().substring(0,50) + " | ";
        }

        if (e.textContent && e.textContent.trim()) label += e.textContent.trim().substring(0,50);
        else if (e.value) label += 'val=' + e.value.substring(0,30);
        else if (e.placeholder) label += 'placeholder=' + e.placeholder.substring(0,30);
        else if (e.title) label += e.title.substring(0,40);

        out.push('[' + (out.length+1) + '] ' + sel + (label ? ' — "' + label + '"' : ''));
      }
      return out.join('\\n');
    })()
    """.trimIndent(),
  )

  /** Capture screenshot as JPEG bytes. */
  fun screenshotSync(): ByteArray? {
    val bmp = Bitmap.createBitmap(webView.width.coerceAtLeast(1), webView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    webView.draw(canvas)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
    bmp.recycle()
    return out.toByteArray()
  }

  /** Scroll the page. direction: "up" or "down", amount in pixels. */
  suspend fun scroll(direction: String, amount: Int = 500): String = evaluateJs(
    "window.scrollBy(0, ${if (direction == "up") -amount else amount}); 'Scrolled $direction ${amount}px, now at ' + window.scrollY",
  )

  suspend fun goBack(): Boolean = withTimeout(5_000) {
    suspendCancellableCoroutine { cont ->
      handler.post {
        val can = webView.canGoBack()
        if (can) webView.goBack()
        cont.resume(can)
      }
    }
  }
  suspend fun goForward(): Boolean = withTimeout(5_000) {
    suspendCancellableCoroutine { cont ->
      handler.post {
        val can = webView.canGoForward()
        if (can) webView.goForward()
        cont.resume(can)
      }
    }
  }
  fun currentUrl(): String = currentUrl.get()
  fun title(): String = pageTitle.get()
  fun destroy() {
    handler.post { webView.destroy() }
  }
}
