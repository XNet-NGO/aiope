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
  private val chatDao: ChatDao
) : AndroidViewModel(application) {

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages = _messages.asStateFlow()

  private val _isStreaming = MutableStateFlow(false)
  val isStreaming = _isStreaming.asStateFlow()

  private val _terminalVisible = MutableStateFlow(false)
  val terminalVisible = _terminalVisible.asStateFlow()

  private var conversationId = UUID.randomUUID().toString()

  // Koog agent with Pollinations (OpenAI-compatible, free)
  private val client = OpenAILLMClient(
    apiKey = "unused",
    settings = OpenAIClientSettings("https://text.pollinations.ai/v1")
  )
  private val executor = SingleLLMPromptExecutor(client)
  private val tools = AiopeTools(application)
  private val model = LLModel(LLMProvider.OpenAI, "openai-fast")

  private val systemPrompt = """You are AIOPE, an AI coding assistant running on an Android device.
You have tools: run_sh (execute shell commands), read_file, write_file, list_directory.
Use tools when the user asks you to run commands, read/write files, or explore the filesystem.
Be concise. Show command output directly."""

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
        val agent = AIAgent(
          promptExecutor = executor,
          systemPrompt = systemPrompt,
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
