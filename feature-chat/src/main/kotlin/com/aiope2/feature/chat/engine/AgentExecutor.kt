package com.aiope2.feature.chat.engine

import com.aiope2.feature.chat.db.AgentEntity
import com.aiope2.feature.chat.db.AgentTaskEntity
import com.aiope2.feature.chat.db.ChatDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Evolved from SubagentManager — resolves agent config from roster,
 * applies per-agent model/tools/temperature.
 */
class AgentExecutor(
  private val createOrchestrator: (AgentConfig, List<StreamingOrchestrator.ToolDef>, suspend (String, Map<String, Any?>) -> String) -> StreamingOrchestrator,
  private val buildMessages: (AgentEntity?, String) -> List<Pair<String, String>>,
  private val getToolDefs: () -> List<StreamingOrchestrator.ToolDef>,
  private val executeTool: suspend (String, Map<String, Any?>) -> String,
  private val dao: ChatDao?,
) {
  enum class Stage { QUEUED, SEARCHING, READING, EXECUTING, SUMMARIZING, FINISHED, ERROR }

  data class AgentConfig(
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 0,
    val maxContext: Int = 32000,
  )

  data class RunningTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val agentName: String,
    val description: String,
    val stage: Stage = Stage.QUEUED,
    val result: String = "",
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val toolCalls: MutableList<String> = mutableListOf(),
  )

  private val _tasks = MutableStateFlow<List<RunningTask>>(emptyList())
  val tasks: StateFlow<List<RunningTask>> = _tasks

  private val allToolNames = setOf(
    "search_web", "search_images", "search_location", "fetch_url",
    "read_file", "list_directory", "query_data", "memory_recall",
    "write_file", "run_sh", "run_proot", "ssh_exec", "image_generate",
    "analyze_image", "browser_content", "browser_elements",
  )

  private val readOnlyTools = setOf(
    "search_web", "search_images", "search_location", "fetch_url",
    "read_file", "list_directory", "query_data", "memory_recall",
  )

  /**
   * Resolve agent from roster and run to completion.
   * Returns result string for tool output.
   */
  suspend fun runAgent(agentName: String, prompt: String, conversationId: String? = null): String {
    val agent = dao?.getAgentByName(agentName)
    val allowedTools = resolveTools(agent)
    val config = resolveConfig(agent)

    val task = RunningTask(
      agentName = agent?.name ?: agentName,
      description = prompt.take(80),
    )
    _tasks.value = _tasks.value + task

    // Persist to Room
    dao?.insertAgentTask(
      AgentTaskEntity(
        id = task.id,
        agentId = agent?.id ?: "",
        agentName = task.agentName,
        prompt = prompt,
        status = "running",
        conversationId = conversationId,
      )
    )

    return try {
      updateStage(task.id, Stage.SEARCHING)
      val tools = getToolDefs().filter { it.name in allowedTools }
      val messages = buildMessages(agent, prompt)

      updateStage(task.id, Stage.READING)
      val sb = StringBuilder()
      val orchestrator = createOrchestrator(config, tools) { name, args ->
        if (name !in allowedTools) return@createOrchestrator "Tool '$name' not available to this agent"
        task.toolCalls.add(name)
        when (name) {
          "search_web", "search_images", "search_location" -> updateStage(task.id, Stage.SEARCHING)
          "fetch_url", "read_file", "list_directory" -> updateStage(task.id, Stage.READING)
          "write_file", "run_sh", "run_proot", "ssh_exec" -> updateStage(task.id, Stage.EXECUTING)
        }
        executeTool(name, args)
      }
      orchestrator.stream(messages).collect { chunk ->
        if (chunk.content.isNotEmpty()) sb.append(chunk.content)
        if (sb.length > 200) updateStage(task.id, Stage.SUMMARIZING)
      }

      val result = sb.toString()
      updateTask(task.id) { it.copy(stage = Stage.FINISHED, result = result) }
      dao?.updateAgentTask(task.id, "finished", result, System.currentTimeMillis())
      "<task_result>\n$result\n</task_result>"
    } catch (e: Exception) {
      val err = e.message ?: "unknown error"
      updateTask(task.id) { it.copy(stage = Stage.ERROR, error = err) }
      dao?.updateAgentTask(task.id, "failed", err, System.currentTimeMillis())
      "<task_error>$err</task_error>"
    }
  }

  /** Legacy compat — run with no agent name (uses read-only tools) */
  suspend fun runBlocking(description: String, prompt: String): String {
    return runAgent("default", prompt)
  }

  fun clear() {
    _tasks.value = emptyList()
  }

  private fun resolveTools(agent: AgentEntity?): Set<String> {
    if (agent == null) return readOnlyTools
    val tools = agent.tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return if (tools.isEmpty()) readOnlyTools else tools.intersect(allToolNames)
  }

  private fun resolveConfig(agent: AgentEntity?): AgentConfig {
    if (agent == null) return AgentConfig()
    return AgentConfig(
      model = agent.model,
      temperature = agent.temperature,
      topP = agent.topP,
      topK = agent.topK,
      maxContext = agent.maxContext,
    )
  }

  private fun updateStage(id: String, stage: Stage) = updateTask(id) { it.copy(stage = stage) }

  private fun updateTask(id: String, transform: (RunningTask) -> RunningTask) {
    _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
  }
}
