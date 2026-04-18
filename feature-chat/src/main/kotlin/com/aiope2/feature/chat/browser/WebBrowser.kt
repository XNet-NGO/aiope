package com.aiope2.feature.chat.browser

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

  @SuppressLint("SetJavaScriptEnabled")
  private fun WebView.init() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36 AIOPE/2"
    webViewClient = object : WebViewClient() {
      override fun onPageStarted(v: WebView, url: String, fav: Bitmap?) {
        currentUrl.set(url)
      }
      override fun onPageFinished(v: WebView, url: String) {
        currentUrl.set(url)
        pageTitle.set(v.title ?: "")
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

  /** Get page text content (innerText of body). */
  suspend fun getPageContent(): String {
    val text = evaluateJs("document.body?.innerText || ''")
    val title = evaluateJs("document.title || ''")
    val url = currentUrl.get()
    val trimmed = if (text.length > 12000) text.take(12000) + "\n...(truncated)" else text
    return "URL: $url\nTitle: $title\n\n$trimmed"
  }

  /** Click an element by CSS selector. */
  suspend fun click(selector: String): String {
    val result =
      evaluateJs(
        """
      (function(){
        var el = document.querySelector('$selector');
        if (!el) return 'Element not found: $selector';
        el.click();
        return 'Clicked: ' + (el.tagName || '') + ' ' + (el.textContent || '').substring(0,50);
      })()
        """.trimIndent(),
      )
    return result
  }

  /** Fill an input element by CSS selector. */
  suspend fun fill(selector: String, value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
    val result =
      evaluateJs(
        """
      (function(){
        var el = document.querySelector('$selector');
        if (!el) return 'Element not found: $selector';
        el.focus();
        el.value = '$escaped';
        el.dispatchEvent(new Event('input', {bubbles:true}));
        el.dispatchEvent(new Event('change', {bubbles:true}));
        return 'Filled: ' + (el.tagName || '') + ' with ' + el.value.substring(0,50);
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
      for (var i = 0; i < Math.min(els.length, 50); i++) {
        var e = els[i];
        var r = e.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) continue;
        var desc = e.tagName.toLowerCase();
        if (e.id) desc += '#' + e.id;
        if (e.className && typeof e.className === 'string') desc += '.' + e.className.split(' ')[0];
        if (e.type) desc += '[type=' + e.type + ']';
        if (e.href) desc += ' href=' + e.href.substring(0,60);
        if (e.textContent) desc += ' "' + e.textContent.trim().substring(0,40) + '"';
        if (e.value) desc += ' val=' + e.value.substring(0,30);
        if (e.placeholder) desc += ' placeholder=' + e.placeholder.substring(0,30);
        out.push(desc);
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
