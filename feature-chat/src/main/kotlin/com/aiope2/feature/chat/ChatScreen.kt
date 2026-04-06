package com.aiope2.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel(), onOpenSettings: () -> Unit = {}) {
  val messages by viewModel.messages.collectAsStateWithLifecycle()
  val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
  val terminalVisible by viewModel.terminalVisible.collectAsStateWithLifecycle()
  val config = LocalConfiguration.current
  val isLandscape = config.screenWidthDp > config.screenHeightDp
  var showModelPicker by remember { mutableStateOf(false) }

  @OptIn(ExperimentalLayoutApi::class)
  val imeVisible = WindowInsets.isImeVisible

  if (isLandscape) {
    LandscapeLayout(messages, isStreaming, terminalVisible, imeVisible, viewModel::send, viewModel::toggleTerminal, onOpenSettings, { showModelPicker = true }, viewModel.modelLabel)
  } else {
    PortraitLayout(messages, isStreaming, terminalVisible, imeVisible, viewModel::send, viewModel::toggleTerminal, onOpenSettings, { showModelPicker = true }, viewModel.modelLabel)
  }

  if (showModelPicker) {
    ModelPickerSheet(viewModel = viewModel, onDismiss = { showModelPicker = false })
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
  val models = remember { viewModel.getModelList() }
  val active = viewModel.providerStore.getActive()

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Text(active.label, style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    models.forEach { m ->
      val selected = m.id == active.selectedModelId
      ListItem(
        headlineContent = {
          Text(
            text = "${if (selected) "● " else "  "}${m.displayName}",
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
          )
        },
        supportingContent = if (m.contextWindow > 0) {{
          Text("${m.contextWindow / 1000}k${if (m.supportsTools) " 🔧" else ""}${if (m.supportsVision) " 👁" else ""}",
            style = MaterialTheme.typography.bodySmall)
        }} else null,
        modifier = Modifier.clickable {
          viewModel.switchModel(m.id)
          onDismiss()
        }
      )
    }
    if (models.isEmpty()) {
      Text("No models. Fetch models in Settings.", modifier = Modifier.padding(16.dp))
    }
    Spacer(Modifier.height(32.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitLayout(
  messages: List<ChatMessage>,
  isStreaming: Boolean,
  terminalVisible: Boolean,
  imeVisible: Boolean,
  onSend: (String) -> Unit,
  onToggleTerminal: () -> Unit,
  onOpenSettings: () -> Unit,
  onModelPicker: () -> Unit,
  modelLabel: String
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { TextButton(onClick = onModelPicker) { Text(modelLabel, style = MaterialTheme.typography.titleMedium) } },
        actions = {
          IconButton(onClick = onToggleTerminal) {
            Icon(
              Icons.Default.Terminal,
              contentDescription = "Terminal",
              tint = if (terminalVisible) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.onSurface
            )
          }
          IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
          }
        }
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        
    ) {
      // Chat messages take remaining space
      MessageList(messages = messages, modifier = Modifier.weight(1f))

      // Input bar
      ChatInput(onSend = onSend, isStreaming = isStreaming)

      // Terminal panel (fixed height)
      if (terminalVisible) {
        TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.fillMaxWidth().height(240.dp))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LandscapeLayout(
  messages: List<ChatMessage>,
  isStreaming: Boolean,
  terminalVisible: Boolean,
  imeVisible: Boolean,
  onSend: (String) -> Unit,
  onToggleTerminal: () -> Unit,
  onOpenSettings: () -> Unit,
  onModelPicker: () -> Unit,
  modelLabel: String
) {
  Row(modifier = Modifier.fillMaxSize()) {
    Scaffold(
      modifier = Modifier.weight(1f),
      topBar = {
        TopAppBar(
          title = { TextButton(onClick = onModelPicker) { Text(modelLabel, style = MaterialTheme.typography.titleMedium) } },
          actions = {
            IconButton(onClick = onToggleTerminal) {
              Icon(
                Icons.Default.Terminal,
                contentDescription = "Terminal",
                tint = if (terminalVisible) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface
              )
            }
            IconButton(onClick = onOpenSettings) {
              Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
          }
        )
      }
    ) { padding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          
      ) {
        MessageList(messages = messages, modifier = Modifier.weight(1f))
        ChatInput(onSend = onSend, isStreaming = isStreaming)
      }
    }

    if (terminalVisible) {
      TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.width(360.dp).fillMaxHeight())
    }
  }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(messages.size - 1) }
  }

  LazyColumn(state = listState, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
    items(messages, key = { it.id }) { msg ->
      MessageBubble(message = msg)
      Spacer(modifier = Modifier.height(8.dp))
    }
  }
}

@Composable
private fun ChatInput(onSend: (String) -> Unit, isStreaming: Boolean) {
  var text by remember { mutableStateOf("") }

  Row(
    modifier = Modifier.fillMaxWidth().padding(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      modifier = Modifier.weight(1f),
      placeholder = { Text("Message...") },
      maxLines = 4,
      enabled = !isStreaming
    )
    Spacer(modifier = Modifier.width(8.dp))
    IconButton(
      onClick = {
        if (text.isNotBlank()) {
          onSend(text.trim())
          text = ""
        }
      },
      enabled = text.isNotBlank() && !isStreaming
    ) {
      Icon(Icons.Default.Send, contentDescription = "Send")
    }
  }
}
