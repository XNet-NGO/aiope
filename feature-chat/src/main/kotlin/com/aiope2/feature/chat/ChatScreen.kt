package com.aiope2.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel(), onOpenSettings: () -> Unit = {}) {
  val messages by viewModel.messages.collectAsStateWithLifecycle()
  val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
  val terminalVisible by viewModel.terminalVisible.collectAsStateWithLifecycle()
  val modelLabel by viewModel._modelLabel.collectAsStateWithLifecycle()
  val browserVisible by viewModel.browserVisible.collectAsStateWithLifecycle()
  val browserMaximized by viewModel.browserMaximized.collectAsStateWithLifecycle()
  val agentMode by viewModel.agentMode.collectAsStateWithLifecycle()
  val autoRun by viewModel.autoRun.collectAsStateWithLifecycle()
  val subagentTasks by viewModel.subagentManager.tasks.collectAsStateWithLifecycle()
  val config = LocalConfiguration.current
  val isLandscape = config.screenWidthDp > config.screenHeightDp
  var showModelPicker by remember { mutableStateOf(false) }
  var showConversations by remember { mutableStateOf(false) }
  var editText by remember { mutableStateOf("") }

  @OptIn(ExperimentalLayoutApi::class)
  val imeVisible = WindowInsets.isImeVisible
  val listState = rememberLazyListState()

  if (isLandscape) {
    Row(Modifier.fillMaxSize()) {
      if (!browserMaximized) {
        ChatContent(
          messages = messages, isStreaming = isStreaming,
          agentMode = agentMode, onModeChange = { viewModel.setAgentMode(it) },
          autoRun = autoRun, onAutoRunChange = { viewModel.setAutoRun(it) },
          subagentTasks = subagentTasks,
          terminalVisible = terminalVisible,
          browserVisible = browserVisible,
          imeVisible = imeVisible, modelLabel = modelLabel,
          listState = listState,
          onSend = { text, imgs -> viewModel.send(text, imgs) }, onStop = { viewModel.cancelStreaming() }, onToggleTerminal = viewModel::toggleTerminal,
          onToggleBrowser = { viewModel.toggleBrowser() },
          onOpenSettings = onOpenSettings,
          onGetModels = { viewModel.getModelList() }, onGetActiveModelId = { viewModel.providerStore.getActive().selectedModelId },
          onSwitchModel = { viewModel.switchModel(it) },
          onChats = { showConversations = true },
          onShareChat = { viewModel.shareConversation() },
          onEditMessage = { text, idx ->
            viewModel.truncateAt(idx)
            editText = text
          },
          onRetry = { idx -> viewModel.retry(idx) },
          onCompact = { idx -> viewModel.compact(idx) },
          onFork = { idx -> viewModel.fork(idx) },
          onTranslate = { msgId, lang -> viewModel.translateMessage(msgId, lang) },
          editText = editText, onEditTextChange = { editText = it },
          modifier = Modifier.weight(1f),
        )
        if (terminalVisible) {
          TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.width(360.dp).fillMaxHeight())
        }
      }
      if (browserVisible) {
        com.aiope2.feature.chat.browser.BrowserPanel(
          maximized = browserMaximized,
          onToggleMaximize = { viewModel.setBrowserMaximized(!browserMaximized) },
          modifier = if (browserMaximized) Modifier.fillMaxSize() else Modifier.width(360.dp).fillMaxHeight(),
        )
      }
    }
  } else {
    Column(Modifier.fillMaxSize()) {
      if (!browserMaximized) {
        ChatContent(
          messages = messages, isStreaming = isStreaming,
          agentMode = agentMode, onModeChange = { viewModel.setAgentMode(it) },
          autoRun = autoRun, onAutoRunChange = { viewModel.setAutoRun(it) },
          subagentTasks = subagentTasks,
          terminalVisible = terminalVisible,
          browserVisible = browserVisible,
          imeVisible = imeVisible, modelLabel = modelLabel,
          listState = listState,
          onSend = { text, imgs -> viewModel.send(text, imgs) }, onStop = { viewModel.cancelStreaming() }, onToggleTerminal = viewModel::toggleTerminal,
          onToggleBrowser = { viewModel.toggleBrowser() },
          onOpenSettings = onOpenSettings,
          onGetModels = { viewModel.getModelList() }, onGetActiveModelId = { viewModel.providerStore.getActive().selectedModelId },
          onSwitchModel = { viewModel.switchModel(it) },
          onChats = { showConversations = true },
          onShareChat = { viewModel.shareConversation() },
          onEditMessage = { text, idx ->
            viewModel.truncateAt(idx)
            editText = text
          },
          onRetry = { idx -> viewModel.retry(idx) },
          onCompact = { idx -> viewModel.compact(idx) },
          onFork = { idx -> viewModel.fork(idx) },
          onTranslate = { msgId, lang -> viewModel.translateMessage(msgId, lang) },
          editText = editText, onEditTextChange = { editText = it },
          modifier = Modifier.weight(1f),
        )
        if (terminalVisible) {
          TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.fillMaxWidth().height(240.dp))
        }
      }
      if (browserVisible) {
        com.aiope2.feature.chat.browser.BrowserPanel(
          maximized = browserMaximized,
          onToggleMaximize = { viewModel.setBrowserMaximized(!browserMaximized) },
          modifier = if (browserMaximized) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(300.dp),
        )
      }
    }
  }

  if (showConversations) ConversationSheet(viewModel, onDismiss = { showConversations = false })
}

