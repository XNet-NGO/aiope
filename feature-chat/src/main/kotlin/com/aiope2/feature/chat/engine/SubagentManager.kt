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
  private val createOrchestrator: () -> StreamingOrchestrator,
  private val buildMessages: suspend (String) -> List<Pair<String, String>>,
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

        updateStage(task.id, Stage.READING)
        val sb = StringBuilder()
        val orchestrator = createOrchestrator()
        orchestrator.stream(messages).collect { chunk ->
          if (chunk.content.isNotEmpty()) sb.append(chunk.content)
          // Move to summarizing once we have substantial content
          if (sb.length > 200) updateStage(task.id, Stage.SUMMARIZING)
        }

        updateTask(task.id) { it.copy(stage = Stage.FINISHED, result = sb.toString()) }
      } catch (e: Exception) {
        updateTask(task.id) { it.copy(stage = Stage.ERROR, error = e.message, result = "") }
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
