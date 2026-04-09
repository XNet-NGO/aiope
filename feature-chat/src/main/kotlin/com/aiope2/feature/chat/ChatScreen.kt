package com.aiope2.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel(), onOpenSettings: () -> Unit = {}) {
  val messages by viewModel.messages.collectAsStateWithLifecycle()
  val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
  val terminalVisible by viewModel.terminalVisible.collectAsStateWithLifecycle()
  val modelLabel by viewModel._modelLabel.collectAsStateWithLifecycle()
  val config = LocalConfiguration.current
  val isLandscape = config.screenWidthDp > config.screenHeightDp
  var showModelPicker by remember { mutableStateOf(false) }
  var showConversations by remember { mutableStateOf(false) }
  var editText by remember { mutableStateOf("") }

  @OptIn(ExperimentalLayoutApi::class)
  val imeVisible = WindowInsets.isImeVisible

  if (isLandscape) {
    Row(Modifier.fillMaxSize()) {
      ChatContent(
        messages = messages, isStreaming = isStreaming, terminalVisible = terminalVisible,
        imeVisible = imeVisible, modelLabel = modelLabel,
        onSend = { text, imgs -> viewModel.send(text, imgs) }, onStop = { viewModel.cancelStreaming() }, onToggleTerminal = viewModel::toggleTerminal,
        onOpenSettings = onOpenSettings,
        onGetModels = { viewModel.getModelList() }, onGetActiveModelId = { viewModel.providerStore.getActive().selectedModelId },
        onSwitchModel = { viewModel.switchModel(it) },
        onChats = { showConversations = true },
        onEditMessage = { text, idx -> viewModel.truncateAt(idx); editText = text },
        onRetry = { idx -> viewModel.retry(idx) },
        onCompact = { idx -> viewModel.compact(idx) },
        onFork = { idx -> viewModel.fork(idx) },
        editText = editText, onEditTextChange = { editText = it },
        modifier = Modifier.weight(1f)
      )
      if (terminalVisible) {
        TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.width(360.dp).fillMaxHeight())
      }
    }
  } else {
    Column(Modifier.fillMaxSize()) {
      ChatContent(
        messages = messages, isStreaming = isStreaming, terminalVisible = terminalVisible,
        imeVisible = imeVisible, modelLabel = modelLabel,
        onSend = { text, imgs -> viewModel.send(text, imgs) }, onStop = { viewModel.cancelStreaming() }, onToggleTerminal = viewModel::toggleTerminal,
        onOpenSettings = onOpenSettings,
        onGetModels = { viewModel.getModelList() }, onGetActiveModelId = { viewModel.providerStore.getActive().selectedModelId },
        onSwitchModel = { viewModel.switchModel(it) },
        onChats = { showConversations = true },
        onEditMessage = { text, idx -> viewModel.truncateAt(idx); editText = text },
        onRetry = { idx -> viewModel.retry(idx) },
        onCompact = { idx -> viewModel.compact(idx) },
        onFork = { idx -> viewModel.fork(idx) },
        editText = editText, onEditTextChange = { editText = it },
        modifier = Modifier.weight(1f)
      )
      if (terminalVisible) {
        TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.fillMaxWidth().height(240.dp))
      }
    }
  }

  if (showConversations) ConversationSheet(viewModel, onDismiss = { showConversations = false })
}

