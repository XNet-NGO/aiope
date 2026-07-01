package com.aiope2.feature.chat.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes multi-agent DAG pipelines via wavefront scheduling.
 * Stages without dependencies run in parallel; results flow to dependents.
 */
class PipelineExecutor(
  private val agentExecutor: AgentExecutor,
  private val onProgress: (String) -> Unit = {},
) {
  data class Stage(
    val name: String,
    val agent: String = "default",
    val prompt: String,
    val dependsOn: List<String> = emptyList(),
  )

  /**
   * Execute a DAG pipeline to completion.
   * Returns combined results from all stages as markdown.
   */
  suspend fun runPipeline(task: String, stages: List<Stage>): String = withContext(Dispatchers.IO) {
    if (stages.isEmpty()) return@withContext ""

    val results = ConcurrentHashMap<String, String>()
    val completed = ConcurrentHashMap<String, Boolean>()

    onProgress("Orchestrating ${stages.size} stages...")

    while (completed.size < stages.size) {
      // Find ready stages: all dependencies satisfied
      val ready = stages.filter { s ->
        !completed.containsKey(s.name) &&
          s.dependsOn.all { completed.containsKey(it) }
      }

      if (ready.isEmpty()) {
        // Deadlock: remaining stages have unsatisfied deps (circular)
        val stuck = stages.filter { !completed.containsKey(it.name) }.map { it.name }
        onProgress("Deadlock detected: ${stuck.joinToString()}")
        break
      }

      // Run ready stages in parallel
      ready.map { stage ->
        async {
          // Build context from dependencies
          val ctx = stage.dependsOn.joinToString("\n") { dep ->
            "--- $dep result ---\n${results[dep] ?: "no result"}\n"
          }
          val fullPrompt = stage.prompt +
            if (ctx.isNotBlank()) "\n\nContext from prior stages:\n$ctx" else ""

          onProgress("[${stage.name}] started (${stage.agent})")

          val result = withTimeoutOrNull(5 * 60 * 1000L) {
            agentExecutor.runAgent(stage.agent, fullPrompt)
          } ?: "timeout: stage exceeded 5 minute limit"

          // Strip task_result tags for cleaner inter-stage context
          val cleanResult = result
            .removePrefix("<task_result>\n")
            .removeSuffix("\n</task_result>")
            .removePrefix("<task_error>")
            .removeSuffix("</task_error>")

          results[stage.name] = cleanResult
          completed[stage.name] = true
          onProgress("[${stage.name}] completed")
        }
      }.awaitAll()
    }

    // Combine results in stage order
    stages.joinToString("\n\n") { s ->
      "## ${s.name} (${s.agent})\n${results[s.name] ?: "not executed"}"
    }
  }

  companion object {
    /** Parse orchestrate tool arguments into stages */
    fun parseStages(args: Map<String, Any?>): Pair<String, List<Stage>> {
      val task = args["task"]?.toString() ?: ""
      val stagesRaw = args["stages"] as? List<*> ?: return task to emptyList()

      val stages = stagesRaw.mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        Stage(
          name = map["name"]?.toString() ?: return@mapNotNull null,
          agent = map["agent"]?.toString() ?: "default",
          prompt = map["prompt"]?.toString() ?: return@mapNotNull null,
          dependsOn = (map["depends_on"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
        )
      }
      return task to stages
    }
  }
}
