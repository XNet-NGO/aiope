package com.aiope2.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.ConversationEntity
import com.aiope2.feature.chat.db.MessageEntity
import com.aiope2.feature.chat.engine.StreamingOrchestrator
import com.aiope2.feature.chat.settings.ProviderStore
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.ProviderTemplates
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
  val providerStore: ProviderStore
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
    viewModelScope.launch {
      chatDao.insertConversation(ConversationEntity(id = conversationId))
      refreshConversations()
    }
  }

  fun newConversation() {
    conversationId = UUID.randomUUID().toString()
    _messages.value = emptyList()
    viewModelScope.launch {
      chatDao.insertConversation(ConversationEntity(id = conversationId))
      refreshConversations()
    }
  }

  fun loadConversation(id: String) {
    conversationId = id
    viewModelScope.launch {
      val msgs = chatDao.getMessages(id).map {
        val uris = if (it.imagePaths.isNotBlank()) {
          it.imagePaths.split(",").mapNotNull { relPath ->
            val file = java.io.File(getApplication<android.app.Application>().filesDir, relPath.trim())
            if (file.exists()) android.net.Uri.fromFile(file).toString() else null
          }
        } else emptyList()
        ChatMessage(id = it.id, role = Role.from(it.role), content = it.content, imageUris = uris, timestamp = it.timestamp)
      }
      _messages.value = msgs
    }
  }


  fun shareConversation() {
    val msgs = _messages.value
    if (msgs.isEmpty()) return
    val text = msgs.joinToString("\n\n") { msg ->
      val prefix = when (msg.role) { Role.USER -> "User"; Role.ASSISTANT -> "Assistant"; else -> msg.role.value.replaceFirstChar { it.uppercase() } }
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

  /** Generate a conversation title using the TITLE task model */
  private fun generateTitle(firstMessage: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val (profile, modelId) = resolveTaskModel(com.aiope2.core.network.ModelTask.TITLE)
        val prompt = "Generate a short title (max 6 words) for a conversation that starts with: \"${firstMessage.take(200)}\". Reply with ONLY the title, no quotes."
        val orchestrator = StreamingOrchestrator(
          baseUrl = profile.effectiveApiBase(), apiKey = profile.apiKey, model = modelId
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
        val bytes = input.readBytes(); input.close()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapIndexedNotNull null
        val file = java.io.File(dir, "${msgId}_$i.jpg")
        java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        "chat_images/${msgId}_$i.jpg"
      } catch (_: Exception) { null }
    }.joinToString(",")
  }

  fun send(text: String, imageUris: List<String> = emptyList()) {
    val userMsg = ChatMessage(role = Role.USER, content = text, imageUris = imageUris)
    _messages.value = _messages.value + userMsg

    cancelStreaming(); streamingJob = viewModelScope.launch(Dispatchers.IO) {
      // Save images to disk
      val savedPaths = saveImagesToDisk(userMsg.id, imageUris)
      chatDao.insertMessage(MessageEntity(
        id = userMsg.id, conversationId = conversationId,
        role = userMsg.role.value, content = userMsg.content,
        imagePaths = savedPaths
      ))

      _isStreaming.value = true
      val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")
      _messages.value = _messages.value + assistantMsg

      val p = providerStore.getActive()
      val mc = p.activeModelConfig()
      try {
        val useTools = mc.toolsOverride != false  // null=auto(send), true=send, false=dont send
        val sb = StringBuilder()
        val reasoningBlocks = mutableListOf<String>()
        val currentReasoning = StringBuilder()
        var isReasoning = false
        val toolCallsList = mutableListOf<String>()
        val toolResultsList = mutableListOf<String>()
        

        val toolDefs = if (useTools) buildToolDefs() else emptyList()

        // Build messages (trim to contextTokens limit, ~4 chars/token)
        val chatMessages = mutableListOf<Pair<String, String>>()
        mc.systemPromptOverride?.let { if (it.isNotBlank()) chatMessages.add("system" to it) }
        val maxChars = mc.contextTokens * 4L
        var charCount = 0L
        val history = _messages.value.dropLast(1).reversed()
        val trimmed = mutableListOf<Pair<String, String>>()
        for (msg in history) {
          val len = msg.content.length
          if (charCount + len > maxChars) break
          charCount += len
          val role = when (msg.role) { Role.USER -> "user"; Role.ASSISTANT -> "assistant"; Role.SYSTEM -> "system"; else -> null }
          if (role != null) trimmed.add(0, role to msg.content)
        }
        chatMessages.addAll(trimmed)
        chatMessages.add("user" to text)

        val orchestrator = StreamingOrchestrator(
          baseUrl = p.effectiveApiBase(),
          apiKey = p.apiKey,
          model = p.selectedModelId,
          tools = toolDefs,
          onToolCall = { name, args -> executeToolCall(name, args) }
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
          } catch (_: Exception) { null }
        }

        var lastUiLength = 0
        val charsPerLine = 55
        orchestrator.stream(chatMessages, imageBase64s).collect { chunk ->
          // Reasoning — accumulate into current block
          chunk.reasoning?.let { r ->
            if (!isReasoning) { isReasoning = true; currentReasoning.clear() }
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
            for (r in results) toolResultsList.add(r.result.take(2000))
          }

          chunk.error?.let { sb.append("\nError: $it") }

          // Done — close any remaining reasoning block
          if (chunk.isDone && isReasoning && currentReasoning.isNotEmpty()) {
            reasoningBlocks.add(currentReasoning.toString())
            isReasoning = false
          }

          // Build current reasoning list (include in-progress block)
          val allReasoning = if (isReasoning && currentReasoning.isNotEmpty())
            reasoningBlocks + currentReasoning.toString()
          else reasoningBlocks.toList()

          val currentLen = sb.length
          val hasNewLine = chunk.content.contains('\n')
          val lineWorth = currentLen - lastUiLength >= charsPerLine
          if (chunk.isDone || chunk.error != null || hasNewLine || lineWorth || chunk.toolCalls != null || chunk.toolResults != null) {
            lastUiLength = currentLen
            withContext(Dispatchers.Main) {
            _messages.value = _messages.value.toMutableList().also {
              it[it.lastIndex] = it.last().copy(
                content = sb.toString(),
                reasoning = allReasoning,
                isReasoningDone = !isReasoning,
                toolCalls = toolCallsList.toList(),
                toolResults = toolResultsList.toList(), locationData = lastLocationData
              )
            }
          }
          }
        }

        // Persist final message
        val finalMsg = _messages.value.last()
        chatDao.insertMessage(MessageEntity(
          id = finalMsg.id, conversationId = conversationId,
          role = Role.ASSISTANT.value, content = finalMsg.content
        ))
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
      val removedMsg = msgs.removeAt(atIndex)
      _messages.value = msgs
      viewModelScope.launch { chatDao.deleteMessagesAfter(conversationId, removedMsg.timestamp) }
      val lastUser = msgs.lastOrNull { it.role == Role.USER }
      if (lastUser != null) resend(lastUser.content)
    }
  }

  /** Compact: summarize messages 0..atIndex into a single context message */
  private fun maybeAutoCompact(mc: ModelConfig) {
    if (!mc.autoCompact) return
    val msgs = _messages.value
    val totalChars = msgs.sumOf { it.content.length }
    val threshold = mc.contextTokens * 4L * 95 / 100  // 95% of token limit in chars
    if (totalChars > threshold && msgs.size > 2) {
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

    cancelStreaming(); streamingJob = viewModelScope.launch(Dispatchers.IO) {
      _isStreaming.value = true
      try {
        val (profile, modelId) = resolveTaskModel(com.aiope2.core.network.ModelTask.SUMMARY)
        val prompt = "Summarize this conversation concisely, preserving all key context needed to continue. Start with [Summary].\n\n$transcript"
        val orchestrator = StreamingOrchestrator(
          baseUrl = profile.effectiveApiBase(), apiKey = profile.apiKey, model = modelId
        )
        val sb = StringBuilder()
        orchestrator.stream(listOf("user" to prompt)).collect { chunk ->
          if (chunk.content.isNotEmpty()) sb.append(chunk.content)
        }
        val summaryMsg = ChatMessage(role = Role.SYSTEM, content = sb.toString())
        _messages.value = listOf(summaryMsg) + remaining

        // Persist: delete old messages, save summary + remaining
        chatDao.deleteMessagesAfter(conversationId, 0) // delete all messages in this conversation
        chatDao.insertMessage(MessageEntity(
          id = summaryMsg.id, conversationId = conversationId,
          role = summaryMsg.role.value, content = summaryMsg.content
        ))
        remaining.forEach { msg ->
          chatDao.insertMessage(MessageEntity(
            id = msg.id, conversationId = conversationId,
            role = msg.role.value, content = msg.content
          ))
        }
      } catch (_: kotlinx.coroutines.CancellationException) { /* stopped */ } catch (e: Exception) {
        // Don't lose messages on failure
      } finally { _isStreaming.value = false }
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
    cancelStreaming(); streamingJob = viewModelScope.launch(Dispatchers.IO) {
      _isStreaming.value = true
      val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")
      _messages.value = _messages.value + assistantMsg
      try {
        val p = providerStore.getActive()
        val mc = p.activeModelConfig()
        val useTools = mc.toolsOverride != false  // null=auto(send), true=send, false=dont send
        val sb = StringBuilder()
        val reasoningBlocks = mutableListOf<String>()
        val currentReasoning = StringBuilder()
        var isReasoning = false
        val toolCallsList = mutableListOf<String>()
        val toolResultsList = mutableListOf<String>()
        

        val chatMessages = mutableListOf<Pair<String, String>>()
        mc.systemPromptOverride?.let { if (it.isNotBlank()) chatMessages.add("system" to it) }
        _messages.value.dropLast(1).forEach { msg ->
          when (msg.role) {
            Role.USER -> chatMessages.add("user" to msg.content)
            Role.ASSISTANT -> chatMessages.add("assistant" to msg.content)
            else -> {}
          }
        }

        val orchestrator = StreamingOrchestrator(
          baseUrl = p.effectiveApiBase(), apiKey = p.apiKey, model = p.selectedModelId,
          tools = if (useTools) buildToolDefs() else emptyList(),
          onToolCall = { name, args -> executeToolCall(name, args) }
        )

        orchestrator.stream(chatMessages).collect { chunk ->
          chunk.reasoning?.let { if (!isReasoning) { isReasoning = true; currentReasoning.clear() }; currentReasoning.append(it) }
          if (chunk.content.isNotEmpty()) {
            if (isReasoning && currentReasoning.isNotEmpty()) { reasoningBlocks.add(currentReasoning.toString()); currentReasoning.clear(); isReasoning = false }
            sb.append(chunk.content)
          }
          chunk.toolCalls?.let { calls -> if (isReasoning) { reasoningBlocks.add(currentReasoning.toString()); currentReasoning.clear(); isReasoning = false }; for (c in calls) toolCallsList.add("${c.name}(${c.arguments.values.firstOrNull()?.toString()?.take(80) ?: ""})") }
          chunk.toolResults?.let { results -> for (r in results) toolResultsList.add(r.result.take(2000)) }
          chunk.error?.let { sb.append("\nError: $it") }
          if (chunk.isDone && isReasoning && currentReasoning.isNotEmpty()) { reasoningBlocks.add(currentReasoning.toString()); isReasoning = false }
          val allReasoning = if (isReasoning && currentReasoning.isNotEmpty()) reasoningBlocks + currentReasoning.toString() else reasoningBlocks.toList()
          withContext(Dispatchers.Main) {
            _messages.value = _messages.value.toMutableList().also {
              it[it.lastIndex] = it.last().copy(content = sb.toString(), reasoning = allReasoning, isReasoningDone = !isReasoning, toolCalls = toolCallsList.toList(), toolResults = toolResultsList.toList(), locationData = lastLocationData)
            }
          }
        }

        val finalMsg = _messages.value.last()
        chatDao.insertMessage(MessageEntity(id = finalMsg.id, conversationId = conversationId, role = Role.ASSISTANT.value, content = finalMsg.content))
      } catch (_: kotlinx.coroutines.CancellationException) { /* stopped */ } catch (e: Exception) {
        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = "Error: ${e.message}")
        _messages.value = updated
      } finally { _isStreaming.value = false }
    }
  }

  private fun buildToolDefs() = listOf(
    StreamingOrchestrator.ToolDef("run_sh", "Execute Android shell command", org.json.JSONObject("""{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}""")),
    StreamingOrchestrator.ToolDef("run_proot", "Execute a command in the Ubuntu proot Linux environment. Use for apt, python, gcc, etc.", org.json.JSONObject("""{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}""")),
    StreamingOrchestrator.ToolDef("read_file", "Read file contents", org.json.JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),
    StreamingOrchestrator.ToolDef("write_file", "Write file", org.json.JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")),
    StreamingOrchestrator.ToolDef("list_directory", "List directory", org.json.JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),
    StreamingOrchestrator.ToolDef("get_location", "Get the device's current GPS location. Call this FIRST when the user asks about nearby places or 'closest' anything, then use the coordinates with search_location.", org.json.JSONObject("""{"type":"object","properties":{}}""")),
    StreamingOrchestrator.ToolDef("search_location", "Search for any place, address, landmark, or business/amenity. For nearby searches ('closest pizza'), call get_location first to establish position. Handles addresses, landmarks, cities, and business/amenity searches (restaurants, cafes, gas stations, etc).", org.json.JSONObject("""{"type":"object","properties":{"query":{"type":"string","description":"What to search for. Examples: '1600 Pennsylvania Ave, Washington DC', 'Eiffel Tower', 'pizza in Boise, ID', 'Starbucks near Meridian, Idaho', 'gas station'"}},"required":["query"]}"""))
  )

  private val locationProvider by lazy { com.aiope2.feature.chat.location.LocationProvider(getApplication()) }
  private var lastLocationData: LocationData? = null

  private fun executeToolCall(name: String, args: Map<String, Any?>): String = when (name) {
    "run_sh" -> com.aiope2.core.terminal.shell.ShellExecutor.exec(args["command"]?.toString() ?: "").let { if (it.length > 4000) it.take(4000) + "\n...(truncated)" else it }
    "run_proot" -> {
      val ctx = getApplication<android.app.Application>()
      if (!com.aiope2.core.terminal.shell.ProotBootstrap.isInstalled(ctx)) "Ubuntu not installed. Set up proot in Settings first."
      else com.aiope2.core.terminal.shell.ProotExecutor.exec(ctx, args["command"]?.toString() ?: "").let { if (it.length > 4000) it.take(4000) + "\n...(truncated)" else it }
    }
    "read_file" -> try { java.io.File(args["path"].toString()).readText().let { if (it.length > 50000) "File too large" else it } } catch (e: Exception) { "Error: ${e.message}" }
    "write_file" -> try { val f = java.io.File(args["path"].toString()); f.parentFile?.mkdirs(); f.writeText(args["content"].toString()); "Written ${args["content"].toString().length} bytes" } catch (e: Exception) { "Error: ${e.message}" }
    "list_directory" -> try { java.io.File(args["path"].toString()).listFiles()?.joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.name}" } ?: "Empty" } catch (e: Exception) { "Error: ${e.message}" }
    "get_location" -> kotlinx.coroutines.runBlocking {
      val loc = locationProvider.getFreshLocation() ?: locationProvider.getLastLocation()
      if (loc != null) {
        lastLocationData = LocationData(
          latitude = loc.latitude, longitude = loc.longitude,
          altitude = if (loc.hasAltitude()) loc.altitude else null,
          speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
          bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
          accuracy = loc.accuracy.toDouble()
        )
        val base = locationProvider.formatLocation(loc)
        val address = locationProvider.reverseGeocode(loc)
        if (address != null) "$base\n$address" else base
      } else "Location unavailable -- check permissions or GPS"
    }
    "search_location" -> {
      val query = args["query"]?.toString() ?: ""
      val q = query.lowercase()
      // Skip Geocoder for business/amenity/brand queries — go straight to Overpass
      val businessTerms = listOf("near", "closest", "nearest", "nearby",
        "restaurant", "food", "eat", "coffee", "cafe", "pizza", "burger",
        "gas", "fuel", "pharmacy", "hotel", "motel", "grocery", "supermarket",
        "bar", "pub", "gym", "bank", "atm", "parking", "hospital",
        "mcdonald", "starbucks", "walmart", "target", "costco", "wendy",
        "subway", "taco bell", "burger king", "chick-fil", "dunkin")
      val isBusinessQuery = businessTerms.any { q.contains(it) }
      try {
        if (!isBusinessQuery) {
          val geocoder = android.location.Geocoder(getApplication(), java.util.Locale.US)
          val geoResults = geocoder.getFromLocationName(query, 5)
          if (!geoResults.isNullOrEmpty()) {
            val first = geoResults[0]
            lastLocationData = LocationData(latitude = first.latitude, longitude = first.longitude)
            geoResults.mapIndexed { i, addr ->
              val line = addr.getAddressLine(0) ?: "${addr.locality ?: ""}, ${addr.adminArea ?: ""}, ${addr.countryName ?: ""}"
              "${i + 1}. $line\n   Lat: ${addr.latitude}, Lng: ${addr.longitude}"
            }.joinToString("\n")
          } else searchPlaces(query)
        } else searchPlaces(query)
      } catch (e: Exception) {
        try { searchPlaces(query) } catch (e2: Exception) { "Error: ${e2.message}" }
      }
    }
    else -> "Unknown tool: $name"
  }

  private fun searchPlaces(query: String): String {
    val apiKey = providerStore.getGeoapifyKey()
    if (apiKey.isBlank()) return "Geoapify API key not set. Add it in Settings to enable place search."

    var lat = lastLocationData?.latitude
    var lng = lastLocationData?.longitude
    // Auto-fetch location if not set
    if (lat == null || lng == null) {
      val loc = kotlinx.coroutines.runBlocking { locationProvider.getFreshLocation() ?: locationProvider.getLastLocation() }
      if (loc != null) {
        lastLocationData = LocationData(latitude = loc.latitude, longitude = loc.longitude)
        lat = loc.latitude; lng = loc.longitude
      } else return "Location unavailable. Enable GPS and try again."
    }

    val q = query.trim().replace(Regex("\\s*(near|in|around|close to|closest to|nearest to)\\s+.*$", RegexOption.IGNORE_CASE), "").trim()
    val encoded = java.net.URLEncoder.encode(q, "UTF-8")
    val url = "https://api.geoapify.com/v2/places?categories=commercial,catering,service,entertainment,leisure,sport,tourism,accommodation,education,healthcare&conditions=named&filter=circle:$lng,$lat,5000&bias=proximity:$lng,$lat&limit=5&name=$encoded&apiKey=$apiKey"

    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    if (conn.responseCode !in 200..299) {
      // Fallback: try text search endpoint
      val url2 = "https://api.geoapify.com/v1/geocode/search?text=${java.net.URLEncoder.encode(query, "UTF-8")}&bias=proximity:$lng,$lat&limit=5&apiKey=$apiKey"
      val conn2 = java.net.URL(url2).openConnection() as java.net.HttpURLConnection
      conn2.connectTimeout = 15000
      conn2.readTimeout = 15000
      if (conn2.responseCode !in 200..299) return "Search error: HTTP ${conn2.responseCode}"
      return parseGeoapifyResults(conn2.inputStream.bufferedReader(Charsets.UTF_8).readText(), query)
    }
    return parseGeoapifyResults(conn.inputStream.bufferedReader(Charsets.UTF_8).readText(), query)
  }

  private fun parseGeoapifyResults(body: String, query: String): String {
    val json = org.json.JSONObject(body)
    val features = json.optJSONArray("features")
    if (features == null || features.length() == 0) return "No results found for: $query"

    val userLat = lastLocationData?.latitude
    val userLng = lastLocationData?.longitude

    val results = (0 until minOf(features.length(), 5)).map { i ->
      val props = features.getJSONObject(i).getJSONObject("properties")
      val name = props.optString("name", "").ifBlank { props.optString("formatted", "Unnamed") }
      val addr = props.optString("formatted", "").ifBlank { null }
      val phone = props.optString("contact:phone", "").ifBlank { props.optString("phone", "").ifBlank { null } }
      val hours = props.optString("opening_hours", "").ifBlank { null }
      val pLat = props.optDouble("lat", 0.0)
      val pLng = props.optDouble("lon", 0.0)
      val dist = if (userLat != null && userLng != null) haversineKm(userLat, userLng, pLat, pLng) else null
      buildString {
        append("${i + 1}. $name")
        dist?.let { append(" (${"%.1f".format(it)} km)") }
        if (addr != null && addr != name) append("\n   Address: $addr")
        phone?.let { append("\n   Phone: $it") }
        hours?.let { append("\n   Hours: $it") }
        append("\n   Lat: $pLat, Lng: $pLng")
      }
    }

    val firstProps = features.getJSONObject(0).getJSONObject("properties")
    lastLocationData = LocationData(latitude = firstProps.optDouble("lat", 0.0), longitude = firstProps.optDouble("lon", 0.0))
    return results.joinToString("\n")
  }

  private fun padToSquare(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = bmp.width; val h = bmp.height
    if (w == h) return bmp
    val size = maxOf(w, h)
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.drawBitmap(bmp, ((size - w) / 2f), ((size - h) / 2f), null)
    return out
  }

  private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  }
}
