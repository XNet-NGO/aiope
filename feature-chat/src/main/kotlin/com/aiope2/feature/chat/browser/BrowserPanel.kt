package com.aiope2.feature.chat.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Singleton browser instance shared between panel UI and tool calls. */
object BrowserHolder {
  var browser: WebBrowser? = null
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())

  fun getOrCreate(context: android.content.Context): WebBrowser {
    browser?.let { return it }
    // Must create WebView on main thread
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      return WebBrowser(context.applicationContext).also { browser = it }
    }
    // Block calling thread while creating on main
    val latch = java.util.concurrent.CountDownLatch(1)
    handler.post {
      if (browser == null) browser = WebBrowser(context.applicationContext)
      latch.countDown()
    }
    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    return browser!!
  }
}

@Composable
fun BrowserPanel(maximized: Boolean = false, onToggleMaximize: () -> Unit = {}, modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val browser = remember { BrowserHolder.getOrCreate(ctx) }
  var url by remember { mutableStateOf(browser.currentUrl()) }
  var urlInput by remember { mutableStateOf("") }
  var editing by remember { mutableStateOf(false) }
  var progress by remember { mutableIntStateOf(100) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    if (browser.currentUrl() == "about:blank") browser.navigate("https://www.google.com")
    while (true) {
      delay(200)
      val cur = browser.currentUrl()
      if (cur != url) {
        url = cur
        if (!editing) urlInput = cur
      }
      progress = browser.loadProgress
    }
  }
  LaunchedEffect(url) { if (!editing) urlInput = url }

  Column(modifier.background(Color.Black)) {
    Row(
      Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Left: Back
      IconButton(onClick = { scope.launch { browser.goBack() } }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(16.dp), tint = Color.White)
      }
      // Center: URL input
      BasicTextField(
        value = urlInput,
        onValueChange = {
          urlInput = it
          editing = true
        },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
          editing = false
          scope.launch { browser.navigate(urlInput) }
        }),
        modifier = Modifier.weight(1f).background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
          if (urlInput.isEmpty()) Text("Enter URL…", color = Color(0xFF666666), fontSize = 12.sp)
          inner()
        },
      )
      // Right: Forward, Refresh, Maximize
      IconButton(onClick = { scope.launch { browser.goForward() } }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", Modifier.size(16.dp), tint = Color.White)
      }
      IconButton(onClick = { scope.launch { browser.navigate(browser.currentUrl()) } }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.Refresh, "Refresh", Modifier.size(16.dp), tint = Color.White)
      }
      IconButton(onClick = onToggleMaximize, modifier = Modifier.size(32.dp)) {
        Icon(
          if (maximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
          if (maximized) "Restore" else "Maximize",
          Modifier.size(16.dp),
          tint = Color.White,
        )
      }
    }
    // Loading progress bar
    if (progress < 100) {
      LinearProgressIndicator(
        progress = { progress / 100f },
        modifier = Modifier.fillMaxWidth().height(2.dp),
        color = Color(0xFF00E5FF),
        trackColor = Color.Transparent,
      )
    }
    AndroidView(
      factory = {
        val wv = browser.webView
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        wv
      },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
