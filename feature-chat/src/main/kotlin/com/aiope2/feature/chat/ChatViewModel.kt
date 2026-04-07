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
import com.aiope2.feature.chat.agent.AiopeTools
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.db.ConversationEntity
import com.aiope2.feature.chat.db.MessageEntity
import com.aiope2.feature.chat.settings.ProviderStore
import com.aiope2.core.network.ModelDef
import com.aiope2.core.network.ModelConfig
import com.aiope2.core.network.ProviderTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

  val modelLabel: String get() {
    val p = providerStore.getActive()
    val id = p.selectedModelId.substringAfterLast('/')
    return id.ifBlank { p.label.ifBlank { "No model" } }
  }

  fun switchModel(modelId: String) {
    val p = providerStore.getActive()
    providerStore.save(p.copy(selectedModelId = modelId))
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
  private fun createClient(task: com.aiope2.core.network.ModelTask = com.aiope2.core.network.ModelTask.CHAT): Triple<SingleLLMPromptExecutor, LLModel, Boolean> {
    val taskStore = com.aiope2.core.network.TaskModelStore(getApplication())
    val tc = taskStore.getTaskConfig(task)

    // Resolve profile: task override → active profile
    val p = tc.profileId?.let { providerStore.getById(it) } ?: providerStore.getActive()
    // Resolve model: task override → profile's selected model
    val modelId = tc.modelId ?: p.selectedModelId
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
    val client = OpenAILLMClient(apiKey = p.apiKey.ifBlank { "unused" }, settings = settings)
    val model = LLModel(
      LLMProvider.OpenAI, modelId,
      listOf(
        ai.koog.prompt.llm.LLMCapability.Completion,
        ai.koog.prompt.llm.LLMCapability.Tools,
        ai.koog.prompt.llm.LLMCapability.Temperature,
        ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions,
      )
    )
    val toolsEnabled = mc.toolsOverride != false
    return Triple(SingleLLMPromptExecutor(client), model, toolsEnabled)
  }
  private val tools = AiopeTools(application)

  private fun getSystemPrompt(): String {
    val p = providerStore.getActive()
    val mc = p.activeModelConfig()
    return mc.systemPromptOverride
      ?: "You are AIOPE, an AI coding assistant on Android. Use tools when asked to run commands or manage files. Be concise."
  }

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
        val (executor, model, toolsEnabled) = createClient(com.aiope2.core.network.ModelTask.CHAT)
        val agent = AIAgent(
          promptExecutor = executor,
          systemPrompt = getSystemPrompt(),
          llmModel = model,
          toolRegistry = if (toolsEnabled) ToolRegistry { tools(this@ChatViewModel.tools) } else ToolRegistry { }
        )

        val result = agent.run(text)

        val updated = _messages.value.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(content = result)
        _messages.value = updated

        // Persist
        chatDao.insertMessage(MessageEntity(
          id = updated.last().id, conversationId = conversationId,
          role = Role.ASSISTANT.value, content = result
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
        val (executor, model, toolsEnabled) = createClient(com.aiope2.core.network.ModelTask.SUMMARY)
        val agent = AIAgent(
          promptExecutor = executor,
          systemPrompt = "Summarize this conversation concisely, preserving all key context needed to continue. Start with [Summary].",
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
        val (executor, model, toolsEnabled) = createClient(com.aiope2.core.network.ModelTask.CHAT)
        val agent = AIAgent(
          promptExecutor = executor, systemPrompt = getSystemPrompt(),
          llmModel = model, toolRegistry = if (toolsEnabled) ToolRegistry { tools(this@ChatViewModel.tools) } else ToolRegistry { }
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
