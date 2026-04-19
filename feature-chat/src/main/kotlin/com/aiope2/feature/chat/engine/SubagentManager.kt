package com.aiope2.feature.chat.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class SubagentManager(
  private val createOrchestrator: (List<StreamingOrchestrator.ToolDef>, suspend (String, Map<String, Any?>) -> String) -> StreamingOrchestrator,
  private val buildMessages: (String) -> List<Pair<String, String>>,
  private val getReadOnlyTools: () -> List<StreamingOrchestrator.ToolDef>,
  private val executeReadOnlyTool: suspend (String, Map<String, Any?>) -> String,
) {
  enum class Stage { SEARCHING, READING, SUMMARIZING, FINISHED, ERROR }

  data class SubagentTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val description: String,
    val stage: Stage = Stage.SEARCHING,
    val result: String = "",
    val error: String? = null,
  )

  private val _tasks = MutableStateFlow<List<SubagentTask>>(emptyList())
  val tasks: StateFlow<List<SubagentTask>> = _tasks

  /** Blocking: runs subagent to completion, returns result string for tool output */
  suspend fun runBlocking(description: String, prompt: String): String {
    val task = SubagentTask(description = description)
    _tasks.value = _tasks.value + task

    return try {
      updateStage(task.id, Stage.SEARCHING)
      val messages = buildMessages(prompt)
      val tools = getReadOnlyTools()

      updateStage(task.id, Stage.READING)
      val sb = StringBuilder()
      val orchestrator = createOrchestrator(tools) { name, args ->
        when (name) {
          "search_web", "search_images", "search_location" -> updateStage(task.id, Stage.SEARCHING)
          "fetch_url", "read_file", "list_directory" -> updateStage(task.id, Stage.READING)
        }
        executeReadOnlyTool(name, args)
      }
      orchestrator.stream(messages).collect { chunk ->
        if (chunk.content.isNotEmpty()) sb.append(chunk.content)
        if (sb.length > 200) updateStage(task.id, Stage.SUMMARIZING)
      }

      val result = sb.toString()
      updateTask(task.id) { it.copy(stage = Stage.FINISHED, result = result) }
      "<task_result>\n$result\n</task_result>"
    } catch (e: Exception) {
      updateTask(task.id) { it.copy(stage = Stage.ERROR, error = e.message) }
      "<task_error>${e.message}</task_error>"
    }
  }

  fun clear() {
    _tasks.value = emptyList()
  }

  private fun updateStage(id: String, stage: Stage) = updateTask(id) { it.copy(stage = stage) }

  private fun updateTask(id: String, transform: (SubagentTask) -> SubagentTask) {
    _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
  }
}
