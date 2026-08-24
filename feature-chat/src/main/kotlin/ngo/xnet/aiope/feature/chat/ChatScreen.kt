package ngo.xnet.aiope.feature.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel(), startNewConversation: Boolean = false, onOpenSettings: () -> Unit = {}, onOpenHome: () -> Unit = {}) {
  LaunchedEffect(startNewConversation) {
    if (startNewConversation) viewModel.startNewConversation()
  }
  val messages by viewModel.messages.collectAsStateWithLifecycle()
  val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
  val isInRealtimeVoice by viewModel.isInRealtimeVoice.collectAsStateWithLifecycle()
  val isVoiceListening by viewModel.isVoiceListening.collectAsStateWithLifecycle()
  val isVoiceSpeaking by viewModel.isVoiceSpeaking.collectAsStateWithLifecycle()
  val supportsRealtimeVoice by viewModel.supportsRealtimeVoice.collectAsStateWithLifecycle()
  val terminalVisible by viewModel.terminalVisible.collectAsStateWithLifecycle()
  val modelLabel by viewModel._modelLabel.collectAsStateWithLifecycle()
  val browserVisible by viewModel.browserVisible.collectAsStateWithLifecycle()
  val browserMaximized by viewModel.browserMaximized.collectAsStateWithLifecycle()
  val agentPanelVisible by viewModel.agentPanelVisible.collectAsStateWithLifecycle()
  val agentRoster by viewModel.agentRoster.collectAsStateWithLifecycle()
  val persistedTasks by viewModel.persistedTasks.collectAsStateWithLifecycle()
  val scheduledTasks by viewModel.scheduledTasks.collectAsStateWithLifecycle()
  val agentMode by viewModel.agentMode.collectAsStateWithLifecycle()
  val autoRun by viewModel.autoRun.collectAsStateWithLifecycle()
  val subagentTasks by viewModel.subagentManager.tasks.collectAsStateWithLifecycle()
  val conversations by viewModel.conversations.collectAsStateWithLifecycle()
  val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
  val config = LocalConfiguration.current
  val isLandscape = config.screenWidthDp > config.screenHeightDp
  var showModelPicker by remember { mutableStateOf(false) }
  var showConversations by remember { mutableStateOf(false) }
  var showShareSheet by remember { mutableStateOf(false) }
  var showFileServer by remember { mutableStateOf(false) }
  var showScanner by remember { mutableStateOf(false) }
  var editText by remember { mutableStateOf("") }
  val context = androidx.compose.ui.platform.LocalContext.current
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val drawerScope = rememberCoroutineScope()

  @OptIn(ExperimentalLayoutApi::class)
  val imeVisible = WindowInsets.isImeVisible
  val listState = rememberLazyListState()

  val openDrawer = { drawerScope.launch { drawerState.open() } }
  val closeDrawer = { drawerScope.launch { drawerState.close() } }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ngo.xnet.aiope.feature.chat.ui.ChatDrawerContent(
          conversations = conversations,
          activeConversationId = activeConversationId,
          onNewChat = {
            viewModel.newConversation()
            closeDrawer()
          },
          onOpenConversation = {
            viewModel.loadConversation(it)
            closeDrawer()
          },
          onDeleteConversation = { viewModel.deleteConversation(it) },
          onOpenHome = {
            closeDrawer()
            onOpenHome()
          },
          onOpenSettings = {
            closeDrawer()
            onOpenSettings()
          },
        )
      }
    },
  ) {
    ChatScreenBody(
      viewModel = viewModel,
      messages = messages,
      isStreaming = isStreaming,
      isLandscape = isLandscape,
      imeVisible = imeVisible,
      listState = listState,
      modelLabel = modelLabel,
      agentMode = agentMode,
      autoRun = autoRun,
      subagentTasks = subagentTasks,
      terminalVisible = terminalVisible,
      browserVisible = browserVisible,
      browserMaximized = browserMaximized,
      agentPanelVisible = agentPanelVisible,
      agentRoster = agentRoster,
      persistedTasks = persistedTasks,
      scheduledTasks = scheduledTasks,
      supportsRealtimeVoice = supportsRealtimeVoice,
      isInRealtimeVoice = isInRealtimeVoice,
      isVoiceListening = isVoiceListening,
      isVoiceSpeaking = isVoiceSpeaking,
      editText = editText,
      onEditTextChange = { editText = it },
      onOpenDrawer = { openDrawer() },
      onOpenSettings = onOpenSettings,
      onOpenHome = onOpenHome,
      onShareChat = { showShareSheet = true },
      onFileServer = { showFileServer = true },
      onScanner = { showScanner = true },
    )
  }

  if (showShareSheet) {
    ShareFormatSheet(
      onDismissRequest = { showShareSheet = false },
      onFormatSelected = { format ->
        showShareSheet = false
        viewModel.shareConversation(format, context)
      },
    )
  }

  if (showFileServer) {
    ngo.xnet.aiope.feature.chat.fileserver.FileServerScreen(onBack = { showFileServer = false })
  }

  if (showScanner) {
    ngo.xnet.aiope.feature.chat.scanner.ScannerScreen(onBack = { showScanner = false })
  }
}

