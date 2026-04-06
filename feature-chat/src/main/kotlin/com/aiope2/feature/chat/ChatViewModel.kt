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
      ?: ProviderTemplates.byId[p.builtinId]?.defaultModels
      ?: emptyList()
  }

  // LLM client — reads from ProviderStore, recreated per send
  private fun createClient(): Pair<SingleLLMPromptExecutor, LLModel> {
    val p = providerStore.getActive()
    val baseUrl = p.effectiveApiBase().trimEnd('/')
    val client = OpenAILLMClient(
      apiKey = p.apiKey.ifBlank { "unused" },
      settings = OpenAIClientSettings(baseUrl)
    )
    val model = LLModel(
      LLMProvider.OpenAI, p.effectiveModel(),
      listOf(
        ai.koog.prompt.llm.LLMCapability.Completion,
        ai.koog.prompt.llm.LLMCapability.Tools,
        ai.koog.prompt.llm.LLMCapability.Temperature,
        ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions,
      )
    )
    return SingleLLMPromptExecutor(client) to model
  }
  private val tools = AiopeTools(application)

  private fun getSystemPrompt(): String {
    val p = providerStore.getActive()
    val override = p.effectiveSystemPrompt()
    return override ?: "You are AIOPE, an AI coding assistant on Android. Use tools when asked to run commands or manage files. Be concise."
  }

  init {
    viewModelScope.launch {
      chatDao.insertConversation(ConversationEntity(id = conversationId))
    }
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
        val (executor, model) = createClient()
        val agent = AIAgent(
          promptExecutor = executor,
          systemPrompt = getSystemPrompt(),
          llmModel = model,
          toolRegistry = ToolRegistry { tools(this@ChatViewModel.tools) }
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
}