// ── Main chat content ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
  messages: List<ChatMessage>, isStreaming: Boolean, terminalVisible: Boolean,
  imeVisible: Boolean, modelLabel: String,
  onSend: (String, List<String>) -> Unit, onStop: () -> Unit = {}, onToggleTerminal: () -> Unit,
  onOpenSettings: () -> Unit,
  onGetModels: () -> List<com.aiope2.core.network.ModelDef>, onGetActiveModelId: () -> String,
  onSwitchModel: (String) -> Unit,
  onChats: () -> Unit,
  onEditMessage: (String, Int) -> Unit = { _, _ -> },
  onRetry: (Int) -> Unit = {},
  onCompact: (Int) -> Unit = {},
  onFork: (Int) -> Unit = {},
  editText: String = "",
  onEditTextChange: (String) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var showModelPicker by remember { mutableStateOf(false) }
  Column(modifier) {
    // ── Toolbar ──
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
      // Left: Chats
      TextButton(onClick = onChats, modifier = Modifier.align(Alignment.CenterStart),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("Chats", fontSize = 12.sp)
      }
      // Center: Model dropdown spinner
      Box(modifier = Modifier.align(Alignment.Center)) {
        TextButton(onClick = { showModelPicker = !showModelPicker },
          contentPadding = PaddingValues(horizontal = 8.dp)) {
          Text(modelLabel, fontSize = 12.sp, maxLines = 1)
          Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = showModelPicker, onDismissRequest = { showModelPicker = false }) {
          val models = onGetModels()
          val activeModelId = onGetActiveModelId()
          models.forEach { m ->
            val selected = m.id == activeModelId
            DropdownMenuItem(
              text = { Text("${if (selected) "• " else ""}${m.displayName}",
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp) },
              onClick = { onSwitchModel(m.id); showModelPicker = false }
            )
          }
          if (models.isEmpty()) {
            DropdownMenuItem(text = { Text("No models — fetch in Settings", fontSize = 12.sp) }, onClick = {})
          }
        }
      }
      // Right: Terminal + Settings
      Row(modifier = Modifier.align(Alignment.CenterEnd)) {
        IconButton(onClick = onToggleTerminal, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Default.Terminal, "Terminal", modifier = Modifier.size(18.dp),
            tint = if (terminalVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Default.Settings, "Settings", modifier = Modifier.size(18.dp))
        }
      }
    }
    HorizontalDivider()

    // ── Messages or empty state ──
    if (messages.isEmpty()) {
      EmptyState(onSend = onSend, modifier = Modifier.weight(1f))
    } else {
      MessageList(messages = messages, isStreaming = isStreaming,
        onEdit = { idx -> onEditMessage(messages[idx].content, idx) },
        onRetry = { idx -> onRetry(idx) },
        onCompact = { idx -> onCompact(idx) },
        onFork = { idx -> onFork(idx) },
        modifier = Modifier.weight(1f))
    }

    HorizontalDivider()

    // ── Input ──
    ChatInput(onSend = onSend, onStop = onStop, isStreaming = isStreaming, editText = editText, onEditTextChange = onEditTextChange)
  }
}

// ── Empty state ──