/**
 * Portrait/landscape arrangement of the chat surface and its side panels.
 *
 * Split out of [ChatScreen] so the drawer wrapper stays readable; it owns no state of its own.
 */
@Composable
private fun ChatScreenBody(
  viewModel: ChatViewModel,
  messages: List<ChatMessage>,
  isStreaming: Boolean,
  isLandscape: Boolean,
  imeVisible: Boolean,
  listState: androidx.compose.foundation.lazy.LazyListState,
  modelLabel: String,
  agentMode: ngo.xnet.aiope.feature.chat.engine.AgentMode,
  autoRun: Boolean,
  subagentTasks: List<ngo.xnet.aiope.feature.chat.engine.AgentExecutor.RunningTask>,
  terminalVisible: Boolean,
  browserVisible: Boolean,
  browserMaximized: Boolean,
  agentPanelVisible: Boolean,
  agentRoster: List<ngo.xnet.aiope.feature.chat.db.AgentEntity>,
  persistedTasks: List<ngo.xnet.aiope.feature.chat.db.AgentTaskEntity>,
  scheduledTasks: List<ngo.xnet.aiope.feature.chat.db.ScheduledTaskEntity>,
  supportsRealtimeVoice: Boolean,
  isInRealtimeVoice: Boolean,
  isVoiceListening: Boolean,
  isVoiceSpeaking: Boolean,
  editText: String,
  onEditTextChange: (String) -> Unit,
  onOpenDrawer: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenHome: () -> Unit,
  onShareChat: () -> Unit,
  onFileServer: () -> Unit,
  onScanner: () -> Unit,
) {
  val chat: @Composable (Modifier) -> Unit = { mod ->
    ChatContent(
      messages = messages, isStreaming = isStreaming,
      agentMode = agentMode, onModeChange = { viewModel.setAgentMode(it) },
      autoRun = autoRun, onAutoRunChange = { viewModel.setAutoRun(it) },
      subagentTasks = subagentTasks,
      terminalVisible = terminalVisible,
      browserVisible = browserVisible,
      agentPanelVisible = agentPanelVisible,
      imeVisible = imeVisible, modelLabel = modelLabel,
      listState = listState,
      onSend = { text, imgs -> viewModel.send(text, imgs) },
      onStop = { viewModel.cancelStreaming() },
      onToggleTerminal = viewModel::toggleTerminal,
      onToggleBrowser = { viewModel.toggleBrowser() },
      onToggleAgentPanel = { viewModel.toggleAgentPanel() },
      onOpenDrawer = onOpenDrawer,
      onNewChat = { viewModel.newConversation() },
      onOpenSettings = onOpenSettings,
      onGetModels = { viewModel.getModelList() },
      onGetActiveModelId = { viewModel.providerStore.getActive().selectedModelId },
      onSwitchModel = { viewModel.switchModel(it) },
      onShareChat = onShareChat,
      onFileServer = onFileServer,
      onScanner = onScanner,
      onEditMessage = { text, idx ->
        viewModel.truncateAt(idx)
        onEditTextChange(text)
      },
      onRetry = { idx -> viewModel.retry(idx) },
      onCompact = { idx -> viewModel.compact(idx) },
      onFork = { idx -> viewModel.fork(idx) },
      onTranslate = { msgId, lang -> viewModel.translateMessage(msgId, lang) },
      editText = editText, onEditTextChange = onEditTextChange,
      supportsRealtimeVoice = supportsRealtimeVoice,
      isInRealtimeVoice = isInRealtimeVoice,
      isVoiceListening = isVoiceListening,
      isVoiceSpeaking = isVoiceSpeaking,
      onToggleVoice = { viewModel.toggleRealtimeVoice() },
      modifier = mod,
    )
  }
  val agentPanel: @Composable (Modifier) -> Unit = { mod ->
    AgentPanel(
      modifier = mod,
      agents = agentRoster,
      runningTasks = subagentTasks,
      persistedTasks = persistedTasks,
      scheduledTasks = scheduledTasks,
      models = remember { viewModel.getModelList().map { it.id } },
      onSpawn = { agent, task -> viewModel.spawnAgentFromPanel(agent, task) },
      onCancelTask = { viewModel.cancelAgentTask(it) },
      onRerunTask = { viewModel.rerunAgentTask(it) },
      onSaveAgent = { viewModel.saveAgent(it) },
      onDeleteAgent = { viewModel.deleteAgent(it) },
      onSaveSchedule = { viewModel.saveScheduledTask(it) },
      onDeleteSchedule = { viewModel.deleteScheduledTask(it) },
    )
  }

  if (isLandscape) {
    Row(Modifier.fillMaxSize()) {
      if (!browserMaximized) {
        chat(Modifier.weight(1f))
        if (terminalVisible) {
          TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.width(360.dp).fillMaxHeight())
        }
        if (agentPanelVisible) agentPanel(Modifier.width(360.dp).fillMaxHeight())
      }
      if (browserVisible) {
        ngo.xnet.aiope.feature.chat.browser.BrowserPanel(
          maximized = browserMaximized,
          onToggleMaximize = { viewModel.setBrowserMaximized(!browserMaximized) },
          modifier = if (browserMaximized) Modifier.fillMaxSize() else Modifier.width(360.dp).fillMaxHeight(),
        )
      }
    }
  } else {
    Column(Modifier.fillMaxSize()) {
      if (!browserMaximized) {
        chat(Modifier.weight(1f))
        if (terminalVisible) {
          TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.fillMaxWidth().height(240.dp))
        }
        if (agentPanelVisible) agentPanel(Modifier.fillMaxWidth().height(240.dp))
      }
      if (browserVisible) {
        ngo.xnet.aiope.feature.chat.browser.BrowserPanel(
          maximized = browserMaximized,
          onToggleMaximize = { viewModel.setBrowserMaximized(!browserMaximized) },
          modifier = if (browserMaximized) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(300.dp),
        )
      }
    }
  }
}


