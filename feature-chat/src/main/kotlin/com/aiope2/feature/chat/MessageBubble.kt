package com.aiope2.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fluid.afm.AFMInitializer
import com.fluid.afm.markdown.widget.PrinterMarkDownTextView
import com.fluid.afm.styles.MarkdownStyles

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
  val cs = MaterialTheme.colorScheme
  val selection = remember { TextSelectionColors(handleColor = cs.primary, backgroundColor = cs.primary.copy(alpha = 0.3f)) }

  CompositionLocalProvider(LocalTextSelectionColors provides selection) {
    if (isUser) UserBubble(message, ctx, showMenu, { showMenu = it }, onEdit, onRetry, onCompact, onFork)
    else AssistantBubble(message, ctx, showMenu, { showMenu = it }, onRetry, onCompact, onFork)
  }
}

// ── User bubble: right-aligned, 75% width, tinted primary bg, borderRadius 16 ──

@Composable
private fun UserBubble(
  message: ChatMessage, ctx: Context,
  showMenu: Boolean, onShowMenu: (Boolean) -> Unit,
  onEdit: (() -> Unit)?, onRetry: (() -> Unit)?,
  onCompact: (() -> Unit)?, onFork: (() -> Unit)?
) {
  val cs = MaterialTheme.colorScheme
  val screenW = LocalConfiguration.current.screenWidthDp.dp
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = cs.primary.copy(alpha = 0.15f),
      modifier = Modifier.widthIn(max = screenW * 0.75f)
    ) {
      Column(Modifier.padding(12.dp)) {
        if (message.imageUris.isNotEmpty()) {
          Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            message.imageUris.forEach { uri ->
              val bmp = remember(uri) {
                try { android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, android.net.Uri.parse(uri)) }
                catch (_: Exception) { null }
              }
              if (bmp != null) {
                AndroidView(factory = { c -> android.widget.ImageView(c).apply {
                  scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                  setImageBitmap(bmp); clipToOutline = true
                  outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 24f) }
                  }
                }}, modifier = Modifier.size(64.dp))
              }
            }
          }
        }
        SelectionContainer {
          Text(message.content, color = cs.onSurface,
            fontSize = 15.5.sp, lineHeight = 23.sp,
            style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
  // User actions row
  Row(Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 2.dp), horizontalArrangement = Arrangement.End) {
    MessageMenu(message, showMenu, onShowMenu, ctx, onEdit, onRetry, onCompact, onFork)
  }
}

// ── Assistant bubble: full-width, no background, action row below ──