// ── Main chat content ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
  messages: List<ChatMessage>,
  isStreaming: Boolean,
  agentMode: com.aiope2.feature.chat.engine.AgentMode = com.aiope2.feature.chat.engine.AgentMode.CHAT,
  onModeChange: (com.aiope2.feature.chat.engine.AgentMode) -> Unit = {},
  autoRun: Boolean = false,
  onAutoRunChange: (Boolean) -> Unit = {},
  subagentTasks: List<com.aiope2.feature.chat.engine.SubagentManager.SubagentTask> = emptyList(),
  terminalVisible: Boolean,
  browserVisible: Boolean,
  imeVisible: Boolean,
  modelLabel: String,
  listState: androidx.compose.foundation.lazy.LazyListState,
  onSend: (String, List<String>) -> Unit,
  onStop: () -> Unit = {},
  onToggleTerminal: () -> Unit,
  onToggleBrowser: () -> Unit,
  onOpenSettings: () -> Unit,
  onGetModels: () -> List<com.aiope2.core.network.ModelDef>,
  onGetActiveModelId: () -> String,
  onSwitchModel: (String) -> Unit,
  onChats: () -> Unit,
  onShareChat: () -> Unit,
  onEditMessage: (String, Int) -> Unit = { _, _ -> },
  onRetry: (Int) -> Unit = {},
  onCompact: (Int) -> Unit = {},
  onFork: (Int) -> Unit = {},
  onTranslate: (String, String) -> Unit = { _, _ -> },
  editText: String = "",
  onEditTextChange: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showModelPicker by remember { mutableStateOf(false) }
  val theme = com.aiope2.feature.chat.theme.LocalThemeState.current
  Box(modifier.background(MaterialTheme.colorScheme.background)) {
    com.aiope2.feature.chat.theme.ChatBackground(theme)
    Column(Modifier.fillMaxSize().alpha(theme.uiOpacity)) {
      // ── Toolbar ──
      val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
      val barColor = if (isLight) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainer
      val barBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isLight) 0.6f else 0.3f)
      Box(Modifier.fillMaxWidth().zIndex(1f)) {
        Surface(
          color = barColor,
          border = androidx.compose.foundation.BorderStroke(0.5.dp, barBorder),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            // Left: Chats icon + Share
            Row(modifier = Modifier.align(Alignment.CenterStart)) {
              IconButton(onClick = onChats, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Forum, "Chats", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
              }
              IconButton(onClick = onShareChat, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Share, "Share", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
              }
            }
            // Center: Model dropdown spinner
            Box(modifier = Modifier.align(Alignment.Center)) {
              TextButton(
                onClick = { showModelPicker = !showModelPicker },
                contentPadding = PaddingValues(horizontal = 8.dp),
              ) {
                Text(modelLabel, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
              }
              DropdownMenu(expanded = showModelPicker, onDismissRequest = { showModelPicker = false }) {
                val models = onGetModels()
                val activeModelId = onGetActiveModelId()
                models.forEach { m ->
                  val selected = m.id == activeModelId
                  DropdownMenuItem(
                    text = {
                      Text(
                        "${if (selected) "• " else ""}${m.displayName}",
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                      )
                    },
                    onClick = {
                      onSwitchModel(m.id)
                      showModelPicker = false
                    },
                  )
                }
                if (models.isEmpty()) {
                  DropdownMenuItem(text = { Text("No models — fetch in Settings", fontSize = 12.sp) }, onClick = {})
                }
              }
            }
            // Right: Browser + Terminal + Settings
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
              IconButton(onClick = onToggleBrowser, modifier = Modifier.size(36.dp)) {
                Icon(
                  Icons.Default.Language,
                  "Browser",
                  modifier = Modifier.size(18.dp),
                  tint = if (browserVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
              IconButton(onClick = onToggleTerminal, modifier = Modifier.size(36.dp)) {
                Icon(
                  Icons.Default.Terminal,
                  "Terminal",
                  modifier = Modifier.size(18.dp),
                  tint = if (terminalVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
              IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                  Icons.Default.Settings,
                  "Settings",
                  modifier = Modifier.size(18.dp),
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
        // Pill hanging below toolbar into chat area
        Surface(
          shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
          color = barColor,
          modifier = Modifier.align(Alignment.BottomCenter).offset(y = 22.dp)
            .drawBehind {
              val stroke = 0.5.dp.toPx()
              val r = 16.dp.toPx()
              val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, size.height - r)
                arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2 * r, 2 * r, size.height), 180f, -90f, false)
                lineTo(size.width - r, size.height)
                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * r, size.height - 2 * r, size.width, size.height), 90f, -90f, false)
                lineTo(size.width, 0f)
              }
              drawPath(path, barBorder, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            },
        ) {
          Row(
            Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            com.aiope2.feature.chat.engine.AgentMode.entries.forEach { mode ->
              val selected = mode == agentMode
              val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent
              val textColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              Text(
                text = mode.label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                modifier = Modifier
                  .clip(RoundedCornerShape(12.dp))
                  .background(bg)
                  .clickable { onModeChange(mode) }
                  .padding(horizontal = 14.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // ── Messages or empty state ──
      if (messages.isEmpty()) {
        EmptyState(onSend = onSend, modifier = Modifier.weight(1f))
      } else {
        MessageList(
          messages = messages, isStreaming = isStreaming,
          onEdit = { idx -> onEditMessage(messages[idx].content, idx) },
          onRetry = { idx -> onRetry(idx) },
          onCompact = { idx -> onCompact(idx) },
          onFork = { idx -> onFork(idx) },
          onTranslate = onTranslate,
          onUiCallback = { event, data ->
            val msg = if (data.isNotEmpty()) "Responded with: ${data.entries.joinToString(", ") { "${it.key}: ${it.value}" }}" else "Pressed: $event"
            onSend(msg, emptyList())
          },
          onRunCode = { code, lang ->
            onSend("Execute this $lang code using run_proot:\n```$lang\n$code\n```", emptyList())
          },
          subagentTasks = subagentTasks,
          listState = listState,
          modifier = Modifier.weight(1f),
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // ── Input ──
      Surface(color = MaterialTheme.colorScheme.surface) {
        ChatInput(onSend = onSend, onStop = onStop, isStreaming = isStreaming, editText = editText, onEditTextChange = onEditTextChange, autoRun = autoRun, onAutoRunChange = onAutoRunChange)
      }
    }
  }
}

// ── Empty state ──

@Composable
private fun EmptyState(onSend: (String, List<String>) -> Unit, modifier: Modifier = Modifier) {
  val suggestions = listOf(
    "What's the weather like right now?",
    "Find the closest coffee shop",
    "Search the web for today's top news",
    "Show me today's NASA astronomy photo",
    "Generate an image of a sunset over mountains",
    "Remember that my favorite color is blue",
  )
  val purple = Color(0xFF00E5FF)
  Column(
    modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("AIOPE", fontSize = 24.sp, color = purple)
    Text(
      "What can I help you with?",
      fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(16.dp))
    suggestions.forEach { s ->
      TextButton(onClick = { onSend(s, emptyList()) }, modifier = Modifier.fillMaxWidth()) {
        Text(s, fontSize = 13.sp, color = purple)
      }
    }
  }
}

// ── Message list ──

@Composable
private fun MessageList(
  messages: List<ChatMessage>,
  isStreaming: Boolean = false,
  onEdit: ((Int) -> Unit)? = null,
  onRetry: ((Int) -> Unit)? = null,
  onCompact: ((Int) -> Unit)? = null,
  onFork: ((Int) -> Unit)? = null,
  onTranslate: ((String, String) -> Unit)? = null,
  onUiCallback: ((String, Map<String, String>) -> Unit)? = null,
  onRunCode: ((code: String, language: String) -> Unit)? = null,
  subagentTasks: List<com.aiope2.feature.chat.engine.SubagentManager.SubagentTask> = emptyList(),
  listState: androidx.compose.foundation.lazy.LazyListState,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  // No auto-scroll — user controls scroll, use ▼ button to go to bottom
  Box(modifier = modifier) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
      items(messages.size, key = { messages[it].id }) { idx ->
        val msg = messages[idx]
        MessageBubble(
          message = msg,
          isLastStreaming = isStreaming && idx == messages.lastIndex && msg.role == Role.ASSISTANT,
          onEdit = if (msg.role == Role.USER) {
            { onEdit?.invoke(idx) }
          } else {
            null
          },
          onRetry = if (msg.role == Role.ASSISTANT) {
            { onRetry?.invoke(idx) }
          } else {
            null
          },
          onCompact = { onCompact?.invoke(idx) },
          onFork = { onFork?.invoke(idx) },
          onTranslate = if (msg.role == Role.ASSISTANT) {
            { lang -> onTranslate?.invoke(msg.id, lang) }
          } else {
            null
          },
          onUiCallback = if (msg.role == Role.ASSISTANT) onUiCallback else null,
          onRunCode = onRunCode,
          subagentTasks = if (isStreaming && idx == messages.lastIndex && msg.role == Role.ASSISTANT) subagentTasks else emptyList(),
        )
        Spacer(Modifier.height(8.dp))
      }
      item(key = "bottom_anchor") { Spacer(Modifier.height(1.dp)) }
    }
    // Scroll nav: right center of screen
    if (messages.size > 2) {
      Column(
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        val btnMod = Modifier.size(28.dp)
        val btnColors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
        IconButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }, modifier = btnMod, colors = btnColors) {
          Text("\u25B2", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        IconButton(onClick = { scope.launch { listState.animateScrollToItem(messages.size / 2) } }, modifier = btnMod, colors = btnColors) {
          Icon(Icons.Default.FiberManualRecord, "Center", modifier = Modifier.size(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        IconButton(onClick = {
          scope.launch {
            listState.animateScrollToItem(messages.size)
          }
        }, modifier = btnMod, colors = btnColors) {
          Text("\u25BC", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

// ── Input bar ──

@Composable
private fun ChatInput(onSend: (String, List<String>) -> Unit, onStop: () -> Unit = {}, isStreaming: Boolean, editText: String = "", onEditTextChange: (String) -> Unit = {}, autoRun: Boolean = false, onAutoRunChange: (Boolean) -> Unit = {}) {
  var text by remember { mutableStateOf("") }
  val pendingImages = remember { mutableStateListOf<String>() }

  LaunchedEffect(editText) {
    if (editText.isNotBlank()) {
      text = editText
      onEditTextChange("")
    }
  }
  val context = androidx.compose.ui.platform.LocalContext.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.GetContent(),
  ) { uri ->
    uri?.let {
      val mime = context.contentResolver.getType(it) ?: ""
      if (mime.startsWith("image/")) {
        pendingImages.add(it.toString())
      } else scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val result = if (mime == "application/pdf") {
        try {
          val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: byteArrayOf()
          val name = it.lastPathSegment ?: "document.pdf"
          com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
          val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes)
          val pageCount = doc.numberOfPages
          val extracted = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc).take(100000)
          doc.close()
          (if (text.isNotBlank()) "\n" else "") + "[$name - $pageCount pages]\n${extracted.ifBlank { "[No extractable text]" }}"
        } catch (e: Exception) {
          "\n[PDF error: ${e.message}]"
        }
      } else {
        try {
          val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.take(10000) ?: ""
          val name = it.lastPathSegment ?: "file"
          (if (text.isNotBlank()) "\n" else "") + "[$name]\n$content"
        } catch (_: Exception) {
          "\n[Attached: $it]"
        }
      }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { text = text + result }
      }
    }
  }

  Column(Modifier.fillMaxWidth().padding(8.dp)) {
    // Pending image thumbnails
    if (pendingImages.isNotEmpty()) {
      Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        pendingImages.forEach { uri ->
          Box(Modifier.size(48.dp)) {
            val bmp = remember(uri) {
              try {
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, android.net.Uri.parse(uri))
              } catch (_: Exception) {
                null
              }
            }
            if (bmp != null) {
              AndroidView(factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                  scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                  setImageBitmap(bmp)
                  clipToOutline = true
                }
              }, modifier = Modifier.size(48.dp))
            }
          }
        }
        Text(
          "${pendingImages.size} image(s)",
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }
    }
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Ask AI...") },
      maxLines = 6,
      enabled = !isStreaming,
      colors = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
      ),
      shape = RoundedCornerShape(16.dp),
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // Attach — opens system file picker (all types)
      IconButton(onClick = { launcher.launch("*/*") }) {
        Icon(Icons.Default.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurface)
      }
      // Camera — capture photo
      val cameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
      val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
      ) { success -> if (success) cameraUri.value?.let { pendingImages.add(it.toString()) } }
      IconButton(onClick = {
        val file = java.io.File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri.value = uri
        photoLauncher.launch(uri)
      }) {
        Icon(Icons.Default.CameraAlt, "Camera", tint = MaterialTheme.colorScheme.onSurface)
      } // Mic — launches Android speech recognizer
      val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
      ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
          val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
          if (!spoken.isNullOrBlank()) {
            text = text + (if (text.isNotBlank()) " " else "") + spoken
          }
        }
      }
      IconButton(onClick = {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        try {
          speechLauncher.launch(intent)
        } catch (_: Exception) {}
      }) {
        Icon(Icons.Default.Mic, "Voice", tint = MaterialTheme.colorScheme.onSurface)
      }
      // Clear
      IconButton(onClick = {
        text = ""
        pendingImages.clear()
      }) {
        Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurface)
      }
      Spacer(Modifier.weight(1f))
      // Auto-run toggle (vertical: up=off, down=on)
      Switch(
        checked = autoRun,
        onCheckedChange = onAutoRunChange,
        modifier = Modifier.graphicsLayer { rotationZ = 90f }.scale(0.65f),
      )
      // Send / Stop
      Button(
        onClick = {
          if (isStreaming) {
            onStop()
          } else if (text.isNotBlank() || pendingImages.isNotEmpty()) {
            onSend(text.trim(), pendingImages.toList())
            text = ""
            pendingImages.clear()
          }
        },
        enabled = text.isNotBlank() || pendingImages.isNotEmpty() || isStreaming,
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isStreaming) Color(0xFFFF1744) else MaterialTheme.colorScheme.primary,
        ),
      ) {
        Text(if (isStreaming) "Stop" else "Send")
      }
    }
  }
}

// ── Model picker bottom sheet ──

// ── Conversation list bottom sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
  val conversations by viewModel.conversations.collectAsStateWithLifecycle()
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Conversations", style = MaterialTheme.typography.titleSmall)
      Row {
        TextButton(onClick = {
          viewModel.newConversation()
          onDismiss()
        }) { Text("+ New Chat") }
      }
    }
    if (conversations.isEmpty()) {
      Text("No conversations yet.", Modifier.padding(16.dp))
    }
    conversations.forEach { conv ->
      ListItem(
        headlineContent = { Text(conv.title, maxLines = 1) },
        supportingContent = {
          Text(
            java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(conv.updatedAt),
            style = MaterialTheme.typography.bodySmall,
          )
        },
        trailingContent = {
          IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp))
          }
        },
        modifier = Modifier.clickable {
          viewModel.loadConversation(conv.id)
          onDismiss()
        },
      )
    }
    Spacer(Modifier.height(32.dp))
  }
}
