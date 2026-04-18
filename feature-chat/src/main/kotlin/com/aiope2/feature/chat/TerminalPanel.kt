package com.aiope2.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aiope2.core.terminal.ShellDiscovery
import com.aiope2.core.terminal.backend.TerminalSession
import com.aiope2.core.terminal.view.TerminalView
import com.aiope2.core.terminal.view.TerminalViewClient
import java.lang.ref.WeakReference

object TerminalSessionHolder {
  private val sessions = mutableMapOf<String, TerminalSession>()
  var viewRef: WeakReference<TerminalView>? = null

  private val callback = object : TerminalSession.SessionChangedCallback {
    override fun onTextChanged(s: TerminalSession) {
      viewRef?.get()?.let { v -> v.post { v.onScreenUpdated() } }
    }
    override fun onTitleChanged(s: TerminalSession) {}
    override fun onSessionFinished(s: TerminalSession) {
      sessions.entries.removeAll { it.value === s }
    }
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

  fun getOrCreate(shellId: String, shell: ShellDiscovery.Shell): TerminalSession = sessions.getOrPut(shellId) {
    TerminalSession(shell.command, shell.cwd, shell.args, shell.env, callback)
  }

  // Sticky modifier state
  var ctrlDown = false
  var altDown = false
}

@Composable
fun TerminalPanel(keyboardVisible: Boolean, modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val shells = remember { ShellDiscovery.getShells(ctx) }
  val availableShells = shells.filter { it.available }
  var selectedShellId by remember { mutableStateOf(availableShells.firstOrNull()?.id ?: "sh") }

  Column(modifier = modifier.background(androidx.compose.ui.graphics.Color.Black)) {
    // Shell picker
    Row(modifier = Modifier.fillMaxWidth()) {
      availableShells.forEach { shell ->
        TextButton(onClick = { selectedShellId = shell.id }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
          Text(
            shell.name,
            color = if (shell.id == selectedShellId) {
              MaterialTheme.colorScheme.primary
            } else {
              androidx.compose.ui.graphics.Color.Gray
            },
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }

    val shell = shells.firstOrNull { it.id == selectedShellId && it.available }
    if (shell != null) {
      // Terminal view
      TerminalViewComposable(shell = shell, modifier = Modifier.weight(1f).fillMaxWidth())
      // Extra keys only when keyboard is open
      if (keyboardVisible) {
        ExtraKeysBar()
      }
    }
  }
}

@Composable
private fun TerminalViewComposable(shell: ShellDiscovery.Shell, modifier: Modifier = Modifier) {
  val session = remember(shell.id) {
    TerminalSessionHolder.getOrCreate(shell.id, shell)
  }

  var currentTextSize by remember { mutableFloatStateOf(0f) }

  AndroidView(
    factory = { context ->
      val dp = context.resources.displayMetrics.scaledDensity
      val initSize = (13 * dp).toInt()
      TerminalView(context, null).apply {
        setTextSize(initSize)
        currentTextSize = initSize.toFloat()
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        attachSession(session)
        TerminalSessionHolder.viewRef = WeakReference(this)
        setTerminalViewClient(object : TerminalViewClient {
          override fun onScale(scale: Float): Float {
            currentTextSize = (currentTextSize * scale).coerceIn(6 * dp, 36 * dp)
            setTextSize(currentTextSize.toInt())
            return 1f
          }
          override fun onSingleTapUp(e: MotionEvent?) {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
          }
          override fun shouldBackButtonBeMappedToEscape() = false
          override fun copyModeChanged(copyMode: Boolean) {}
          override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: TerminalSession?) = false
          override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
          override fun readControlKey() = TerminalSessionHolder.ctrlDown
          override fun readAltKey() = TerminalSessionHolder.altDown
          override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?): Boolean {
            // After a non-locked modifier fires, reset it
            return false
          }
          override fun onLongPress(event: MotionEvent?) = false
        })
        requestFocus()
      }
    },
    update = { view ->
      TerminalSessionHolder.viewRef = WeakReference(view)
      view.attachSession(session)
      view.post { view.onScreenUpdated() }
    },
    modifier = modifier,
  )
}

// ── Extra Keys ──────────────────────────────────────────────────────────────

private enum class StickyState { IDLE, ARMED, LOCKED }

private data class ExtraKey(val label: String, val isModifier: Boolean = false, val seq: String = "")

private val extraKeys = listOf(
  ExtraKey("ESC", seq = "\u001b"),
  ExtraKey("TAB", seq = "\t"),
  ExtraKey("CTRL", isModifier = true),
  ExtraKey("ALT", isModifier = true),
  ExtraKey("↑", seq = "\u001b[A"),
  ExtraKey("↓", seq = "\u001b[B"),
  ExtraKey("←", seq = "\u001b[D"),
  ExtraKey("→", seq = "\u001b[C"),
  ExtraKey("HOME", seq = "\u001b[H"),
  ExtraKey("END", seq = "\u001b[F"),
)

private val COL_IDLE = Color.parseColor("#0F1729")
private val COL_ARMED = Color.parseColor("#1A3A1A")
private val COL_LOCKED = Color.parseColor("#3A1A1A")

@Composable
private fun ExtraKeysBar() {
  AndroidView(
    factory = { context ->
      val dp = context.resources.displayMetrics.density
      val states = mutableMapOf<String, StickyState>()

      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor("#0F1729"))

        extraKeys.forEach { key ->
          val tv = TextView(context).apply {
            text = key.label
            textSize = 11f
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setBackgroundColor(COL_IDLE)
            layoutParams = LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f).apply {
              marginEnd = (1 * dp).toInt()
            }
          }

          if (key.isModifier) {
            tv.setOnClickListener {
              val cur = states.getOrDefault(key.label, StickyState.IDLE)
              val next = when (cur) {
                StickyState.IDLE -> StickyState.ARMED
                StickyState.ARMED -> StickyState.LOCKED
                StickyState.LOCKED -> StickyState.IDLE
              }
              states[key.label] = next
              tv.setBackgroundColor(
                when (next) {
                  StickyState.IDLE -> COL_IDLE
                  StickyState.ARMED -> COL_ARMED
                  StickyState.LOCKED -> COL_LOCKED
                },
              )
              tv.setTextColor(
                when (next) {
                  StickyState.IDLE -> Color.parseColor("#F8FAFC")
                  StickyState.ARMED -> Color.parseColor("#88FF88")
                  StickyState.LOCKED -> Color.parseColor("#FF8888")
                },
              )
              when (key.label) {
                "CTRL" -> TerminalSessionHolder.ctrlDown = next != StickyState.IDLE
                "ALT" -> TerminalSessionHolder.altDown = next != StickyState.IDLE
              }
            }
          } else {
            tv.setOnClickListener {
              TerminalSessionHolder.viewRef?.get()?.let { view ->
                val session = view.javaClass.getDeclaredField("mTermSession").apply { isAccessible = true }.get(view) as? TerminalSession
                session?.write(key.seq)
                // Reset armed (non-locked) modifiers after keypress
                states.forEach { (name, state) ->
                  if (state == StickyState.ARMED) {
                    states[name] = StickyState.IDLE
                    when (name) {
                      "CTRL" -> TerminalSessionHolder.ctrlDown = false
                      "ALT" -> TerminalSessionHolder.altDown = false
                    }
                  }
                }
                // Refresh modifier button colors
                for (i in 0 until childCount) {
                  val child = getChildAt(i) as? TextView ?: continue
                  val childKey = extraKeys.getOrNull(i) ?: continue
                  if (childKey.isModifier) {
                    val s = states.getOrDefault(childKey.label, StickyState.IDLE)
                    child.setBackgroundColor(
                      when (s) {
                        StickyState.IDLE -> COL_IDLE
                        StickyState.ARMED -> COL_ARMED
                        StickyState.LOCKED -> COL_LOCKED
                      },
                    )
                    child.setTextColor(
                      when (s) {
                        StickyState.IDLE -> Color.parseColor("#F8FAFC")
                        StickyState.ARMED -> Color.parseColor("#88FF88")
                        StickyState.LOCKED -> Color.parseColor("#FF8888")
                      },
                    )
                  }
                }
              }
            }
          }
          addView(tv)
        }
      }
    },
    modifier = Modifier.fillMaxWidth().padding(0.dp),
  )
}
