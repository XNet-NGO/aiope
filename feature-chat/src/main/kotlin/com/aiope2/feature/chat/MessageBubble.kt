package com.aiope2.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fluid.compose.MarkdownTheme
import com.fluid.compose.UniversalMarkdown

@Composable
fun MessageBubble(
  message: ChatMessage,
  isLastStreaming: Boolean = false,
  onEdit: (() -> Unit)? = null,
  onRetry: (() -> Unit)? = null,
  onCompact: (() -> Unit)? = null,
  onFork: (() -> Unit)? = null,
  onTranslate: ((String) -> Unit)? = null,
  onUiCallback: ((String, Map<String, String>) -> Unit)? = null,
  onRunCode: ((code: String, language: String) -> Unit)? = null,
) {
  val isUser = message.role == Role.USER
  val ctx = LocalContext.current
  var showMenu by remember { mutableStateOf(false) }
  val cs = MaterialTheme.colorScheme
  val selection = remember { TextSelectionColors(handleColor = cs.primary, backgroundColor = cs.primary.copy(alpha = 0.3f)) }

  CompositionLocalProvider(LocalTextSelectionColors provides selection) {
    if (isUser) {
      UserBubble(message, ctx, showMenu, { showMenu = it }, onEdit, onRetry, onCompact, onFork)
    } else {
      AssistantBubble(message, ctx, isLastStreaming, onRetry, onCompact, onFork, onTranslate, onUiCallback, onRunCode)
    }
  }
}

// ── User bubble: right-aligned, 75% width, tinted primary bg, borderRadius 16 ──

@Composable
private fun UserBubble(
  message: ChatMessage,
  ctx: Context,
  showMenu: Boolean,
  onShowMenu: (Boolean) -> Unit,
  onEdit: (() -> Unit)?,
  onRetry: (() -> Unit)?,
  onCompact: (() -> Unit)?,
  onFork: (() -> Unit)?,
) {
  val cs = MaterialTheme.colorScheme
  val screenW = LocalConfiguration.current.screenWidthDp.dp
  Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color(0xFF00E5FF).copy(alpha = 0.12f),
      modifier = Modifier.widthIn(max = screenW * 0.75f),
    ) {
      Column(Modifier.padding(12.dp)) {
        if (message.imageUris.isNotEmpty()) {
          Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            message.imageUris.forEach { uri ->
              val bmp = remember(uri) {
                try {
                  if (uri.startsWith("file://")) {
                    android.graphics.BitmapFactory.decodeFile(uri.removePrefix("file://"))
                  } else {
                    android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, android.net.Uri.parse(uri))
                  }
                } catch (_: Exception) {
                  null
                }
              }
              val isGenerated = uri.startsWith("file://") && uri.contains("/generated/")
              val imgSize = if (isGenerated) 256.dp else 64.dp
              if (bmp != null) {
                AndroidView(factory = { c ->
                  android.widget.ImageView(c).apply {
                    scaleType = if (isGenerated) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                      override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, 24f)
                      }
                    }
                    setOnLongClickListener {
                      saveImageToGallery(context, bmp)
                      true
                    }
                  }
                }, modifier = Modifier.size(imgSize))
              }
            }
          }
        }
        SelectionContainer {
          Text(
            message.content,
            color = cs.onSurface,
            fontSize = 15.5.sp,
            lineHeight = 23.sp,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
  }
  // User actions row
  Row(Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.End) {
    MessageMenu(message, showMenu, onShowMenu, ctx, onEdit, onRetry, onCompact, onFork)
  }
}

// ── Assistant bubble: full-width, no background, action row below ──