// ── Main chat content ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
  messages: List<ChatMessage>,
  isStreaming: Boolean,
  agentMode: ngo.xnet.aiope.feature.chat.engine.AgentMode = ngo.xnet.aiope.feature.chat.engine.AgentMode.CHAT,
  onModeChange: (ngo.xnet.aiope.feature.chat.engine.AgentMode) -> Unit = {},
  autoRun: Boolean = false,
  onAutoRunChange: (Boolean) -> Unit = {},
  subagentTasks: List<ngo.xnet.aiope.feature.chat.engine.AgentExecutor.RunningTask> = emptyList(),
  terminalVisible: Boolean,
  browserVisible: Boolean,
  agentPanelVisible: Boolean = false,
  imeVisible: Boolean,
  modelLabel: String,
  listState: androidx.compose.foundation.lazy.LazyListState,
  onSend: (String, List<String>) -> Unit,
  onStop: () -> Unit = {},
  onToggleTerminal: () -> Unit,
  onToggleBrowser: () -> Unit,
  onToggleAgentPanel: () -> Unit = {},
  onOpenDrawer: () -> Unit = {},
  onNewChat: () -> Unit = {},
  onOpenSettings: () -> Unit,
  onGetModels: () -> List<ngo.xnet.aiope.core.network.ModelDef>,
  onGetActiveModelId: () -> String,
  onSwitchModel: (String) -> Unit,
  onShareChat: () -> Unit,
  onFileServer: () -> Unit = {},
  onScanner: () -> Unit = {},
  onEditMessage: (String, Int) -> Unit = { _, _ -> },
  onRetry: (Int) -> Unit = {},
  onCompact: (Int) -> Unit = {},
  onFork: (Int) -> Unit = {},
  onTranslate: (String, String) -> Unit = { _, _ -> },
  editText: String = "",
  onEditTextChange: (String) -> Unit = {},
  supportsRealtimeVoice: Boolean = false,
  isInRealtimeVoice: Boolean = false,
  isVoiceListening: Boolean = false,
  isVoiceSpeaking: Boolean = false,
  onToggleVoice: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
  Box(modifier.background(MaterialTheme.colorScheme.background)) {
    ngo.xnet.aiope.feature.chat.theme.ChatBackground(theme)
    Column(Modifier.fillMaxSize().alpha(theme.uiOpacity)) {
      // ── Top bar: drawer · model · new chat · overflow ──
      ngo.xnet.aiope.feature.chat.ui.ChatTopBar(
        modelLabel = modelLabel,
        onOpenDrawer = onOpenDrawer,
        onNewChat = onNewChat,
        onGetModels = onGetModels,
        onGetActiveModelId = onGetActiveModelId,
        onSwitchModel = onSwitchModel,
        browserVisible = browserVisible,
        terminalVisible = terminalVisible,
        agentPanelVisible = agentPanelVisible,
        autoRun = autoRun,
        onAutoRunChange = onAutoRunChange,
        onToggleBrowser = onToggleBrowser,
        onToggleTerminal = onToggleTerminal,
        onToggleAgentPanel = onToggleAgentPanel,
        onFileServer = onFileServer,
        onScanner = onScanner,
        onShareChat = onShareChat,
        onOpenSettings = onOpenSettings,
        modifier = Modifier.zIndex(1f),
      )

      // ── Messages or empty state ──
      if (messages.isEmpty()) {
        ngo.xnet.aiope.feature.chat.ui.ChatEmptyState(onSend = onSend, modifier = Modifier.weight(1f))
      } else {
        MessageList(
          messages = messages, isStreaming = isStreaming,
          onEdit = { idx -> onEditMessage(messages[idx].content, idx) },
          onRetry = { idx -> onRetry(idx) },
          onCompact = { idx -> onCompact(idx) },
          onFork = { idx -> onFork(idx) },
          onTranslate = onTranslate,
          onUiCallback = { event, data ->
            when (event) {
              "switch-mode" -> {
                val mode = data["mode"]?.uppercase()?.let { m ->
                  try {
                    ngo.xnet.aiope.feature.chat.engine.AgentMode.valueOf(m)
                  } catch (_: Exception) {
                    null
                  }
                }
                if (mode != null) onModeChange(mode)
              }

              "switch-model" -> data["model"]?.let { onSwitchModel(it) }

              "auto-run" -> onAutoRunChange(data["enabled"]?.toBooleanStrictOrNull() ?: true)

              "open-settings" -> onOpenSettings()

              "toggle-terminal" -> onToggleTerminal()

              "toggle-browser" -> onToggleBrowser()

              else -> {
                val msg = if (data.isNotEmpty()) "Responded with: ${data.entries.joinToString(", ") { "${it.key}: ${it.value}" }}" else "Pressed: $event"
                onSend(msg, emptyList())
              }
            }
          },
          onRunCode = { code, lang ->
            onSend("Execute this $lang code using run_proot:\n```$lang\n$code\n```", emptyList())
          },
          subagentTasks = subagentTasks,
          listState = listState,
          modifier = Modifier.weight(1f),
        )
      }

      // ── Mode selector + composer, grouped at the bottom where the user is typing ──
      Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ngo.xnet.aiope.feature.chat.ui.ModeSelector(agentMode = agentMode, onModeChange = onModeChange)
      }
      ngo.xnet.aiope.feature.chat.ui.GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(26.dp),
      ) {
        ChatInput(onSend = onSend, onStop = onStop, isStreaming = isStreaming, editText = editText, onEditTextChange = onEditTextChange, autoRun = autoRun, onAutoRunChange = onAutoRunChange, supportsRealtimeVoice = supportsRealtimeVoice, isInRealtimeVoice = isInRealtimeVoice, isVoiceListening = isVoiceListening, isVoiceSpeaking = isVoiceSpeaking, onToggleVoice = onToggleVoice)
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
  subagentTasks: List<ngo.xnet.aiope.feature.chat.engine.AgentExecutor.RunningTask> = emptyList(),
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
    // Scroll rail: glass chip on the right edge for long threads
    if (messages.size > 4) {
      ngo.xnet.aiope.feature.chat.ui.ScrollRail(
        onTop = { scope.launch { listState.animateScrollToItem(0) } },
        onMiddle = { scope.launch { listState.animateScrollToItem(messages.size / 2) } },
        onBottom = { scope.launch { listState.animateScrollToItem(messages.size) } },
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
      )
    }
  }
}