@Composable
private fun EmptyState(onSend: (String, List<String>) -> Unit, modifier: Modifier = Modifier) {
  val suggestions = listOf(
    "Explain this error", "Write a Python script to...",
    "List files in /sdcard", "What's my Android version?"
  )
  Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center) {
    Text("AIOPE", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
    Text("What can I help you with?", fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(16.dp))
    suggestions.forEach { s ->
      TextButton(onClick = { onSend(s, emptyList()) }, modifier = Modifier.fillMaxWidth()) {
        Text(s, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
      }
    }
  }
}

// ── Message list ──

@Composable
private fun MessageList(messages: List<ChatMessage>, isStreaming: Boolean = false, onEdit: ((Int) -> Unit)? = null, onRetry: ((Int) -> Unit)? = null, onCompact: ((Int) -> Unit)? = null, onFork: ((Int) -> Unit)? = null, modifier: Modifier = Modifier) {
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(messages.size - 1) }
  }
  Box(modifier = modifier) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
      items(messages.size, key = { messages[it].id }) { idx ->
        val msg = messages[idx]
        MessageBubble(
          message = msg,
          isLastStreaming = isStreaming && idx == messages.lastIndex && msg.role == Role.ASSISTANT,
          onEdit = if (msg.role == Role.USER) {{ onEdit?.invoke(idx) }} else null,
          onRetry = if (msg.role == Role.ASSISTANT) {{ onRetry?.invoke(idx) }} else null,
          onCompact = { onCompact?.invoke(idx) },
          onFork = { onFork?.invoke(idx) }
        )
        Spacer(Modifier.height(8.dp))
      }
    }
    // Scroll nav: vertical on right side
    if (messages.size > 2) {
      Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
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
        IconButton(onClick = { scope.launch { listState.animateScrollToItem(messages.size - 1) } }, modifier = btnMod, colors = btnColors) {
          Text("\u25BC", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

// ── Input bar ──

@Composable
private fun ChatInput(onSend: (String, List<String>) -> Unit, onStop: () -> Unit = {}, isStreaming: Boolean, editText: String = "", onEditTextChange: (String) -> Unit = {}) {
  var text by remember { mutableStateOf("") }
  val pendingImages = remember { mutableStateListOf<String>() }

  LaunchedEffect(editText) { if (editText.isNotBlank()) { text = editText; onEditTextChange("") } }
  val context = androidx.compose.ui.platform.LocalContext.current
  val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.GetContent()
  ) { uri ->
    uri?.let {
      val mime = context.contentResolver.getType(it) ?: ""
      if (mime.startsWith("image/")) {
        pendingImages.add(it.toString())
      } else if (mime == "application/pdf") {
        try {
          val fd = context.contentResolver.openFileDescriptor(it, "r") ?: return@let
          val renderer = android.graphics.pdf.PdfRenderer(fd)
          val sb = StringBuilder()
          for (i in 0 until minOf(renderer.pageCount, 20)) {
            val page = renderer.openPage(i)
            // Render page to bitmap then extract via a simple approach
            val bmp = android.graphics.Bitmap.createBitmap(page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            // For now, note the page — full OCR would need ML Kit
            sb.append("[Page ${i + 1}/${renderer.pageCount}]\n")
          }
          renderer.close(); fd.close()
          // Use Android's built-in text extraction if available
          val textContent = try {
            val input = context.contentResolver.openInputStream(it)
            val bytes = input?.readBytes() ?: byteArrayOf()
            input?.close()
            // Extract raw text strings from PDF bytes (simple heuristic)
            val raw = String(bytes, Charsets.ISO_8859_1)
            val texts = Regex("\\(([^)]{2,})\\)").findAll(raw).map { m -> m.groupValues[1] }.joinToString(" ")
            if (texts.length > 50) texts.take(10000) else ""
          } catch (_: Exception) { "" }
          val name = it.lastPathSegment ?: "document.pdf"
          text = text + (if (text.isNotBlank()) "\n" else "") + "[$name - ${renderer.pageCount} pages]\n${textContent.ifBlank { sb.toString() }}"
        } catch (e: Exception) { text = text + "\n[PDF error: ${e.message}]" }
      } else {
        try {
          val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.take(10000) ?: ""
          val name = it.lastPathSegment ?: "file"
          text = text + (if (text.isNotBlank()) "\n" else "") + "[$name]\n$content"
        } catch (_: Exception) { text = text + "\n[Attached: $it]" }
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
              try { android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, android.net.Uri.parse(uri)) }
              catch (_: Exception) { null }
            }
            if (bmp != null) {
              AndroidView(factory = { ctx -> android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp); clipToOutline = true
              }}, modifier = Modifier.size(48.dp))
            }
          }
        }
        Text("${pendingImages.size} image(s)", style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.align(Alignment.CenterVertically))
      }
    }
    OutlinedTextField(
      value = text, onValueChange = { text = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Ask AI...") },
      maxLines = 6, enabled = !isStreaming
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // Attach — opens system file picker (all types)
      IconButton(onClick = { launcher.launch("*/*") }) {
        Icon(Icons.Default.AttachFile, "Attach")
      }
      // Camera — capture photo
      val cameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
      val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
      ) { success -> if (success) cameraUri.value?.let { pendingImages.add(it.toString()) } }
      IconButton(onClick = {
        val file = java.io.File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri.value = uri
        photoLauncher.launch(uri)
      }) {
        Icon(Icons.Default.CameraAlt, "Camera")
      }      // Mic — launches Android speech recognizer
      val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
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
        try { speechLauncher.launch(intent) } catch (_: Exception) {}
      }) {
        Icon(Icons.Default.Mic, "Voice")
      }
      // Clear
      IconButton(onClick = { text = ""; pendingImages.clear() }) {
        Icon(Icons.Default.Clear, "Clear")
      }
      Spacer(Modifier.weight(1f))
      // Send / Stop
      Button(
        onClick = {
          if (isStreaming) { onStop() }
          else if (text.isNotBlank() || pendingImages.isNotEmpty()) {
            onSend(text.trim(), pendingImages.toList()); text = ""; pendingImages.clear()
          }
        },
        enabled = text.isNotBlank() || pendingImages.isNotEmpty() || isStreaming,
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isStreaming) Color(0xFFCC0000) else MaterialTheme.colorScheme.primary
        )
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Conversations", style = MaterialTheme.typography.titleSmall)
      Row {
        TextButton(onClick = { viewModel.shareConversation(); onDismiss() }) { Text("Share") }
        TextButton(onClick = { viewModel.newConversation(); onDismiss() }) { Text("+ New Chat") }
      }
    }
    if (conversations.isEmpty()) {
      Text("No conversations yet.", Modifier.padding(16.dp))
    }
    conversations.forEach { conv ->
      ListItem(
        headlineContent = { Text(conv.title, maxLines = 1) },
        supportingContent = { Text(java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(conv.updatedAt),
          style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
          IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp))
          }
        },
        modifier = Modifier.clickable { viewModel.loadConversation(conv.id); onDismiss() }
      )
    }
    Spacer(Modifier.height(32.dp))
  }
}