@Composable
private fun AssistantBubble(
  message: ChatMessage, ctx: Context,
  showMenu: Boolean, onShowMenu: (Boolean) -> Unit,
  onRetry: (() -> Unit)?, onCompact: (() -> Unit)?, onFork: (() -> Unit)?
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {

    // Reasoning blocks
    if (message.reasoning.isNotEmpty()) {
      message.reasoning.forEachIndexed { idx, block ->
        ReasoningBlock(block, idx == message.reasoning.lastIndex && !message.isReasoningDone)
        Spacer(Modifier.height(8.dp))
      }
    }

    // Tool calls
    if (message.toolCalls.isNotEmpty()) {
      ToolCallsBlock(message.toolCalls, message.toolResults)
      Spacer(Modifier.height(8.dp))
    }

    // Location
    if (message.locationData != null && message.content.isNotBlank()) {
      key(message.locationData) {
        com.aiope2.feature.chat.location.LocationCard(
          latitude = message.locationData.latitude, longitude = message.locationData.longitude,
          altitude = message.locationData.altitude, speed = message.locationData.speed,
          bearing = message.locationData.bearing, accuracy = message.locationData.accuracy
        )
      }
    }

    // Content
    if (message.content.isNotBlank()) {
      val textColor = cs.onSurface.toArgb()
      val content = message.content.trimEnd()
      val primaryArgb = cs.primary.toArgb()
      AndroidView(
        factory = { context ->
          AFMInitializer.init(context, null, null, null)
          val density = context.resources.displayMetrics.density
          // Black backgrounds for high contrast
          val codeBg = 0xFF0A0A0A.toInt()
          val codeHeaderBg = 0xFF111111.toInt()
          val styles = MarkdownStyles.getDefaultStyles()
            .codeStyle(com.fluid.afm.styles.CodeStyle.create()
              .codeFontColor(0xFFE0E0E0.toInt())
              .codeBackgroundColor(codeBg)
              .titleFontColor(cs.primary.copy(alpha = 0.8f).toArgb())
              .borderColor(0xFF1A1A1A.toInt())
              .inlineFontColor(0xFFCE9178.toInt())
              .inlineCodeBackgroundColor(0xFF111111.toInt()))
          val styles2 = styles
          val ts = styles2.tableStyle()
            .bodyFontSize(11f * density)
            .headerFontSize(11f * density)
            .titleFontSize(11f * density)
            .fontColor(cs.onSurface.toArgb())
            .titleFontColor(cs.primary.copy(alpha = 0.8f).toArgb())
            .titleBackgroundColor(codeHeaderBg)
            .headerBackgroundColor(0xFF0E0E0E.toInt())
            .bodyBackgroundColor(codeBg)
            .borderColor(0xFF1A1A1A.toInt())
          styles2.tableStyle(ts)
          object : PrinterMarkDownTextView(context) {
            init { init(styles, null); setTextColor(textColor); textSize = 15.5f; setLineSpacing(0f, 1.5f); setPadding(0, 8, 0, 8); tag = "" }
            override fun onMeasure(wSpec: Int, hSpec: Int) {
              super.onMeasure(wSpec, hSpec)
              layout?.let { l ->
                val h = l.getLineBottom(l.lineCount - 1) + paddingTop + paddingBottom
                if (measuredHeight > h + 20) setMeasuredDimension(measuredWidth, h)
              }
            }
          }
        },
        update = { tv ->
          val prev = tv.tag as? String ?: ""
          if (content != prev) {
            tv.tag = content
            try {
              tv.setMarkdownText(content)
              if (tv.text.isNullOrEmpty() && content.isNotEmpty()) tv.text = content
            } catch (_: Exception) { tv.text = content }
            val txt = tv.text
            if (txt != null) {
              val len = txt.length; var i = len
              while (i > 0 && (txt[i - 1] == '\n' || txt[i - 1] == '\u00A0' || txt[i - 1] == ' ')) i--
              if (i < len) tv.text = txt.subSequence(0, i)
            }
          }
        },
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
      )
    }

    // Action row: copy + retry + menu
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (message.content.isNotBlank()) {
        ActionIcon(Icons.Default.ContentCopy, "Copy") {
          val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("message", message.content))
          Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
      }
      if (onRetry != null) ActionIcon(Icons.Default.Refresh, "Retry") { onRetry() }
      Spacer(Modifier.weight(1f))
      MessageMenu(message, showMenu, onShowMenu, ctx, null, onRetry, onCompact, onFork)
    }
  }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
  IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
    Icon(icon, desc, modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
  }
}

// ── Reasoning block: primaryContainer bg, shimmer, collapsible ──

@Composable
private fun ReasoningBlock(reasoning: String, isStreaming: Boolean) {
  val cs = MaterialTheme.colorScheme
  // While streaming: partially collapsed (3 lines visible)
  // When complete: fully collapsed (0 lines)
  // Toggleable at any time
  var userToggled by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }

  // Auto-manage state based on streaming
  LaunchedEffect(isStreaming) {
    if (!isStreaming && !userToggled) expanded = false // collapse on complete
  }

  val showContent = if (userToggled) expanded else if (isStreaming) true else false
  val isPartial = isStreaming && !expanded && !userToggled // show 3 lines during streaming

  Surface(
    modifier = Modifier.fillMaxWidth().clickable {
      userToggled = true
      expanded = !expanded
    },
    shape = RoundedCornerShape(16.dp),
    color = Color(0xFF0A0A0A)
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (showContent) "▾" else "▸", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        if (isStreaming) ShimmerText("Thinking…", cs) else Text("Thinking", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700, color = cs.onSurfaceVariant)
        if (isStreaming) { Spacer(Modifier.width(6.dp)); LoadingDots() }
      }
      AnimatedVisibility(visible = showContent || isPartial) {
        Box {
          SelectionContainer {
            val lines = reasoning.lines()
            val displayText = if (isPartial && lines.size > 3) lines.takeLast(3).joinToString("\n") else reasoning
            Text(displayText, fontSize = 12.5.sp, lineHeight = 16.5.sp,
              color = cs.onSurfaceVariant.copy(alpha = 0.7f),
              maxLines = if (isPartial) 3 else Int.MAX_VALUE,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 6.dp))
          }
          // Fade mask at top when partially collapsed
          if (isPartial) {
            Box(Modifier.matchParentSize().align(Alignment.TopCenter)
              .drawWithContent {
                drawContent()
                drawRect(
                  brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0A0A), Color.Transparent),
                    startY = 0f, endY = size.height * 0.5f
                  )
                )
              })
          }
        }
      }
    }
  }
}

