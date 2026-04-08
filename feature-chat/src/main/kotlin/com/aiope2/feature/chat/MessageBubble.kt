package com.aiope2.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.m3.Markdown

@Composable
fun MessageBubble(
  message: ChatMessage,
  isLastStreaming: Boolean = false,
  onEdit: (() -> Unit)? = null,
  onRetry: (() -> Unit)? = null,
  onCompact: (() -> Unit)? = null,
  onFork: (() -> Unit)? = null
) {
  val isUser = message.role == Role.USER
  val ctx = LocalContext.current
  var showMenu by remember { mutableStateOf(false) }

  if (isUser) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = 320.dp)) {
        Column {
          if (message.imageUris.isNotEmpty()) {
            Row(Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              message.imageUris.forEach { uri ->
                val bmp = remember(uri) {
                  try { android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, android.net.Uri.parse(uri)) }
                  catch (_: Exception) { null }
                }
                if (bmp != null) {
                  AndroidView(factory = { c -> android.widget.ImageView(c).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp); clipToOutline = true
                  }}, modifier = Modifier.size(56.dp))
                }
              }
            }
          }
          SelectionContainer {
            Text(message.content, color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
          }
          MessageMenu(message, showMenu, { showMenu = it }, ctx, onEdit, onRetry, onCompact, onFork)
        }
      }
    }
  } else {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 340.dp)) {
        Column(Modifier.padding(bottom = 4.dp).animateContentSize()) {

          if (message.reasoning.isNotEmpty()) {
            message.reasoning.forEachIndexed { idx, block ->
              val isLast = idx == message.reasoning.lastIndex
              val isStreaming = isLast && !message.isReasoningDone
              ReasoningBlock(block, isStreaming)
            }
          }

          if (message.toolCalls.isNotEmpty()) {
            ToolCallsBlock(message.toolCalls, message.toolResults)
          }

          if (message.locationData != null && message.content.isNotBlank()) {
            key(message.locationData) {
              com.aiope2.feature.chat.location.LocationCard(
                latitude = message.locationData.latitude, longitude = message.locationData.longitude,
                altitude = message.locationData.altitude, speed = message.locationData.speed,
                bearing = message.locationData.bearing, accuracy = message.locationData.accuracy
              )
            }
          }

          if (message.content.isNotBlank()) {
            SelectionContainer {
              Markdown(
                content = message.content,
                modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp)
              )
            }
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
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF1A3A1A),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
      ) {
        Text("${calls[i]}", fontSize = 12.sp, color = Color(0xFF88FF88),
          modifier = Modifier.padding(8.dp, 4.dp), fontFamily = FontFamily.Monospace)
      }
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
  val iconTint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                 else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
  Box(Modifier.fillMaxWidth().padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
    IconButton(onClick = { onShowMenu(true) }, modifier = Modifier.size(28.dp)) {
      Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(16.dp), tint = iconTint)
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
