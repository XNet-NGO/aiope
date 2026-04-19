package com.aiope2.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.ConversationEntity
import com.aiope2.feature.chat.db.MessageEntity
import com.aiope2.feature.chat.engine.StreamingOrchestrator
import com.aiope2.feature.chat.engine.TokenCounter
import com.aiope2.feature.chat.engine.ToolExecutor
import com.aiope2.feature.chat.settings.McpManager
import com.aiope2.feature.chat.settings.ProviderStore
import com.aiope2.feature.chat.settings.ToolStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
  application: Application,
  private val chatDao: ChatDao,
  val providerStore: ProviderStore,
  val toolStore: ToolStore,
) : AndroidViewModel(application) {

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages = _messages.asStateFlow()

  private val _isStreaming = MutableStateFlow(false)
  val isStreaming = _isStreaming.asStateFlow()

  private var streamingJob: kotlinx.coroutines.Job? = null

  fun cancelStreaming() {
    streamingJob?.cancel()
    streamingJob = null
    _isStreaming.value = false
  }

  private val _terminalVisible = MutableStateFlow(false)
  val terminalVisible = _terminalVisible.asStateFlow()
  private val _browserVisible = MutableStateFlow(false)
  val browserVisible = _browserVisible.asStateFlow()
  private val _browserMaximized = MutableStateFlow(false)
  val browserMaximized = _browserMaximized.asStateFlow()

  private var conversationId = UUID.randomUUID().toString()

  val _modelLabel = MutableStateFlow("")
  val modelLabel: String get() = _modelLabel.value

  fun switchModel(modelId: String) {
    val p = providerStore.getActive()
    providerStore.save(p.copy(selectedModelId = modelId))
    _modelLabel.value = modelId.substringAfterLast('/').ifBlank { p.label.ifBlank { "No model" } }
  }

  private fun refreshModelLabel() {
    val p = providerStore.getActive()
    val id = p.selectedModelId.substringAfterLast('/')
    _modelLabel.value = id.ifBlank { p.label.ifBlank { "No model" } }
  }

  fun getModelList(): List<ModelDef> {
    val p = providerStore.getActive()
    return providerStore.getModelCache(p.builtinId)
      ?: providerStore.getModelCacheStale(p.builtinId)
      ?: ProviderTemplates.byId[p.builtinId]?.defaultModels
      ?: emptyList()
  }

  private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
  val conversations = _conversations.asStateFlow()

  init {
    refreshModelLabel()
    com.aiope2.feature.chat.browser.BrowserServer.start { getBrowser() }
    getBrowser() // preload WebView on main thread
    viewModelScope.launch {
      // Reuse last conversation if it exists, or find an empty one
      val all = chatDao.getConversations()
      val empty = all.firstOrNull { chatDao.getMessages(it.id).isEmpty() }
      if (empty != null) {
        conversationId = empty.id
      } else if (all.isNotEmpty()) {
        // Load the most recent conversation
        val last = all.first()
        conversationId = last.id
        val msgs = chatDao.getMessages(last.id).map {
          val uris = if (it.imagePaths.isNotBlank()) {
            it.imagePaths.split(",").mapNotNull { relPath ->
              val file = java.io.File(getApplication<android.app.Application>().filesDir, relPath.trim())
              if (file.exists()) android.net.Uri.fromFile(file).toString() else null
            }
          } else {
            emptyList()
          }
          ChatMessage(id = it.id, role = Role.from(it.role), content = it.content, imageUris = uris, timestamp = it.timestamp)
        }
        _messages.value = msgs
      } else {
        chatDao.insertConversation(ConversationEntity(id = conversationId))
      }
      refreshConversations()
    }
  }

  fun newConversation() {
    conversationId = UUID.randomUUID().toString()
    _messages.value = emptyList()
    toolExecutor.lastLocationData = null
    viewModelScope.launch {
      chatDao.insertConversation(ConversationEntity(id = conversationId))
      refreshConversations()
    }
  }

  fun loadConversation(id: String) {
    conversationId = id
    toolExecutor.lastLocationData = null
    viewModelScope.launch {
      val msgs = chatDao.getMessages(id).map {
        val uris = if (it.imagePaths.isNotBlank()) {
          it.imagePaths.split(",").mapNotNull { relPath ->
            val file = java.io.File(getApplication<android.app.Application>().filesDir, relPath.trim())
            if (file.exists()) android.net.Uri.fromFile(file).toString() else null
          }
        } else {
          emptyList()
        }
        ChatMessage(id = it.id, role = Role.from(it.role), content = it.content, imageUris = uris, timestamp = it.timestamp)
      }
      _messages.value = msgs
    }
  }

  fun shareConversation() {
    val msgs = _messages.value
    if (msgs.isEmpty()) return
    val text = msgs.joinToString("\n\n") { msg ->
      val prefix = when (msg.role) {
        Role.USER -> "User"
        Role.ASSISTANT -> "Assistant"
        else -> msg.role.value.replaceFirstChar { it.uppercase() }
      }
      "$prefix:\n${msg.content}"
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(android.content.Intent.EXTRA_TEXT, text)
      putExtra(android.content.Intent.EXTRA_SUBJECT, "AIOPE 2 Conversation")
    }
    val ctx = getApplication<android.app.Application>()
    ctx.startActivity(android.content.Intent.createChooser(intent, "Share conversation").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
  }
  fun deleteConversation(id: String) {
    viewModelScope.launch {
      chatDao.deleteConversation(id)
      if (id == conversationId) newConversation()
      refreshConversations()
    }
  }

  private suspend fun refreshConversations() {
    _conversations.value = chatDao.getConversations()
  }

  // LLM client — resolves task model, then creates client

  /** Resolve provider + model for a given task. Falls back to active profile. */
  private fun resolveTaskModel(task: com.aiope2.core.network.ModelTask): Pair<ProviderProfile, String> {
    val taskStore = com.aiope2.core.network.TaskModelStore(getApplication())
    val tc = taskStore.getTaskConfig(task)
    val profile = tc.profileId?.let { providerStore.getById(it) } ?: providerStore.getActive()
    val modelId = tc.modelId ?: profile.selectedModelId
    return profile to modelId
  }

  /** Translate a message using the TRANSLATION task model */
  fun translateMessage(messageId: String, language: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val msg = _messages.value.find { it.id == messageId } ?: return@launch
        val (profile, modelId) = resolveTaskModel(com.aiope2.core.network.ModelTask.TRANSLATION)
        val prompt = "Translate the following text to $language. Reply with ONLY the translation, nothing else:\n\n${msg.content}"
        val orchestrator = StreamingOrchestrator(
          baseUrl = profile.effectiveApiBase(),
          apiKey = profile.apiKey,
          model = modelId,
        )
        val sb = StringBuilder()
        orchestrator.stream(listOf("user" to prompt)).collect { chunk ->
          if (chunk.content.isNotEmpty()) {
            sb.append(chunk.content)
            val updated = _messages.value.toMutableList()
            val idx = updated.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
              updated[idx] = updated[idx].copy(translation = sb.toString())
              _messages.value = updated
            }
          }
        }
      } catch (e: Exception) {
        val updated = _messages.value.toMutableList()
        val idx = updated.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
          updated[idx] = updated[idx].copy(translation = "Translation error: ${e.message}")
          _messages.value = updated
        }
      }
    }
  }

  /** Generate a conversation title using the TITLE task model */
  private fun generateTitle(firstMessage: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val (profile, modelId) = resolveTaskModel(com.aiope2.core.network.ModelTask.TITLE)
        val prompt = "Generate a short title (max 6 words) for a conversation that starts with: \"${firstMessage.take(200)}\". Reply with ONLY the title, no quotes."
        val orchestrator = StreamingOrchestrator(
          baseUrl = profile.effectiveApiBase(),
          apiKey = profile.apiKey,
          model = modelId,
        )
        val sb = StringBuilder()
        orchestrator.stream(listOf("user" to prompt)).collect { chunk ->
          if (chunk.content.isNotEmpty()) sb.append(chunk.content)
        }
        val title = sb.toString().trim().take(60)
        if (title.isNotBlank()) {
          chatDao.updateConversation(conversationId, title)
          refreshConversations()
        }
      } catch (_: Exception) { /* silent failure for title gen */ }
    }
  }

  /** Save content:// URIs to disk as JPEG, return comma-separated relative paths */
  private fun saveImagesToDisk(msgId: String, uris: List<String>): String {
    if (uris.isEmpty()) return ""
    val dir = java.io.File(getApplication<android.app.Application>().filesDir, "chat_images")
    dir.mkdirs()
    return uris.mapIndexedNotNull { i, uriStr ->
      try {
        val uri = android.net.Uri.parse(uriStr)
        val input = getApplication<android.app.Application>().contentResolver.openInputStream(uri) ?: return@mapIndexedNotNull null
        val bytes = input.readBytes()
        input.close()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapIndexedNotNull null
        val file = java.io.File(dir, "${msgId}_$i.jpg")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        "chat_images/${msgId}_$i.jpg"
      } catch (_: Exception) {
        null
      }
    }.joinToString(",")
  }

  private var lastSendTime = 0L
  private var lastSendHash = 0

  fun send(text: String, imageUris: List<String> = emptyList()) {
    val now = System.currentTimeMillis()
    val hash = text.hashCode() xor imageUris.hashCode()
    if (now - lastSendTime < 500 && hash == lastSendHash) return
    lastSendTime = now
    lastSendHash = hash

    val userMsg = ChatMessage(role = Role.USER, content = text, imageUris = imageUris)
    _messages.value = _messages.value + userMsg

    cancelStreaming()
    streamingJob = viewModelScope.launch(Dispatchers.IO) {
      // Save images to disk
      val savedPaths = saveImagesToDisk(userMsg.id, imageUris)
      chatDao.insertMessage(
        MessageEntity(
          id = userMsg.id,
          conversationId = conversationId,
          role = userMsg.role.value,
          content = userMsg.content,
          imagePaths = savedPaths,
        ),
      )

      _isStreaming.value = true
      val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")
      _messages.value = _messages.value + assistantMsg

      val p = providerStore.getActive()
      val mc = p.activeModelConfig()
      toolExecutor.shellOutputLimit = mc.shellOutputLimit
      toolExecutor.fetchLimit = mc.fetchLimit
      toolExecutor.fileReadLimit = mc.fileReadLimit
      toolExecutor.locationUsedThisTurn = false

      try {
        val useTools = mc.toolsOverride != false // null=auto(send), true=send, false=dont send
        val sb = StringBuilder()
        val reasoningBlocks = mutableListOf<String>()
        val currentReasoning = StringBuilder()
        var isReasoning = false
        val toolCallsList = mutableListOf<String>()
        val toolResultsList = mutableListOf<String>()
        val toolErrorsList = mutableListOf<String>()

        val toolDefs = if (useTools) toolExecutor.buildToolDefs() else emptyList()

        // Build messages (trim to contextTokens limit using jtokkit)
        val chatMessages = buildSystemMessages(mc)
        val maxTokens = mc.contextTokens.toLong()
        var tokenCount = TokenCounter.countMessages(chatMessages, mc.modelId)
        val history = _messages.value.dropLast(1).reversed()
        val trimmed = mutableListOf<Pair<String, String>>()
        for (msg in history) {
          val msgTokens = TokenCounter.count(msg.content, mc.modelId) + 4
          if (tokenCount + msgTokens > maxTokens) break
          tokenCount += msgTokens
          val role = when (msg.role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.SYSTEM -> "system"
            else -> null
          }
          if (role != null) {
            var content = msg.content
            // Append tool call summaries so the model recalls what tools it ran
            if (role == "assistant" && msg.toolCalls.isNotEmpty()) {
              val toolSummary = msg.toolCalls.mapIndexed { i, call ->
                val result = msg.toolResults.getOrNull(i)?.take(500) ?: "(no result)"
                val name = call.substringBefore("(").substringBefore(" ").trim()
                "[$name → $result]"
              }.joinToString("\n")
              content = "[Tool calls:\n$toolSummary]\n$content"
            }
            trimmed.add(0, role to content)
          }
        }
        chatMessages.addAll(trimmed)
        chatMessages.add("user" to text)

        val isTaskModel = imageUris.isNotEmpty()
        val (sendProfile, sendModelId) = if (isTaskModel) resolveTaskModel(com.aiope2.core.network.ModelTask.IMAGE_RECOGNITION) else (p to p.selectedModelId)
        val sendMessages = if (isTaskModel) mutableListOf<Pair<String, String>>("user" to text) else chatMessages
        val sendTools = if (isTaskModel) emptyList() else toolDefs
        val orchestrator = StreamingOrchestrator(
          baseUrl = sendProfile.effectiveApiBase(),
          apiKey = sendProfile.apiKey,
          model = sendModelId,
          tools = sendTools,
          onToolCall = { name, args -> toolExecutor.execute(name, args) },
        )

        // Encode images to base64 — use saved disk paths (content URIs may expire)
        val filesDir = getApplication<android.app.Application>().filesDir
        val imageBase64s = savedPaths.split(",").filter { it.isNotBlank() }.mapNotNull { relPath ->
          try {
            val file = java.io.File(filesDir, relPath)
            if (!file.exists()) return@mapNotNull null
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
            val padded = padToSquare(bmp)
            val scaled = android.graphics.Bitmap.createScaledBitmap(padded, 448, 448, true)
            if (padded != bmp) padded.recycle()
            bmp.recycle()
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
          } catch (_: Exception) {
            null
          }
        }

        var lastUiLength = 0
        val charsPerLine = 55
        orchestrator.stream(sendMessages, imageBase64s).collect { chunk ->
          // Reasoning — accumulate into current block
          chunk.reasoning?.let { r ->
            if (!isReasoning) {
              isReasoning = true
              currentReasoning.clear()
            }
            currentReasoning.append(r)
          }

          // Text content — if we were reasoning, close that block
          if (chunk.content.isNotEmpty()) {
            if (isReasoning && currentReasoning.isNotEmpty()) {
              reasoningBlocks.add(currentReasoning.toString())
              currentReasoning.clear()
              isReasoning = false
            }
            sb.append(chunk.content)
          }

          // Tool calls — close any open reasoning block first
          chunk.toolCalls?.let { calls ->
            if (isReasoning && currentReasoning.isNotEmpty()) {
              reasoningBlocks.add(currentReasoning.toString())
              currentReasoning.clear()
              isReasoning = false
            }
            for (c in calls) toolCallsList.add("${c.name}(${c.arguments.values.firstOrNull()?.toString()?.take(80) ?: ""})")
          }

          chunk.toolResults?.let { results ->
            for (r in results) {
              toolResultsList.add(r.result.take(2000))
              if (r.result.startsWith("Error:") || r.result.startsWith("FAILED")) toolErrorsList.add("${r.name}: ${r.result.take(200)}")
            }
          }

          chunk.error?.let { sb.append("\nError: $it") }

          // Done — close any remaining reasoning block
          if (chunk.isDone && isReasoning && currentReasoning.isNotEmpty()) {
            reasoningBlocks.add(currentReasoning.toString())
            isReasoning = false
          }

          // Build current reasoning list (include in-progress block)
          val allReasoning = if (isReasoning && currentReasoning.isNotEmpty()) {
            reasoningBlocks + currentReasoning.toString()
          } else {
            reasoningBlocks.toList()
          }

          val currentLen = sb.length
          val hasNewLine = chunk.content.contains('\n')
          val lineWorth = currentLen - lastUiLength >= charsPerLine
          if (chunk.isDone || chunk.error != null || hasNewLine || lineWorth || chunk.toolCalls != null || chunk.toolResults != null || chunk.reasoning != null) {
            lastUiLength = currentLen
            withContext(Dispatchers.Main) {
              _messages.value = _messages.value.toMutableList().also {
                it[it.lastIndex] = it.last().copy(
                  content = sb.toString(),
                  reasoning = allReasoning,
                  isReasoningDone = !isReasoning,
                  toolCalls = toolCallsList.toList(),
                  toolResults = toolResultsList.toList(),
                  toolErrors = toolErrorsList.toList(),
                  locationData = if (toolExecutor.locationUsedThisTurn) toolExecutor.lastLocationData else null,
                )
              }
            }
          }
        }

        // Extract generated image URIs from content
        val generatedImages = Regex("""file:///[^\s)]+\.(png|jpg|jpeg|webp)""").findAll(sb.toString()).map { it.value }.toList()
        if (generatedImages.isNotEmpty()) {
          _messages.value = _messages.value.toMutableList().also {
            it[it.lastIndex] = it.last().copy(imageUris = generatedImages)
          }
        }

        // Persist final message
        val finalMsg = _messages.value.last()
        toolExecutor.locationUsedThisTurn = false // Don't carry map to next response
        android.util.Log.d("AIOPE2", "Final content len=${finalMsg.content.length} last100=${finalMsg.content.takeLast(100)}")
        val filesDirPath = getApplication<android.app.Application>().filesDir.absolutePath
        val genImagePaths = generatedImages.joinToString(",") { it.removePrefix("file://").removePrefix("$filesDirPath/") }
        chatDao.insertMessage(
          MessageEntity(
            id = finalMsg.id,
            conversationId = conversationId,
            role = Role.ASSISTANT.value,
            content = finalMsg.content,
            imagePaths = genImagePaths,
          ),
        )
        if (_messages.value.size <= 2) {
          chatDao.updateConversation(conversationId, text.take(50))
          // Auto-generate title using TITLE task model
          generateTitle(text)
        }
      } catch (_: kotlinx.coroutines.CancellationException) { /* stopped */ } catch (e: Exception) {
        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = "Error: ${e.message}")
        _messages.value = updated
      } finally {
        _isStreaming.value = false
        maybeAutoCompact(mc)
      }
    }
  }

  fun toggleTerminal() {
    _terminalVisible.value = !_terminalVisible.value
  }
  fun toggleBrowser() {
    _browserVisible.value = !_browserVisible.value
  }
  fun setBrowserVisible(v: Boolean) {
    _browserVisible.value = v
  }
  fun setBrowserMaximized(v: Boolean) {
    _browserMaximized.value = v
  }

  /** Edit & Resend: truncate messages after index, resend with new text */
  fun editAndResend(text: String, atIndex: Int) {
    truncateAt(atIndex)
    send(text)
  }

  fun truncateAt(atIndex: Int) {
    cancelStreaming()
    val cutTimestamp = if (atIndex < _messages.value.size) _messages.value[atIndex].timestamp else System.currentTimeMillis()
    _messages.value = _messages.value.take(atIndex)
    viewModelScope.launch { chatDao.deleteMessagesAfter(conversationId, cutTimestamp) }
  }

  /** Retry: remove last assistant message and re-run the last user message */
  fun retry(atIndex: Int) {
    val msgs = _messages.value.toMutableList()
    if (atIndex < msgs.size && msgs[atIndex].role == Role.ASSISTANT) {
      // Remove this message and everything after it
      val removedTimestamp = msgs[atIndex].timestamp
      while (msgs.size > atIndex) msgs.removeAt(msgs.lastIndex)
      _messages.value = msgs
      viewModelScope.launch { chatDao.deleteMessagesAfter(conversationId, removedTimestamp) }
      // Find the last user message (now the one right before atIndex)
      val lastUser = msgs.lastOrNull { it.role == Role.USER }
      if (lastUser != null) resend(lastUser.content)
    }
  }

  /** Compact: summarize messages 0..atIndex into a single context message */
  private fun maybeAutoCompact(mc: ModelConfig) {
    if (!mc.autoCompact) return
    val msgs = _messages.value
    val totalTokens = msgs.sumOf { TokenCounter.count(it.content, mc.modelId) }
    val threshold = mc.contextTokens * 95 / 100 // 95% of token limit
    if (totalTokens > threshold && msgs.size > 2) {
      // Compact first half of conversation
      compact(msgs.size / 2)
    }
  }

  fun compact(atIndex: Int) {
    val msgs = _messages.value
    if (atIndex < 1) return
    val toCompact = msgs.take(atIndex + 1)
    val transcript = toCompact.joinToString("\n") { "[${it.role.value}] ${it.content.take(2000)}" }
    val remaining = msgs.drop(atIndex + 1)

    cancelStreaming()
    streamingJob = viewModelScope.launch(Dispatchers.IO) {
      _isStreaming.value = true
      try {
        val (profile, modelId) = resolveTaskModel(com.aiope2.core.network.ModelTask.SUMMARY)
        val prompt = "Summarize this conversation concisely, preserving all key context needed to continue. Start with [Summary].\n\n$transcript"
        val orchestrator = StreamingOrchestrator(
          baseUrl = profile.effectiveApiBase(),
          apiKey = profile.apiKey,
          model = modelId,
        )
        val sb = StringBuilder()
        orchestrator.stream(listOf("user" to prompt)).collect { chunk ->
          if (chunk.content.isNotEmpty()) sb.append(chunk.content)
        }
        val summaryMsg = ChatMessage(role = Role.SYSTEM, content = sb.toString())
        val indicatorMsg = ChatMessage(role = Role.SYSTEM, content = "⟳ Context compacted — earlier messages summarized")
        _messages.value = listOf(summaryMsg, indicatorMsg) + remaining

        // Persist: delete old messages, save summary + indicator + remaining
        chatDao.deleteMessagesAfter(conversationId, 0) // delete all messages in this conversation
        chatDao.insertMessage(
          MessageEntity(
            id = summaryMsg.id,
            conversationId = conversationId,
            role = summaryMsg.role.value,
            content = summaryMsg.content,
          ),
        )
        chatDao.insertMessage(
          MessageEntity(
            id = indicatorMsg.id,
            conversationId = conversationId,
            role = indicatorMsg.role.value,
            content = indicatorMsg.content,
          ),
        )
        remaining.forEach { msg ->
          chatDao.insertMessage(
            MessageEntity(
              id = msg.id,
              conversationId = conversationId,
              role = msg.role.value,
              content = msg.content,
            ),
          )
        }
      } catch (_: kotlinx.coroutines.CancellationException) { /* stopped */ } catch (e: Exception) {
        // Don't lose messages on failure
      } finally {
        _isStreaming.value = false
      }
    }
  }

  /** Fork: create new conversation from messages 0..atIndex */
  fun fork(atIndex: Int) {
    val forkedMsgs = _messages.value.take(atIndex + 1)
    val newId = UUID.randomUUID().toString()
    viewModelScope.launch {
      chatDao.insertConversation(ConversationEntity(id = newId, title = "Fork: ${forkedMsgs.firstOrNull { it.role == Role.USER }?.content?.take(30) ?: "chat"}"))
      forkedMsgs.forEach { msg ->
        chatDao.insertMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = newId, role = msg.role.value, content = msg.content))
      }
      conversationId = newId
      _messages.value = forkedMsgs
      refreshConversations()
    }
  }

  /** Send to LLM without adding a new user message (used by retry) */
  private fun resend(text: String) {
    cancelStreaming()
    streamingJob = viewModelScope.launch(Dispatchers.IO) {
      _isStreaming.value = true
      val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")
      _messages.value = _messages.value + assistantMsg
      try {
        val p = providerStore.getActive()
        val mc = p.activeModelConfig()
        val useTools = mc.toolsOverride != false // null=auto(send), true=send, false=dont send
        val sb = StringBuilder()
        val reasoningBlocks = mutableListOf<String>()
        val currentReasoning = StringBuilder()
        var isReasoning = false
        val toolCallsList = mutableListOf<String>()
        val toolResultsList = mutableListOf<String>()
        val toolErrorsList = mutableListOf<String>()

        val chatMessages = buildSystemMessages(mc)
        _messages.value.dropLast(1).forEach { msg ->
          when (msg.role) {
            Role.USER -> chatMessages.add("user" to msg.content)

            Role.ASSISTANT -> {
              var content = msg.content
              if (msg.toolCalls.isNotEmpty()) {
                val toolSummary = msg.toolCalls.mapIndexed { i, call ->
                  val result = msg.toolResults.getOrNull(i)?.take(500) ?: "(no result)"
                  val name = call.substringBefore("(").substringBefore(" ").trim()
                  "[$name → $result]"
                }.joinToString("\n")
                content = "[Tool calls:\n$toolSummary]\n$content"
              }
              chatMessages.add("assistant" to content)
            }

            else -> {}
          }
        }

        val orchestrator = StreamingOrchestrator(
          baseUrl = p.effectiveApiBase(),
          apiKey = p.apiKey,
          model = p.selectedModelId,
          tools = if (useTools) toolExecutor.buildToolDefs() else emptyList(),
          onToolCall = { name, args -> toolExecutor.execute(name, args) },
        )

        orchestrator.stream(chatMessages).collect { chunk ->
          chunk.reasoning?.let {
            if (!isReasoning) {
              isReasoning = true
              currentReasoning.clear()
            }
            currentReasoning.append(it)
          }
          if (chunk.content.isNotEmpty()) {
            if (isReasoning && currentReasoning.isNotEmpty()) {
              reasoningBlocks.add(currentReasoning.toString())
              currentReasoning.clear()
              isReasoning = false
            }
            sb.append(chunk.content)
          }
          chunk.toolCalls?.let { calls ->
            if (isReasoning) {
              reasoningBlocks.add(currentReasoning.toString())
              currentReasoning.clear()
              isReasoning = false
            }
            for (c in calls) toolCallsList.add("${c.name}(${c.arguments.values.firstOrNull()?.toString()?.take(80) ?: ""})")
          }
          chunk.toolResults?.let { results ->
            for (r in results) {
              toolResultsList.add(r.result.take(2000))
              if (r.result.startsWith("Error:") || r.result.startsWith("FAILED")) toolErrorsList.add("${r.name}: ${r.result.take(200)}")
            }
          }
          chunk.error?.let { sb.append("\nError: $it") }
          if (chunk.isDone && isReasoning && currentReasoning.isNotEmpty()) {
            reasoningBlocks.add(currentReasoning.toString())
            isReasoning = false
          }
          val allReasoning = if (isReasoning && currentReasoning.isNotEmpty()) reasoningBlocks + currentReasoning.toString() else reasoningBlocks.toList()
          withContext(Dispatchers.Main) {
            _messages.value = _messages.value.toMutableList().also {
              it[it.lastIndex] = it.last().copy(content = sb.toString(), reasoning = allReasoning, isReasoningDone = !isReasoning, toolCalls = toolCallsList.toList(), toolResults = toolResultsList.toList(), toolErrors = toolErrorsList.toList(), locationData = if (toolExecutor.locationUsedThisTurn) toolExecutor.lastLocationData else null)
            }
          }
        }

        val resendImages = Regex("""file:///[^\s)]+\.(png|jpg|jpeg|webp)""").findAll(sb.toString()).map { it.value }.toList()
        if (resendImages.isNotEmpty()) {
          _messages.value = _messages.value.toMutableList().also { it[it.lastIndex] = it.last().copy(imageUris = resendImages) }
        }

        val finalMsg = _messages.value.last()
        chatDao.insertMessage(MessageEntity(id = finalMsg.id, conversationId = conversationId, role = Role.ASSISTANT.value, content = finalMsg.content))
      } catch (_: kotlinx.coroutines.CancellationException) { /* stopped */ } catch (e: Exception) {
        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = "Error: ${e.message}")
        _messages.value = updated
      } finally {
        _isStreaming.value = false
      }
    }
  }

  val mcpManager: McpManager by lazy { McpManager(toolStore).also { it.startHeartbeat() } }

  private val toolExecutor by lazy {
    ToolExecutor(
      app = getApplication(),
      providerStore = providerStore,
      toolStore = toolStore,
      chatDao = chatDao,
      mcpManager = mcpManager,
      locationProvider = com.aiope2.feature.chat.location.LocationProvider(getApplication()),
      getBrowser = { getBrowser() },
      onBrowserVisible = { setBrowserVisible(it) },
      onBrowserMaximized = { setBrowserMaximized(it) },
      resolveTaskModel = { resolveTaskModel(it) },
    )
  }

  private suspend fun buildSystemMessages(mc: com.aiope2.core.network.ModelConfig): MutableList<Pair<String, String>> {
    val msgs = mutableListOf<Pair<String, String>>()
    val prompt = com.aiope2.feature.chat.settings.buildAgentPrompt(chatDao)
    if (prompt.isNotBlank()) msgs.add("system" to prompt)
    return msgs
  }
  private fun getBrowser() = com.aiope2.feature.chat.browser.BrowserHolder.getOrCreate(getApplication())

  private fun padToSquare(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = bmp.width
    val h = bmp.height
    if (w == h) return bmp
    val size = maxOf(w, h)
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.drawBitmap(bmp, ((size - w) / 2f), ((size - h) / 2f), null)
    return out
  }
}