@Composable
private fun AssistantBubble(
  message: ChatMessage,
  ctx: Context,
  isStreaming: Boolean = false,
  onRetry: (() -> Unit)?,
  onCompact: (() -> Unit)?,
  onFork: (() -> Unit)?,
  onTranslate: ((String) -> Unit)? = null,
  onUiCallback: ((String, Map<String, String>) -> Unit)? = null,
  onRunCode: ((code: String, language: String) -> Unit)? = null,
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
    // Reasoning blocks
    if (message.reasoning.isNotEmpty()) {
      ReasoningTabStrip(message.reasoning, message.isReasoningDone)
      Spacer(Modifier.height(8.dp))
    }

    // Tool calls
    if (message.toolCalls.isNotEmpty()) {
      ToolTabStrip(message.toolCalls, message.toolResults, message.toolErrors)
      Spacer(Modifier.height(8.dp))
    }

    // Location
    if (message.locationData != null && message.content.isNotBlank()) {
      key(message.locationData) {
        com.aiope2.feature.chat.location.LocationCard(
          latitude = message.locationData.latitude,
          longitude = message.locationData.longitude,
          altitude = message.locationData.altitude,
          speed = message.locationData.speed,
          bearing = message.locationData.bearing,
          accuracy = message.locationData.accuracy,
        )
      }
    }

    // Content (skip if it's just a generated image file path)
    if (message.content.isNotBlank() && !(message.content.startsWith("file://") && message.imageUris.isNotEmpty())) {
      val content = message.content.trimEnd()
      val mdTheme = rememberMarkdownTheme(cs)
      // Detect aiope-ui blocks (complete = has closing ```)
      val hasUiBlock = content.contains("```aiope-ui")
      val uiBlockComplete = hasUiBlock && Regex("""```aiope-ui\s*\n[\s\S]*?```""").containsMatchIn(content)
      val uiBlockStreaming = hasUiBlock && !uiBlockComplete

      // Show shimmer while aiope-ui block is streaming
      if (uiBlockStreaming) {
        Surface(
          modifier = Modifier.fillMaxWidth().clickable {},
          shape = RoundedCornerShape(16.dp),
          color = Color(0xFF111111),
        ) {
          Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShimmerText("Building UI…", cs)
            Spacer(Modifier.width(6.dp))
            LoadingDots()
          }
        }
        // Render any text before the aiope-ui block
        val beforeBlock = content.substringBefore("```aiope-ui").trim()
        if (beforeBlock.isNotBlank()) {
          UniversalMarkdown(content = beforeBlock, theme = mdTheme, animateStreaming = isStreaming, modifier = Modifier.fillMaxWidth())
        }
      } else {
        // Split into markdown segments and aiope-ui blocks
        val uiBlockPattern = remember { Regex("""```aiope-ui\s*\n([\s\S]*?)```""") }
        val segments = remember(content) {
          val result = mutableListOf<Pair<String, String?>>()
          var lastEnd = 0
          uiBlockPattern.findAll(content).forEach { match ->
            val before = content.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) result.add(before to null)
            result.add("" to match.groupValues[1].trim())
            lastEnd = match.range.last + 1
          }
          val after = content.substring(lastEnd).trim()
          if (after.isNotEmpty()) result.add(after to null)
          if (result.isEmpty()) result.add(content to null)
          result
        }
        segments.forEach { (text, uiJson) ->
          if (uiJson != null) {
            val node = remember(uiJson) { com.aiope2.feature.chat.dynamicui.AiopeUiParser.parse(uiJson) }
            if (node != null) {
              SelectionContainer {
                com.aiope2.feature.chat.dynamicui.AiopeUiRenderer(
                  node = node,
                  isInteractive = !isStreaming,
                  onCallback = { event, data -> onUiCallback?.invoke(event, data) },
                  modifier = Modifier.padding(vertical = 4.dp),
                )
              }
            }
          } else if (text.isNotBlank()) {
            UniversalMarkdown(
              content = text,
              theme = mdTheme,
              animateStreaming = true,
              modifier = Modifier.fillMaxWidth(),
              onExportPdf = { latex -> LatexPdfExporter.export(ctx, latex) },
              onRunCode = onRunCode,
              onImageContent = { url, alt ->
                val imgCtx = LocalContext.current
                coil.compose.AsyncImage(
                  model = url,
                  contentDescription = alt,
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = {}, onLongClick = {
                      kotlin.concurrent.thread {
                        try {
                          val bmp = if (url.startsWith("file://")) {
                            android.graphics.BitmapFactory.decodeFile(url.removePrefix("file://"))
                          } else {
                            java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                          }
                          if (bmp != null) saveImageToGallery(imgCtx, bmp)
                        } catch (_: Exception) {}
                      }
                    }),
                  contentScale = ContentScale.FillWidth,
                )
              },
            )
          }
        }
      } // else (not streaming)
    }

    // Action row: copy + retry + menu
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (message.content.isNotBlank()) {
        ActionIcon(Icons.Default.ContentCopy, "Copy") {
          val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("message", message.content))
          Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
        ActionIcon(Icons.Default.Share, "Share") {
          val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, message.content)
          }
          ctx.startActivity(android.content.Intent.createChooser(intent, "Share"))
        }
        var speaking by remember { mutableStateOf(false) }
        val tts = remember {
          var engine: android.speech.tts.TextToSpeech? = null
          engine = android.speech.tts.TextToSpeech(ctx) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
              engine?.language = java.util.Locale.getDefault()
              engine?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                  speaking = false
                }

                @Deprecated("")
                override fun onError(id: String?) {
                  speaking = false
                }
              })
            }
          }
          engine
        }
        ActionIcon(if (speaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (speaking) "Stop" else "Speak") {
          if (speaking) {
            tts?.stop()
            speaking = false
          } else {
            tts?.speak(message.content, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, message.id)
            speaking = true
          }
        }
      }
      if (onRetry != null) ActionIcon(Icons.Default.Refresh, "Retry") { onRetry() }
      if (onCompact != null) ActionIcon(Icons.Default.Compress, "Compact") { onCompact() }
      if (onFork != null) ActionIcon(Icons.Default.ForkRight, "Fork") { onFork() }
      if (onTranslate != null && message.content.isNotBlank()) {
        var showLangPicker by remember { mutableStateOf(false) }
        ActionIcon(Icons.Default.Translate, "Translate") { showLangPicker = true }
        if (showLangPicker) {
          DropdownMenu(expanded = showLangPicker, onDismissRequest = { showLangPicker = false }) {
            listOf(
              "English" to "English", "Spanish" to "Spanish", "French" to "French", "German" to "German",
              "Chinese" to "Chinese", "Japanese" to "Japanese", "Korean" to "Korean", "Portuguese" to "Portuguese",
              "Russian" to "Russian", "Arabic" to "Arabic", "Hindi" to "Hindi", "Italian" to "Italian",
            ).forEach { (label, lang) ->
              DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = {
                onTranslate(lang)
                showLangPicker = false
              })
            }
          }
        }
      }
    }
    // Inline translation
    if (message.translation != null) {
      Spacer(Modifier.height(6.dp))
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
      ) {
        Column(Modifier.padding(8.dp)) {
          Text(
            "Translation",
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
          )
          Spacer(Modifier.height(2.dp))
          SelectionContainer { Text(message.translation, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface) }
        }
      }
    }
  }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
  IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
    Icon(
      icon,
      desc,
      modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
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

  val showContent = if (userToggled) {
    expanded
  } else if (isStreaming) {
    true
  } else {
    false
  }
  val isPartial = isStreaming && !expanded && !userToggled // show 3 lines during streaming

  Surface(
    modifier = Modifier.fillMaxWidth().clickable {
      userToggled = true
      expanded = !expanded
    },
    shape = RoundedCornerShape(16.dp),
    color = Color(0xFF111111),
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (showContent) "▾" else "▸", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        if (isStreaming) ShimmerText("Thinking…", cs) else Text("Thinking", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700, color = cs.onSurfaceVariant)
        if (isStreaming) {
          Spacer(Modifier.width(6.dp))
          LoadingDots()
        }
      }
      AnimatedVisibility(visible = showContent || isPartial) {
        Box {
          val lines = reasoning.lines()
          val displayText = if (isPartial && lines.size > 3) lines.takeLast(3).joinToString("\n") else reasoning
          val reasoningTheme = rememberMarkdownTheme(cs).copy(
            textColor = cs.onSurfaceVariant.copy(alpha = 0.7f),
            headingColor = cs.onSurfaceVariant.copy(alpha = 0.7f),
          )
          UniversalMarkdown(
            content = displayText,
            theme = reasoningTheme,
            modifier = Modifier.padding(top = 6.dp),
          )
          // Fade mask at top when partially collapsed
          if (isPartial) {
            Box(
              Modifier.matchParentSize().align(Alignment.TopCenter)
                .drawWithContent {
                  drawContent()
                  drawRect(
                    brush = Brush.verticalGradient(
                      colors = listOf(Color(0xFF111111), Color.Transparent),
                      startY = 0f,
                      endY = size.height * 0.5f,
                    ),
                  )
                },
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun ShimmerText(text: String, cs: ColorScheme) {
  val transition = rememberInfiniteTransition(label = "shimmer")
  val offset by transition.animateFloat(
    initialValue = -1f,
    targetValue = 2f,
    animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
    label = "shimmer",
  )
  Text(
    text,
    fontSize = 13.sp,
    fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
    color = cs.onSurfaceVariant,
    modifier = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
      .drawWithContent {
        drawContent()
        drawRect(
          brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
            startX = size.width * offset,
            endX = size.width * (offset + 0.4f),
          ),
          blendMode = BlendMode.SrcAtop,
        )
      },
  )
}

@Composable
private fun LoadingDots() {
  val transition = rememberInfiniteTransition(label = "dots")
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
    label = "dots",
  )
  val cs = MaterialTheme.colorScheme
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    repeat(3) { i ->
      val wave = ((kotlin.math.sin((phase + i * 0.22f) * 2 * Math.PI) + 1) / 2).toFloat()
      Box(
        Modifier.size(7.dp)
          .graphicsLayer(scaleX = 0.85f + 0.15f * wave, scaleY = 0.85f + 0.15f * wave, alpha = 0.45f + 0.45f * wave)
          .drawWithContent { drawCircle(cs.primary) },
      )
    }
  }
}

// ── Tool calls: primaryContainer bg, borderRadius 16 ──

@Composable
private fun ToolCallsBlock(calls: List<String>, results: List<String>) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (i in calls.indices) {
      var expanded by remember { mutableStateOf(false) }
      val isDone = i < results.size
      val toolName = calls[i].substringBefore("(").substringBefore(" ").trim()
      Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF111111),
      ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            if (!isDone) {
              ShimmerText("$toolName…", cs)
            } else {
              Text(toolName, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700, color = cs.onSurfaceVariant)
            }
            if (!isDone) {
              Spacer(Modifier.width(6.dp))
              LoadingDots()
            }
          }
          AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp)) {
              Text(calls[i], fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
              if (isDone) {
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                  SelectionContainer {
                    Text(
                      results[i],
                      fontSize = 12.sp,
                      lineHeight = 16.sp,
                      color = cs.onSurfaceVariant,
                      modifier = Modifier.padding(10.dp),
                      fontFamily = FontFamily.Monospace,
                    )
                  }
                }
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
  message: ChatMessage,
  showMenu: Boolean,
  onShowMenu: (Boolean) -> Unit,
  ctx: Context,
  onEdit: (() -> Unit)?,
  onRetry: (() -> Unit)?,
  onCompact: (() -> Unit)?,
  onFork: (() -> Unit)?,
) {
  val isUser = message.role == Role.USER
  Box(contentAlignment = Alignment.CenterEnd) {
    IconButton(onClick = { onShowMenu(true) }, modifier = Modifier.size(28.dp)) {
      Icon(
        Icons.Default.MoreVert,
        "More",
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
      )
    }
    DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenu(false) }) {
      if (isUser) {
        DropdownMenuItem(text = { Text("Copy") }, onClick = {
          onShowMenu(false)
          val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("message", message.content))
          Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        })
      }
      if (isUser && onEdit != null) {
        DropdownMenuItem(text = { Text("Edit & Resend") }, onClick = {
          onShowMenu(false)
          onEdit()
        })
      }
      if (!isUser && onRetry != null) {
        DropdownMenuItem(text = { Text("Retry") }, onClick = {
          onShowMenu(false)
          onRetry()
        })
      }
      if (onCompact != null) {
        DropdownMenuItem(text = { Text("Compact") }, onClick = {
          onShowMenu(false)
          onCompact()
        })
      }
      if (onFork != null) {
        DropdownMenuItem(text = { Text("Fork") }, onClick = {
          onShowMenu(false)
          onFork()
        })
      }
      if (message.content.contains("\\documentclass") || message.content.contains("\\begin{document}")) {
        DropdownMenuItem(text = { Text("Export PDF") }, onClick = {
          onShowMenu(false)
          LatexPdfExporter.export(ctx, message.content)
        })
      }
    }
  }
}