// ── Input bar ──

@Composable
private fun ChatInput(onSend: (String, List<String>) -> Unit, onStop: () -> Unit = {}, isStreaming: Boolean, editText: String = "", onEditTextChange: (String) -> Unit = {}, autoRun: Boolean = false, onAutoRunChange: (Boolean) -> Unit = {}, supportsRealtimeVoice: Boolean = false, isInRealtimeVoice: Boolean = false, isVoiceListening: Boolean = false, isVoiceSpeaking: Boolean = false, onToggleVoice: () -> Unit = {}) {
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
      } else {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
  }

  Column(Modifier.fillMaxWidth().padding(8.dp)) {
    // Pending image thumbnails
    if (pendingImages.isNotEmpty()) {
      Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        pendingImages.forEach { uri ->
          Box(Modifier.size(48.dp)) {
            coil.compose.AsyncImage(
              model = android.net.Uri.parse(uri),
              contentDescription = "attached image",
              contentScale = androidx.compose.ui.layout.ContentScale.Crop,
              modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
            )
          }
        }
        Text(
          "${pendingImages.size} image(s)",
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }
    }
    // Borderless field: the glass pane around the composer already provides the frame, so an
    // outlined box inside it reads as a double border.
    TextField(
      value = text,
      onValueChange = { text = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Message CuO…", fontSize = 15.sp) },
      maxLines = 6,
      enabled = !isStreaming,
      colors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
      ),
    )
    Row(
      Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val iconMod = Modifier.size(38.dp)
      val iconSize = Modifier.size(20.dp)
      // Attach — opens system file picker (all types)
      IconButton(onClick = { launcher.launch("*/*") }, modifier = iconMod) {
        Icon(Icons.Default.AttachFile, "Attach", iconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      // Camera — capture photo
      val cameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
      val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
      ) { success -> if (success) cameraUri.value?.let { pendingImages.add(it.toString()) } }
      IconButton(
        onClick = {
          val file = java.io.File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
          val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
          cameraUri.value = uri
          photoLauncher.launch(uri)
        },
        modifier = iconMod,
      ) {
        Icon(Icons.Default.CameraAlt, "Camera", iconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      // Mic — launches Android speech recognizer (dictation into the field)
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
      IconButton(
        onClick = {
          val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          }
          try {
            speechLauncher.launch(intent)
          } catch (_: Exception) {}
        },
        modifier = iconMod,
      ) {
        Icon(Icons.Default.Mic, "Dictate", iconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      // Live voice call
      IconButton(onClick = { onToggleVoice() }, modifier = iconMod) {
        Icon(
          imageVector = if (isInRealtimeVoice) Icons.Default.CallEnd else Icons.Default.GraphicEq,
          contentDescription = if (isInRealtimeVoice) "End voice call" else "Start voice call",
          modifier = iconSize,
          tint = if (isInRealtimeVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (isInRealtimeVoice) {
        RealtimeWaveform(
          isListening = isVoiceListening,
          isSpeaking = isVoiceSpeaking,
          modifier = Modifier.weight(1f),
        )
      } else {
        Spacer(Modifier.weight(1f))
      }
      // Send / Stop as a single round action, the way both reference apps do it.
      val canSend = text.isNotBlank() || pendingImages.isNotEmpty()
      FilledIconButton(
        onClick = {
          if (isStreaming) {
            onStop()
          } else if (canSend) {
            onSend(text.trim(), pendingImages.toList())
            text = ""
            pendingImages.clear()
          }
        },
        enabled = canSend || isStreaming,
        modifier = Modifier.size(42.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
          containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
      ) {
        Icon(
          if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
          if (isStreaming) "Stop" else "Send",
          Modifier.size(20.dp),
        )
      }
    }
  }
}


/**
 * Animated waveform visualization for realtime voice mode
 */
@Composable
fun RealtimeWaveform(
  isListening: Boolean,
  isSpeaking: Boolean,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "waveform")

  val alpha1 by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(300, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "a1",
  )
  val alpha2 by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0.3f,
    animationSpec = infiniteRepeatable(
      animation = tween(400, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "a2",
  )
  val alpha3 by infiniteTransition.animateFloat(
    initialValue = 0.5f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(350, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "a3",
  )

  val color = when {
    isSpeaking -> MaterialTheme.colorScheme.primary
    isListening -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val barHeights = if (isListening || isSpeaking) {
      listOf(alpha1, alpha2, alpha3, alpha2, alpha1)
    } else {
      listOf(0.3f, 0.3f, 0.3f, 0.3f, 0.3f)
    }

    barHeights.forEach { alpha ->
      Box(
        modifier = Modifier
          .width(4.dp)
          .height((20 * alpha).dp)
          .background(
            color = color.copy(alpha = alpha),
            shape = RoundedCornerShape(2.dp),
          ),
      )
      Spacer(modifier = Modifier.width(2.dp))
    }
  }
}
