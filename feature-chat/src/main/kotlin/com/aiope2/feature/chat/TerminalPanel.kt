package com.aiope2.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aiope2.core.terminal.backend.TerminalSession
import com.aiope2.core.terminal.view.TerminalView
import com.aiope2.core.terminal.view.TerminalViewClient
import com.aiope2.core.terminal.ShellDiscovery
import java.lang.ref.WeakReference

object TerminalSessionHolder {
  private val sessions = mutableMapOf<String, TerminalSession>()
  /** Weak ref so dead views get GC'd after rotation */
  var viewRef: WeakReference<TerminalView>? = null

  private val callback = object : TerminalSession.SessionChangedCallback {
    override fun onTextChanged(s: TerminalSession) {
      viewRef?.get()?.let { v -> v.post { v.onScreenUpdated() } }
    }
    override fun onTitleChanged(s: TerminalSession) {}
    override fun onSessionFinished(s: TerminalSession) {}
    override fun onClipboardText(s: TerminalSession, text: String) {
      viewRef?.get()?.let { v ->
        v.post {
          val cm = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
      }
    }
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) {}
  }

  fun getOrCreate(shellId: String, shell: ShellDiscovery.Shell): TerminalSession {
    return sessions.getOrPut(shellId) {
      TerminalSession(shell.command, shell.cwd, shell.args, shell.env, callback)
    }
  }
}

@Composable
fun TerminalPanel(modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val shells = remember { ShellDiscovery.getShells(ctx) }
  val availableShells = shells.filter { it.available }
  var selectedShellId by remember { mutableStateOf(availableShells.firstOrNull()?.id ?: "sh") }

  Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
      availableShells.forEach { shell ->
        TextButton(onClick = { selectedShellId = shell.id }) {
          Text(
            shell.name,
            color = if (shell.id == selectedShellId) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall
          )
        }
      }
    }

    val shell = shells.firstOrNull { it.id == selectedShellId && it.available }
    if (shell != null) {
      TerminalViewComposable(shell = shell, modifier = Modifier.fillMaxSize())
    }
  }
}

@Composable
private fun TerminalViewComposable(shell: ShellDiscovery.Shell, modifier: Modifier = Modifier) {
  val session = remember(shell.id) {
    TerminalSessionHolder.getOrCreate(shell.id, shell)
  }

  AndroidView(
    factory = { context ->
      TerminalView(context, null).apply {
        setTextSize(14)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        attachSession(session)
        TerminalSessionHolder.viewRef = WeakReference(this)
        setTerminalViewClient(object : TerminalViewClient {
          override fun onScale(scale: Float) = scale
          override fun onSingleTapUp(e: MotionEvent?) {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
          }
          override fun shouldBackButtonBeMappedToEscape() = false
          override fun copyModeChanged(copyMode: Boolean) {}
          override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: TerminalSession?) = false
          override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
          override fun readControlKey() = false
          override fun readAltKey() = false
          override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?) = false
          override fun onLongPress(event: MotionEvent?) = false
        })
        requestFocus()
      }
    },
    update = { view ->
      TerminalSessionHolder.viewRef = WeakReference(view)
      view.attachSession(session)
      // Force a redraw after reattach (rotation/resume)
      view.post { view.onScreenUpdated() }
    },
    modifier = modifier
  )
}
