package com.aiope2.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
fun MessageBubble(
  message: ChatMessage,
  onEdit: (() -> Unit)? = null,
  onRetry: (() -> Unit)? = null,
  onCompact: (() -> Unit)? = null,
  onFork: (() -> Unit)? = null
) {
  val isUser = message.role == Role.USER
  val ctx = LocalContext.current
  val markwon = remember { Markwon.create(ctx) }
  var showMenu by remember { mutableStateOf(false) }

  if (isUser) {
    // User bubble — right aligned, simple
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = 320.dp)) {
        Column {
          SelectionContainer {
            Text(message.content, color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
          }
          MessageMenu(message, showMenu, { showMenu = it }, ctx, onEdit, onRetry, onCompact, onFork)
        }
      }
    }
  } else {
    // Assistant bubble — left aligned, with reasoning + tool calls + content
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 340.dp)) {
        Column(Modifier.padding(bottom = 4.dp)) {

          // Reasoning blocks (each collapsible)
          if (message.reasoning.isNotEmpty()) {
            message.reasoning.forEachIndexed { idx, block ->
              val isLast = idx == message.reasoning.lastIndex
              val isStreaming = isLast && !message.isReasoningDone
              ReasoningBlock(block, isStreaming)
            }
          }

          // Tool calls + results (interleaved)
          if (message.toolCalls.isNotEmpty()) {
            ToolCallsBlock(message.toolCalls, message.toolResults)
          }

          // Main content (markdown)
          if (message.content.isNotBlank()) {
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            AndroidView(
              factory = { context ->
                TextView(context).apply {
                  setTextColor(textColor); textSize = 14f
                  setTextIsSelectable(true); setPadding(32, 16, 32, 8)
                }
              },
              update = { tv -> markwon.setMarkdown(tv, message.content) },
              modifier = Modifier.fillMaxWidth()
            )
          }

          MessageMenu(message, showMenu, { showMenu = it }, ctx, onEdit, onRetry, onCompact, onFork)
        }
      }
    }
  }
}

@Composable
private fun ReasoningBlock(reasoning: String, isStreaming: Boolean) {
  var expanded by remember { mutableStateOf(isStreaming) }

  // Auto-collapse when streaming finishes
  LaunchedEffect(isStreaming) { if (!isStreaming) expanded = false }

  Surface(
    modifier = Modifier.fillMaxWidth().padding(8.dp, 8.dp, 8.dp, 0.dp).clickable { expanded = !expanded },
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 2.dp
  ) {
    Column(Modifier.padding(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (expanded) "▾ " else "▸ ", style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Thinking", style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isStreaming) LoadingDots()
      }
      AnimatedVisibility(visible = expanded) {
        Text(reasoning, fontSize = 12.sp, lineHeight = 16.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
          modifier = Modifier.padding(top = 6.dp))
      }
    }
  }
}

@Composable
private fun LoadingDots() {
  val transition = rememberInfiniteTransition(label = "dots")
  val dot by transition.animateFloat(
    initialValue = 0f, targetValue = 4f,
    animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
    label = "dots"
  )
  Text(".".repeat(dot.toInt().coerceIn(0, 3)), style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ToolCallsBlock(calls: List<String>, results: List<String>) {
  Column(Modifier.fillMaxWidth().padding(8.dp, 4.dp, 8.dp, 0.dp)) {
    for (i in calls.indices) {
      // Tool call chip
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF1A3A1A),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
      ) {
        Text("${calls[i]}", fontSize = 12.sp, color = Color(0xFF88FF88),
          modifier = Modifier.padding(8.dp, 4.dp), fontFamily = FontFamily.Monospace)
      }
      // Tool result (if available)
      if (i < results.size) {
        var resultExpanded by remember { mutableStateOf(false) }
        val result = results[i]
        val preview = if (result.length > 120 && !resultExpanded) result.take(120) + "..." else result
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color(0xFF1C1B1F),
          modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 1.dp, bottom = 4.dp)
            .clickable { resultExpanded = !resultExpanded }
        ) {
          Text(preview, fontSize = 11.sp, color = Color(0xFFB0B0B0),
            modifier = Modifier.padding(8.dp, 4.dp), fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
        }
      }
    }
  }
}

@Composable
private fun MessageMenu(
  message: ChatMessage, showMenu: Boolean, onShowMenu: (Boolean) -> Unit,
  ctx: Context, onEdit: (() -> Unit)?, onRetry: (() -> Unit)?,
  onCompact: (() -> Unit)?, onFork: (() -> Unit)?
) {
  val isUser = message.role == Role.USER
  Box(Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 2.dp), contentAlignment = Alignment.CenterEnd) {
    IconButton(onClick = { onShowMenu(true) }, modifier = Modifier.size(24.dp)) {
      Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
    DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenu(false) }) {
      DropdownMenuItem(text = { Text("Copy") }, onClick = {
        onShowMenu(false)
        val full = buildString {
          if (message.reasoning.isNotEmpty()) append("[Thinking]\n${message.reasoning.joinToString("\n\n")}\n\n")
          message.toolCalls.forEachIndexed { i, c ->
            append("$c\n")
            if (i < message.toolResults.size) append("${message.toolResults[i]}\n\n")
          }
          append(message.content)
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", full))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
      })
      if (isUser && onEdit != null) DropdownMenuItem(text = { Text("Edit & Resend") }, onClick = { onShowMenu(false); onEdit() })
      if (!isUser && onRetry != null) DropdownMenuItem(text = { Text("Retry") }, onClick = { onShowMenu(false); onRetry() })
      if (onCompact != null) DropdownMenuItem(text = { Text("Compact") }, onClick = { onShowMenu(false); onCompact() })
      if (onFork != null) DropdownMenuItem(text = { Text("Fork") }, onClick = { onShowMenu(false); onFork() })
    }
  }
}
