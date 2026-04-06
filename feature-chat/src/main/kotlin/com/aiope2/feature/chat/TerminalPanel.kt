package com.aiope2.feature.chat

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
    TerminalSession(
      shell.command, shell.cwd, shell.args, shell.env,
      object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(s: TerminalSession) {}
        override fun onTitleChanged(s: TerminalSession) {}
        override fun onSessionFinished(s: TerminalSession) {}
        override fun onClipboardText(s: TerminalSession, text: String) {}
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) {}
      }
    )
  }

  DisposableEffect(session) {
    onDispose { session.finishIfRunning() }
  }

  AndroidView(
    factory = { context ->
      TerminalView(context, null).apply {
        setTextSize(14)
        attachSession(session)
        setTerminalViewClient(object : TerminalViewClient {
          override fun onScale(scale: Float) = scale
          override fun onSingleTapUp(e: MotionEvent?) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this@apply, 0)
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
    modifier = modifier
  )
}
