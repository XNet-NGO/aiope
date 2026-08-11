package ngo.xnet.aiope.feature.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports LaTeX document content to PDF via WebView + KaTeX rendering.
 */
object LatexPdfExporter {

  /**
   * Opens the system print dialog for the given LaTeX content (user picks
   * "Save as PDF" or a printer). Safe to call from any thread — the WebView
   * work is marshalled onto the main thread, and the WebView is only
   * destroyed after the print job finishes, so the dialog can never race
   * the renderer.
   */
  fun export(context: Context, latexContent: String) {
    val activity = context.findActivity()
    if (activity == null) {
      Toast.makeText(context, "PDF export requires an active screen", Toast.LENGTH_LONG).show()
      return
    }
    // WebView creation + printing must happen on the main thread.
    Handler(Looper.getMainLooper()).post { doExport(activity, latexContent) }
  }

  private fun doExport(activity: Activity, latexContent: String) {
    var webView: WebView? = null
    var host: FrameLayout? = null
    try {
      val html = buildHtml(latexContent)
      val web = WebView(activity)
      webView = web
      web.settings.javaScriptEnabled = true

      // Give the WebView real dimensions and attach it to the window so the
      // print renderer produces content. An unattached / zero-size WebView
      // yields a blank PDF on most devices. It is parked off-screen so the
      // user never sees it flash up.
      val w = (8.5f * 160).toInt() // ~US Letter width @ 160dpi
      val h = (11f * 160).toInt()
      web.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
      )
      web.layout(0, 0, w, h)

      val hostLocal = FrameLayout(activity)
      host = hostLocal
      hostLocal.addView(web, FrameLayout.LayoutParams(w, h))
      hostLocal.translationY = 100000f
      (activity.window?.decorView as? ViewGroup)?.addView(hostLocal, FrameLayout.LayoutParams(w, h))

      val printed = AtomicBoolean(false)
      val doPrint = Runnable {
        if (printed.compareAndSet(false, true)) {
          print(activity, web, hostLocal)
        }
      }

      web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          // Give KaTeX a moment to render, then open the system print dialog.
          view?.postDelayed(doPrint, 1200)
        }