@Composable
private fun ShimmerText(text: String, cs: ColorScheme) {
  val transition = rememberInfiniteTransition(label = "shimmer")
  val offset by transition.animateFloat(
    initialValue = -1f, targetValue = 2f,
    animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmer"
  )
  Text(text, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
    color = cs.onSurfaceVariant,
    modifier = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
      .drawWithContent {
        drawContent()
        drawRect(
          brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
            startX = size.width * offset, endX = size.width * (offset + 0.4f)
          ), blendMode = BlendMode.SrcAtop
        )
      }
  )
}

@Composable
private fun LoadingDots() {
  val transition = rememberInfiniteTransition(label = "dots")
  val phase by transition.animateFloat(
    initialValue = 0f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "dots"
  )
  val cs = MaterialTheme.colorScheme
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    repeat(3) { i ->
      val wave = ((kotlin.math.sin((phase + i * 0.22f) * 2 * Math.PI) + 1) / 2).toFloat()
      Box(Modifier.size(7.dp)
        .graphicsLayer(scaleX = 0.85f + 0.15f * wave, scaleY = 0.85f + 0.15f * wave, alpha = 0.45f + 0.45f * wave)
        .drawWithContent { drawCircle(cs.primary) })
    }
  }
}

// ── Tool calls: primaryContainer bg, borderRadius 16 ──

@Composable
private fun ToolCallsBlock(calls: List<String>, results: List<String>) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (i in calls.indices) {
      Surface(shape = RoundedCornerShape(16.dp), color = cs.primaryContainer.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
          Text(calls[i], fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
            color = cs.onSurface, fontFamily = FontFamily.Monospace)
          if (i < results.size) {
            var expanded by remember { mutableStateOf(false) }
            val result = results[i]
            val preview = if (result.length > 150 && !expanded) result.take(150) + "…" else result
            Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(10.dp),
              color = if (MaterialTheme.colorScheme.surface == Color(0xFF121213)) Color.White.copy(alpha = 0.1f) else Color(0xFFF7F7F9),
              modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
              SelectionContainer {
                Text(preview, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant,
                  modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace)
              }
            }
          }
        }
      }
    }
  }
}

// ── Menu ──

@Composable
private fun MessageMenu(
  message: ChatMessage, showMenu: Boolean, onShowMenu: (Boolean) -> Unit,
  ctx: Context, onEdit: (() -> Unit)?, onRetry: (() -> Unit)?,
  onCompact: (() -> Unit)?, onFork: (() -> Unit)?
) {
  val isUser = message.role == Role.USER
  Box(contentAlignment = Alignment.CenterEnd) {
    IconButton(onClick = { onShowMenu(true) }, modifier = Modifier.size(28.dp)) {
      Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
    DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenu(false) }) {
      if (isUser) DropdownMenuItem(text = { Text("Copy") }, onClick = {
        onShowMenu(false)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", message.content))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
      })
      if (isUser && onEdit != null) DropdownMenuItem(text = { Text("Edit & Resend") }, onClick = { onShowMenu(false); onEdit() })
      if (!isUser && onRetry != null) DropdownMenuItem(text = { Text("Retry") }, onClick = { onShowMenu(false); onRetry() })
      if (onCompact != null) DropdownMenuItem(text = { Text("Compact") }, onClick = { onShowMenu(false); onCompact() })
      if (onFork != null) DropdownMenuItem(text = { Text("Fork") }, onClick = { onShowMenu(false); onFork() })
      if (message.content.contains("\\documentclass") || message.content.contains("\\begin{document}")) {
        DropdownMenuItem(text = { Text("Export PDF") }, onClick = {
          onShowMenu(false); LatexPdfExporter.export(ctx, message.content)
        })
      }
    }
  }
}

// ── Utility ──

private fun blendColor(fg: Int, bg: Int, alpha: Float): Int {
  val a = alpha
  val r = ((fg shr 16 and 0xFF) * a + (bg shr 16 and 0xFF) * (1 - a)).toInt()
  val g = ((fg shr 8 and 0xFF) * a + (bg shr 8 and 0xFF) * (1 - a)).toInt()
  val b = ((fg and 0xFF) * a + (bg and 0xFF) * (1 - a)).toInt()
  return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
