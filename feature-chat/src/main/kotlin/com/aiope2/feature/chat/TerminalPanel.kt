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

/** Holds terminal sessions so they survive recomposition/toggle */
object TerminalSessionHolder {
  private val sessions = mutableMapOf<String, TerminalSession>()

  fun getOrCreate(
    shellId: String,
    shell: ShellDiscovery.Shell,
    callback: TerminalSession.SessionChangedCallback
  ): TerminalSession {
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
  // Hold a ref to the view so the callback can call onScreenUpdated
  var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }

  val session = remember(shell.id) {
    TerminalSessionHolder.getOrCreate(shell.id, shell,
      object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(s: TerminalSession) {
          terminalViewRef?.onScreenUpdated()
        }
        override fun onTitleChanged(s: TerminalSession) {}
        override fun onSessionFinished(s: TerminalSession) {}
        override fun onClipboardText(s: TerminalSession, text: String) {
          terminalViewRef?.let { view ->
            val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
          }
        }
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) {}
      }
    )
  }

  AndroidView(
    factory = { context ->
      TerminalView(context, null).apply {
        terminalViewRef = this
        setTextSize(14)
        isFocusable = true
        isFocusableInTouchMode = true
        setKeepScreenOn(true)
        attachSession(session)
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
      terminalViewRef = view
      // attachSession returns false if already attached
      view.attachSession(session)
    },
    modifier = modifier
  )
}