// ── MarkdownTheme from MaterialTheme ──

@Composable
private fun rememberMarkdownTheme(cs: ColorScheme): MarkdownTheme = remember(cs) {
  MarkdownTheme(
    textColor = cs.onSurface,
    headingColor = cs.onSurface,
    linkColor = Color(0xFF00E5FF),
    listBulletColor = Color(0xFF9E9E9E),
    codeTextColor = Color(0xFFE0E0E0),
    codeBgColor = Color(0xFF111111),
    codeBorderColor = Color(0xFF2A2A2A),
    codeLabelColor = Color(0xFF00E5FF).copy(alpha = 0.8f),
    inlineCodeTextColor = Color(0xFFFFB300),
    inlineCodeBgColor = Color(0xFF1A1A1A),
    blockQuoteBorderColor = Color(0xFF2A2A2A),
    blockQuoteTextColor = Color(0xFF9E9E9E),
    tableHeaderBgColor = Color(0xFF1A1A1A),
    tableBodyBgColor = Color(0xFF111111),
    tableBorderColor = Color(0xFF333333),
    tableHeaderTextColor = cs.onSurface,
    tableBodyTextColor = cs.onSurface,
    hrColor = Color(0xFF2A2A2A),
    checkboxColor = Color(0xFF00E5FF),
  )
}

// ── Utility ──

private fun blendColor(fg: Int, bg: Int, alpha: Float): Int {
  val a = alpha
  val r = ((fg shr 16 and 0xFF) * a + (bg shr 16 and 0xFF) * (1 - a)).toInt()
  val g = ((fg shr 8 and 0xFF) * a + (bg shr 8 and 0xFF) * (1 - a)).toInt()
  val b = ((fg and 0xFF) * a + (bg and 0xFF) * (1 - a)).toInt()
  return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun saveImageToGallery(context: Context, bitmap: android.graphics.Bitmap) {
  try {
    val values = android.content.ContentValues().apply {
      put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "AIOPE2_${System.currentTimeMillis()}.png")
      put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
      put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIOPE2")
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os) } }
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show() }
  } catch (e: Exception) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
  }
}
