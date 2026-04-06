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
  var showConversations by remember { mutableStateOf(false) }

  @OptIn(ExperimentalLayoutApi::class)
  val imeVisible = WindowInsets.isImeVisible

  if (isLandscape) {
    Row(Modifier.fillMaxSize()) {
      ChatContent(
        messages = messages, isStreaming = isStreaming, terminalVisible = terminalVisible,
        imeVisible = imeVisible, modelLabel = viewModel.modelLabel,
        onSend = viewModel::send, onToggleTerminal = viewModel::toggleTerminal,
        onOpenSettings = onOpenSettings, onModelPicker = { showModelPicker = true },
        onChats = { showConversations = true }, onNewChat = { viewModel.newConversation() },
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
        imeVisible = imeVisible, modelLabel = viewModel.modelLabel,
        onSend = viewModel::send, onToggleTerminal = viewModel::toggleTerminal,
        onOpenSettings = onOpenSettings, onModelPicker = { showModelPicker = true },
        onChats = { showConversations = true }, onNewChat = { viewModel.newConversation() },
        modifier = Modifier.weight(1f)
      )
      if (terminalVisible) {
        TerminalPanel(keyboardVisible = imeVisible, modifier = Modifier.fillMaxWidth().height(240.dp))
      }
    }
  }

  if (showModelPicker) ModelPickerSheet(viewModel, onDismiss = { showModelPicker = false })
  if (showConversations) ConversationSheet(viewModel, onDismiss = { showConversations = false })
}

// ── Main chat content ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
  messages: List<ChatMessage>, isStreaming: Boolean, terminalVisible: Boolean,
  imeVisible: Boolean, modelLabel: String,
  onSend: (String) -> Unit, onToggleTerminal: () -> Unit,
  onOpenSettings: () -> Unit, onModelPicker: () -> Unit,
  onChats: () -> Unit, onNewChat: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier) {
    // ── Toolbar ──
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextButton(onClick = onChats, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("Chats", fontSize = 12.sp)
      }
      TextButton(onClick = onModelPicker, modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(modelLabel, fontSize = 12.sp, maxLines = 1,
          modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      }
      IconButton(onClick = onToggleTerminal, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Default.Terminal, "Terminal", modifier = Modifier.size(18.dp),
          tint = if (terminalVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
      }
      IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Default.Settings, "Settings", modifier = Modifier.size(18.dp))
      }
    }
    HorizontalDivider()

    // ── Messages or empty state ──
    if (messages.isEmpty()) {
      EmptyState(onSend = onSend, modifier = Modifier.weight(1f))
    } else {
      MessageList(messages = messages, modifier = Modifier.weight(1f))
    }

    HorizontalDivider()

    // ── Input ──
    ChatInput(onSend = onSend, isStreaming = isStreaming)
  }
}

// ── Empty state ──

@Composable
private fun EmptyState(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
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
      TextButton(onClick = { onSend(s) }, modifier = Modifier.fillMaxWidth()) {
        Text(s, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
      }
    }
  }
}

// ── Message list ──

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
      Spacer(Modifier.height(8.dp))
    }
  }
}

// ── Input bar ──

@Composable
private fun ChatInput(onSend: (String) -> Unit, isStreaming: Boolean) {
  var text by remember { mutableStateOf("") }

  Column(Modifier.fillMaxWidth().padding(8.dp)) {
    OutlinedTextField(
      value = text, onValueChange = { text = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Ask AI...") },
      maxLines = 6, enabled = !isStreaming
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // Attach
      IconButton(onClick = { /* TODO: file picker */ }) {
        Icon(Icons.Default.AttachFile, "Attach")
      }
      // Mic
      IconButton(onClick = { /* TODO: audio recording */ }) {
        Icon(Icons.Default.Mic, "Record")
      }
      // Clear
      IconButton(onClick = { text = "" }) {
        Icon(Icons.Default.Clear, "Clear")
      }
      Spacer(Modifier.weight(1f))
      // Send / Stop
      Button(
        onClick = {
          if (text.isNotBlank()) { onSend(text.trim()); text = "" }
        },
        enabled = text.isNotBlank() || isStreaming,
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
        headlineContent = { Text("${if (selected) "● " else "  "}${m.displayName}",
          color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        supportingContent = if (m.contextWindow > 0) {{ Text("${m.contextWindow/1000}k${if (m.supportsTools) " 🔧" else ""}${if (m.supportsVision) " 👁" else ""}",
          style = MaterialTheme.typography.bodySmall) }} else null,
        modifier = Modifier.clickable { viewModel.switchModel(m.id); onDismiss() }
      )
    }
    if (models.isEmpty()) Text("No models. Fetch in Settings.", Modifier.padding(16.dp))
    Spacer(Modifier.height(32.dp))
  }
}

// ── Conversation list bottom sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
  val conversations by viewModel.conversations.collectAsStateWithLifecycle()
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Conversations", style = MaterialTheme.typography.titleSmall)
      TextButton(onClick = { viewModel.newConversation(); onDismiss() }) { Text("+ New Chat") }
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
