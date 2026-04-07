package com.aiope2.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.streaming.StreamFrame
import com.aiope2.feature.chat.agent.AiopeTools
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
        ChatMessage(id = it.id, role = Role.from(it.role), content = it.content, timestamp = it.timestamp)
      }
      _messages.value = msgs
    }
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
  private fun createClient(task: com.aiope2.core.network.ModelTask = com.aiope2.core.network.ModelTask.CHAT): Pair<SingleLLMPromptExecutor, LLModel> {
    val taskStore = com.aiope2.core.network.TaskModelStore(getApplication())
    val tc = taskStore.getTaskConfig(task)

    // Chat task: always use active profile + its selected model (set by toolbar dropdown)
    // Other tasks: resolve from TaskModelStore override → active profile fallback
    val p: ProviderProfile
    val modelId: String
    if (task == com.aiope2.core.network.ModelTask.CHAT) {
      p = providerStore.getActive()
      modelId = p.selectedModelId
    } else {
      val tc2 = taskStore.getTaskConfig(task)
      p = tc2.profileId?.let { providerStore.getById(it) } ?: providerStore.getActive()
      modelId = tc2.modelId ?: p.selectedModelId
    }
    android.util.Log.d("AIOPE2", "Task=${task.id} resolved=${p.label}/$modelId")
    val mc = p.modelConfigs[modelId] ?: ModelConfig(modelId = modelId)

    var baseUrl = p.effectiveApiBase().trimEnd('/')
    val eo = mc.endpointOverride.trim().removePrefix("/")
    val chatPath = if (eo.isNotBlank()) eo
      else if (baseUrl.endsWith("/openai")) "chat/completions"
      else if (baseUrl.endsWith("/v1")) { baseUrl = baseUrl.removeSuffix("/v1"); "v1/chat/completions" }
      else "v1/chat/completions"
    val settings = OpenAIClientSettings(
      baseUrl,
      ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(),
      chatPath, "v1/responses", "v1/embeddings", "v1/moderations", "v1/models"
    )

    val toolsEnabled = mc.toolsOverride == true  // null/false = no tools, true = tools on
    val stripSystemPrompt = mc.systemPromptOverride.isNullOrBlank()
    val stripTools = !toolsEnabled

    // OkHttp interceptor strips unsupported fields before they reach the API
    val ktorClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
      engine {
        addInterceptor { chain ->
          val req = chain.request()
          if (req.method == "POST" && req.body != null) {
            val buf = okio.Buffer(); req.body!!.writeTo(buf)
            try {
              val obj = org.json.JSONObject(buf.readUtf8())
              if (stripTools) { obj.remove("tools"); obj.remove("tool_choice") }
              if (stripSystemPrompt) {
                val msgs = obj.optJSONArray("messages")
                if (msgs != null && msgs.length() > 0 &&
                    msgs.getJSONObject(0).optString("role").let { it == "system" || it == "developer" }) {
                  msgs.remove(0)
                }
              }
              // Add reasoning_effort if set
              mc.reasoningEffort?.let { obj.put("reasoning_effort", it) }
              val body = okhttp3.RequestBody.create(req.body!!.contentType(), obj.toString())
              chain.proceed(req.newBuilder().method(req.method, body).build())
            } catch (_: Exception) { chain.proceed(req) }
          } else chain.proceed(req)
        }
      }
    }

    val client = OpenAILLMClient(p.apiKey.ifBlank { "unused" }, settings, ktorClient)
    val model = LLModel(LLMProvider.OpenAI, modelId, listOf(
      ai.koog.prompt.llm.LLMCapability.Completion,
      ai.koog.prompt.llm.LLMCapability.Tools,
      ai.koog.prompt.llm.LLMCapability.Temperature,
      ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions,
    ))
    return Pair(SingleLLMPromptExecutor(client), model)
  }

  private val tools = AiopeTools(application)

  fun send(text: String) {
    val userMsg = ChatMessage(role = Role.USER, content = text)
    _messages.value = _messages.value + userMsg

    viewModelScope.launch(Dispatchers.IO) {
      chatDao.insertMessage(MessageEntity(
        id = userMsg.id, conversationId = conversationId,
        role = userMsg.role.value, content = userMsg.content
      ))

      _isStreaming.value = true
      val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")
      _messages.value = _messages.value + assistantMsg

      try {
        val (executor, model) = createClient(com.aiope2.core.network.ModelTask.CHAT)
        val p = providerStore.getActive()
        val mc = p.activeModelConfig()
        val useTools = mc.toolsOverride == true
        val sb = StringBuilder()
        val reasoningSb = StringBuilder()
        val toolCallsList = mutableListOf<String>()
        val toolResultsList = mutableListOf<String>()

        // Build tool definitions
        val toolDefs = if (useTools) listOf(
          StreamingOrchestrator.ToolDef("run_sh", "Execute Android shell command", org.json.JSONObject("""{"type":"object","properties":{"command":{"type":"string","description":"Shell command"}},"required":["command"]}""")),
          StreamingOrchestrator.ToolDef("read_file", "Read file contents", org.json.JSONObject("""{"type":"object","properties":{"path":{"type":"string","description":"File path"}},"required":["path"]}""")),
          StreamingOrchestrator.ToolDef("write_file", "Write file", org.json.JSONObject("""{"type":"object","properties":{"path":{"type":"string","description":"File path"},"content":{"type":"string","description":"Content"}},"required":["path","content"]}""")),
          StreamingOrchestrator.ToolDef("list_directory", "List directory", org.json.JSONObject("""{"type":"object","properties":{"path":{"type":"string","description":"Directory path"}},"required":["path"]}"""))
        ) else emptyList()

        // Build messages
        val chatMessages = mutableListOf<Pair<String, String>>()
        mc.systemPromptOverride?.let { if (it.isNotBlank()) chatMessages.add("system" to it) }
        _messages.value.dropLast(1).forEach { msg ->
          when (msg.role) {
            Role.USER -> chatMessages.add("user" to msg.content)
            Role.ASSISTANT -> chatMessages.add("assistant" to msg.content)
            Role.SYSTEM -> chatMessages.add("system" to msg.content)
            else -> {}
          }
        }
        chatMessages.add("user" to text)

        val orchestrator = StreamingOrchestrator(
          baseUrl = p.effectiveApiBase(),
          apiKey = p.apiKey,
          model = p.selectedModelId,
          tools = toolDefs,
          onToolCall = { name, args ->
            when (name) {
              "run_sh" -> com.aiope2.core.terminal.shell.ShellExecutor.exec(args["command"]?.toString() ?: "").let { if (it.length > 4000) it.take(4000) + "\n...(truncated)" else it }
              "read_file" -> try { java.io.File(args["path"].toString()).readText().let { if (it.length > 50000) "File too large" else it } } catch (e: Exception) { "Error: ${e.message}" }
              "write_file" -> try { val f = java.io.File(args["path"].toString()); f.parentFile?.mkdirs(); f.writeText(args["content"].toString()); "Written ${args["content"].toString().length} bytes" } catch (e: Exception) { "Error: ${e.message}" }
              "list_directory" -> try { java.io.File(args["path"].toString()).listFiles()?.joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.name}" } ?: "Empty" } catch (e: Exception) { "Error: ${e.message}" }
              else -> "Unknown tool: $name"
            }
          }
        )

        orchestrator.stream(chatMessages).collect { chunk ->
          chunk.reasoning?.let { reasoningSb.append(it) }
          if (chunk.content.isNotEmpty()) sb.append(chunk.content)
          chunk.toolCalls?.let { calls ->
            for (c in calls) toolCallsList.add("${c.name}(${c.arguments.values.firstOrNull()?.toString()?.take(80) ?: ""})")
          }
          chunk.toolResults?.let { results ->
            for (r in results) toolResultsList.add(r.result.take(2000))
          }
          chunk.error?.let { sb.append("\n❌ $it") }

          withContext(Dispatchers.Main) {
            _messages.value = _messages.value.toMutableList().also {
              it[it.lastIndex] = it.last().copy(
                content = sb.toString(),
                reasoning = reasoningSb.toString(),
                toolCalls = toolCallsList.toList(),
                toolResults = toolResultsList.toList()
              )
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
        }
      } catch (e: Exception) {
        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = "Error: ${e.message}")
        _messages.value = updated
      } finally {
        _isStreaming.value = false
      }
    }
  }

  fun toggleTerminal() {
    _terminalVisible.value = !_terminalVisible.value
  }

  /** Edit & Resend: truncate messages after index, resend with new text */
  fun editAndResend(text: String, atIndex: Int) {
    // Keep messages up to (not including) the edited message
    _messages.value = _messages.value.take(atIndex)
    send(text)
  }

  /** Retry: remove last assistant message and re-run the last user message */
  fun retry(atIndex: Int) {
    val msgs = _messages.value.toMutableList()
    if (atIndex < msgs.size && msgs[atIndex].role == Role.ASSISTANT) {
      msgs.removeAt(atIndex)
      _messages.value = msgs
      val lastUser = msgs.lastOrNull { it.role == Role.USER }
      if (lastUser != null) resend(lastUser.content)
    }
  }

  /** Compact: summarize messages 0..atIndex into a single context message */
  fun compact(atIndex: Int) {
    val msgs = _messages.value
    if (atIndex < 1) return
    val toCompact = msgs.take(atIndex + 1)
    val transcript = toCompact.joinToString("\n") { "[${it.role.value}] ${it.content.take(2000)}" }
    val remaining = msgs.drop(atIndex + 1)

    viewModelScope.launch(Dispatchers.IO) {
      _isStreaming.value = true
      try {
        val (executor, model) = createClient(com.aiope2.core.network.ModelTask.SUMMARY)
        val agent = AIAgent(
          promptExecutor = executor,
          systemPrompt = "",
          llmModel = model, toolRegistry = ToolRegistry { }
        )
        val summary = agent.run(transcript)
        val summaryMsg = ChatMessage(role = Role.SYSTEM, content = summary)
        _messages.value = listOf(summaryMsg) + remaining
      } catch (e: Exception) {
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
    viewModelScope.launch(Dispatchers.IO) {
      _isStreaming.value = true
      val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")
      _messages.value = _messages.value + assistantMsg
      try {
        val (executor, model) = createClient(com.aiope2.core.network.ModelTask.CHAT)
        val agent = AIAgent(
          promptExecutor = executor,
          systemPrompt = "",
          llmModel = model, toolRegistry = ToolRegistry { tools(this@ChatViewModel.tools) }
        )
        val result = agent.run(text)
        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = result)
        _messages.value = updated
        chatDao.insertMessage(MessageEntity(
          id = updated.last().id, conversationId = conversationId,
          role = Role.ASSISTANT.value, content = result
        ))
      } catch (e: Exception) {
        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = "Error: ${e.message}")
        _messages.value = updated
      } finally { _isStreaming.value = false }
    }
  }
}