        override fun onReceivedError(
          view: WebView?,
          request: WebResourceRequest?,
          error: WebResourceError?,
        ) {
          // CDN hiccups must not block printing — the dialog still opens.
        }
      }
      web.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "UTF-8", null)

      // Safety net: if the page never finishes loading (offline / slow CDN),
      // force the print dialog open anyway instead of silently doing nothing.
      Handler(Looper.getMainLooper()).postDelayed(doPrint, 12_000)
    } catch (t: Throwable) {
      cleanup(webView, host)
      printed.set(true) // Prevent delayed callbacks from firing print
      Toast.makeText(activity, "PDF export failed: ${t.message}", Toast.LENGTH_LONG).show()
    }
  }

  private fun print(activity: Activity, webView: WebView, host: FrameLayout) {
    try {
      val jobName = "AIOPE Conversation"
      val delegate = webView.createPrintDocumentAdapter(jobName)
      val adapter = object : PrintDocumentAdapter() {
        override fun onStart() = delegate.onStart()

        override fun onWrite(
          attributes: PrintAttributes?,
          pages: List<PageRange>?,
          dest: ParcelFileDescriptor?,
          callback: PrintDocumentAdapter.WriteResultCallback?,
        ) = delegate.onWrite(attributes, pages, dest, callback)

        override fun onFinish() {
          try { delegate.onFinish() } catch (_: Throwable) {}
          cleanup(webView, host)
        }

        override fun onCancel() {
          try { delegate.onCancel() } catch (_: Throwable) {}
          cleanup(webView, host)
        }
      }
      val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
      printManager.print(
        jobName,
        adapter,
        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build(),
      )
    } catch (t: Throwable) {
      cleanup(webView, host)
      Toast.makeText(activity, "PDF export failed: ${t.message}", Toast.LENGTH_LONG).show()
    }
  }

  private fun cleanup(webView: WebView?, host: FrameLayout?) {
    try { (host?.parent as? ViewGroup)?.removeView(host) } catch (_: Throwable) {}
    try { webView?.destroy() } catch (_: Throwable) {}
  }

  /** Unwrap ContextWrapper chains (e.g. ContextThemeWrapper) to find the Activity. */
  private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
      if (c is Activity) return c
      c = c.baseContext
    }
    return null
  }

  fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share PDF"))
  }

  private fun buildHtml(latex: String): String {
    val body = transpileToHtml(latex)
    return """<!DOCTYPE html>
<html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/contrib/auto-render.min.js"></script>
<style>
body { font-family: serif; font-size: 12pt; line-height: 1.6; max-width: 7in; margin: 0.5in auto; color: #222; }
h1 { font-size: 20pt; margin-top: 24pt; }
h2 { font-size: 16pt; margin-top: 18pt; }
h3 { font-size: 14pt; margin-top: 14pt; }
pre { background: #f5f5f5; padding: 8pt; font-size: 10pt; overflow-x: auto; border-radius: 4pt; }
code { font-family: monospace; font-size: 10pt; background: #f0f0f0; padding: 1pt 3pt; border-radius: 2pt; }
blockquote { border-left: 3pt solid #ccc; margin-left: 0; padding-left: 12pt; color: #555; }
table { border-collapse: collapse; margin: 12pt 0; }
th, td { border: 1pt solid #999; padding: 4pt 8pt; }
th { background: #eee; font-weight: bold; }
hr { border: none; border-top: 1pt solid #ccc; margin: 16pt 0; }
</style>
</head><body>
$body
<script>renderMathInElement(document.body, {delimiters: [
  {left: "$${'$'}${'$'}", right: "$${'$'}${'$'}", display: true},
  {left: "${'$'}", right: "${'$'}", display: false}
]});</script>
</body></html>"""
  }

  private fun transpileToHtml(latex: String): String {
    var s = latex
    // Strip preamble
    s = s.replace(Regex("(?s).*?\\\\begin\\{document\\}\\s*"), "")
    s = s.replace(Regex("\\\\end\\{document\\}.*$"), "")

    // Preserve math environments for KaTeX (before stripping other \begin/\end)
    s = s.replace(Regex("\\\\begin\\{(equation|align|gather|multline|flalign|alignat)\\*?\\}"), "\n$$$$\n\\\\begin{$1}")
    s = s.replace(Regex("\\\\end\\{(equation|align|gather|multline|flalign|alignat)\\*?\\}"), "\\\\end{$1}\n$$$$\n")

    // Figures / images
    s = s.replace(Regex("\\\\includegraphics(\\[[^]]*])?\\{([^}]*)\\}"), "<img src=\"$2\" style=\"max-width:100%\">")
    s = s.replace(Regex("\\\\caption\\{([^}]*)\\}"), "<p><i>$1</i></p>")

    // Headings
    s = s.replace(Regex("\\\\chapter\\{([^}]*)\\}"), "<h1>$1</h1>")
    s = s.replace(Regex("\\\\section\\{([^}]*)\\}"), "<h2>$1</h2>")
    s = s.replace(Regex("\\\\subsection\\{([^}]*)\\}"), "<h3>$1</h3>")
    s = s.replace(Regex("\\\\subsubsection\\{([^}]*)\\}"), "<h4>$1</h4>")
    s = s.replace(Regex("\\\\title\\{([^}]*)\\}"), "<h1>$1</h1>")
    s = s.replace(Regex("\\\\author\\{([^}]*)\\}"), "<p><i>$1</i></p>")
    s = s.replace(Regex("\\\\date\\{([^}]*)\\}"), "<p><i>$1</i></p>")
    s = s.replace(Regex("\\\\maketitle"), "")

    // Nested-safe inline formatting (handle innermost first, repeat)
    for (i in 0..3) {
      s = s.replace(Regex("\\\\textbf\\{([^{}]*)\\}"), "<b>$1</b>")
      s = s.replace(Regex("\\\\textit\\{([^{}]*)\\}"), "<i>$1</i>")
      s = s.replace(Regex("\\\\emph\\{([^{}]*)\\}"), "<i>$1</i>")
      s = s.replace(Regex("\\\\texttt\\{([^{}]*)\\}"), "<code>$1</code>")
      s = s.replace(Regex("\\\\underline\\{([^{}]*)\\}"), "<u>$1</u>")
      s = s.replace(Regex("\\\\textsc\\{([^{}]*)\\}"), "<span style=\"font-variant:small-caps\">$1</span>")
    }
    s = s.replace(Regex("\\\\footnote\\{([^}]*)\\}"), " <sup>($1)</sup>")

    // Lists
    s = s.replace(Regex("\\\\begin\\{itemize\\}"), "<ul>")
    s = s.replace(Regex("\\\\end\\{itemize\\}"), "</ul>")
    s = s.replace(Regex("\\\\begin\\{enumerate\\}"), "<ol>")
    s = s.replace(Regex("\\\\end\\{enumerate\\}"), "</ol>")
    s = s.replace(Regex("\\\\item\\s*"), "<li>")

    // Code
    s = s.replace(Regex("\\\\begin\\{verbatim\\}"), "<pre>")
    s = s.replace(Regex("\\\\end\\{verbatim\\}"), "</pre>")
    s = s.replace(Regex("\\\\begin\\{lstlisting\\}(\\[[^]]*])?"), "<pre>")
    s = s.replace(Regex("\\\\end\\{lstlisting\\}"), "</pre>")
    s = s.replace(Regex("\\\\begin\\{minted\\}(\\{[^}]*\\})?"), "<pre>")
    s = s.replace(Regex("\\\\end\\{minted\\}"), "</pre>")

    // Quotes / abstract
    s = s.replace(Regex("\\\\begin\\{quote\\}"), "<blockquote>")
    s = s.replace(Regex("\\\\end\\{quote\\}"), "</blockquote>")
    s = s.replace(Regex("\\\\begin\\{quotation\\}"), "<blockquote>")
    s = s.replace(Regex("\\\\end\\{quotation\\}"), "</blockquote>")
    s = s.replace(Regex("\\\\begin\\{abstract\\}"), "<blockquote><b>Abstract</b><br>")
    s = s.replace(Regex("\\\\end\\{abstract\\}"), "</blockquote>")

    // Tables: \begin{tabular}{cols} ... \end{tabular}
    s = transpileTables(s)

    // Links
    s = s.replace(Regex("\\\\href\\{([^}]*)\\}\\{([^}]*)\\}"), "<a href=\"$1\">$2</a>")
    s = s.replace(Regex("\\\\url\\{([^}]*)\\}"), "<a href=\"$1\">$1</a>")

    // Bibliography / citations
    s = s.replace(Regex("\\\\cite\\{([^}]*)\\}"), "[<i>$1</i>]")
    s = s.replace(Regex("\\\\ref\\{([^}]*)\\}"), "($1)")
    s = s.replace(Regex("\\\\label\\{[^}]*\\}"), "")
    s = s.replace(Regex("(?s)\\\\begin\\{thebibliography\\}.*?\\\\end\\{thebibliography\\}")) { m ->
      val bib = m.value
      val items = Regex("\\\\bibitem\\{([^}]*)\\}\\s*([^\\\\]*)").findAll(bib)
      val html = items.joinToString("\n") { "<li id=\"${it.groupValues[1]}\">${it.groupValues[2].trim()}</li>" }
      "<h3>References</h3><ol>$html</ol>"
    }

    // Line breaks
    s = s.replace(Regex("\\\\\\\\"), "<br>")
    s = s.replace(Regex("\\\\newline"), "<br>")
    s = s.replace(Regex("\\\\newpage"), "<hr>")
    s = s.replace(Regex("\\\\par\\b"), "<br><br>")
    s = s.replace(Regex("\\\\hrule"), "<hr>")

    // Strip figure/table wrappers (keep content inside)
    s = s.replace(Regex("\\\\begin\\{(figure|table)\\}(\\[[^]]*])?"), "")
    s = s.replace(Regex("\\\\end\\{(figure|table)\\}"), "")
    s = s.replace(Regex("\\\\centering"), "")

    // Strip remaining unknown environments and commands
    s = s.replace(Regex("\\\\begin\\{[^}]*\\}(\\[[^]]*])?"), "")
    s = s.replace(Regex("\\\\end\\{[^}]*\\}"), "")
    s = s.replace(Regex("\\\\[a-zA-Z]+\\{([^}]*)\\}"), "$1")
    s = s.replace(Regex("\\\\[a-zA-Z]+\\b"), "")

    // Paragraphs
    s = s.replace(Regex("\n\\s*\n"), "</p><p>")
    return "<p>$s</p>"
  }

  private fun transpileTables(input: String): String = Regex("(?s)\\\\begin\\{tabular\\}\\{[^}]*\\}(.*?)\\\\end\\{tabular\\}").replace(input) { m ->
    val body = m.groupValues[1].trim()
    val rows = body.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() && it != "\\hline" }
    val sb = StringBuilder("<table>")
    for ((i, row) in rows.withIndex()) {
      if (row == "\\hline") continue
      val cells = row.replace("\\hline", "").split("&").map { it.trim() }
      val tag = if (i == 0) "th" else "td"
      sb.append("<tr>")
      for (cell in cells) sb.append("<$tag>$cell</$tag>")
      sb.append("</tr>")
    }
    sb.append("</table>")
    sb.toString()
  }
}
