package com.aiope2.feature.chat.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SubagentManager(
  private val scope: CoroutineScope,
  private val createOrchestrator: (List<StreamingOrchestrator.ToolDef>, suspend (String, Map<String, Any?>) -> String) -> StreamingOrchestrator,
  private val buildMessages: suspend (String) -> List<Pair<String, String>>,
  private val getReadOnlyTools: () -> List<StreamingOrchestrator.ToolDef>,
  private val executeReadOnlyTool: suspend (String, Map<String, Any?>) -> String,
  var onTaskFinished: ((SubagentTask) -> Unit)? = null,
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
  private val jobs = mutableMapOf<String, Job>()

  fun spawn(description: String, prompt: String): String {
    val task = SubagentTask(description = description)
    _tasks.value = _tasks.value + task

    jobs[task.id] = scope.launch(Dispatchers.IO) {
      try {
        updateStage(task.id, Stage.SEARCHING)
        val messages = buildMessages(prompt)
        val tools = getReadOnlyTools()

        updateStage(task.id, Stage.READING)
        val sb = StringBuilder()
        val orchestrator = createOrchestrator(tools) { name, args ->
          // Track stage based on tool usage
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

        val finished = task.copy(stage = Stage.FINISHED, result = sb.toString())
        updateTask(task.id) { finished }
        onTaskFinished?.invoke(finished)
      } catch (e: Exception) {
        val errTask = task.copy(stage = Stage.ERROR, error = e.message, result = "")
        updateTask(task.id) { errTask }
        onTaskFinished?.invoke(errTask)
      }
    }
    return task.id
  }

  fun cancel(taskId: String) {
    jobs[taskId]?.cancel()
    updateTask(taskId) { it.copy(stage = Stage.ERROR, error = "Cancelled") }
  }

  fun getResult(taskId: String): SubagentTask? = _tasks.value.find { it.id == taskId }

  fun clear() {
    jobs.values.forEach { it.cancel() }
    jobs.clear()
    _tasks.value = emptyList()
  }

  private fun updateStage(id: String, stage: Stage) = updateTask(id) { it.copy(stage = stage) }

  private fun updateTask(id: String, transform: (SubagentTask) -> SubagentTask) {
    _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
  }
}
