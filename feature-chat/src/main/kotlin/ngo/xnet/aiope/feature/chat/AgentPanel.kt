package ngo.xnet.aiope.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluid.compose.UniversalMarkdown
import ngo.xnet.aiope.feature.chat.db.AgentEntity
import ngo.xnet.aiope.feature.chat.db.AgentTaskEntity
import ngo.xnet.aiope.feature.chat.db.ScheduledTaskEntity
import ngo.xnet.aiope.feature.chat.engine.AgentExecutor

@Composable
fun AgentPanel(
  modifier: Modifier = Modifier,
  agents: List<AgentEntity> = emptyList(),
  runningTasks: List<AgentExecutor.RunningTask> = emptyList(),
  persistedTasks: List<AgentTaskEntity> = emptyList(),
  scheduledTasks: List<ScheduledTaskEntity> = emptyList(),
  models: List<String> = emptyList(),
  onSpawn: (agentName: String, task: String) -> Unit = { _, _ -> },
  onSteerTask: (taskId: String, message: String) -> Unit = { _, _ -> },
  onCancelTask: (taskId: String) -> Unit = {},
  onRerunTask: (taskId: String) -> Unit = {},
  onSaveAgent: (AgentEntity) -> Unit = {},
  onDeleteAgent: (String) -> Unit = {},
  onSaveSchedule: (ScheduledTaskEntity) -> Unit = {},
  onDeleteSchedule: (String) -> Unit = {},
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  val tabs = listOf("Spawn", "Monitor", "Timers", "Builder")

  Column(
    modifier
      .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
      .background(Color(0xFF0A0A0A))
      .padding(top = 4.dp),
  ) {
    // Tab row
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      tabs.forEachIndexed { idx, label ->
        TextButton(
          onClick = { selectedTab = idx },
          modifier = Modifier.height(28.dp),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
          Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (idx == selectedTab) FontWeight.Bold else FontWeight.Normal,
            color = if (idx == selectedTab) MaterialTheme.colorScheme.primary else Color(0xFF888888),
          )
        }
      }
    }

    // Tab content
    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
      when (selectedTab) {
        0 -> SpawnTab(agents = agents, onSpawn = onSpawn)
        1 -> MonitorTab(runningTasks = runningTasks, persistedTasks = persistedTasks, onSteer = onSteerTask, onCancel = onCancelTask, onRerun = onRerunTask)
        2 -> TimersTab(scheduledTasks = scheduledTasks, agents = agents, onSave = onSaveSchedule, onDelete = onDeleteSchedule)
        3 -> BuilderTab(agents = agents, onSave = onSaveAgent, onDelete = onDeleteAgent, models = models)
      }
    }
  }
}

// ── Tab 1: Spawn ──

@Composable
private fun SpawnTab(agents: List<AgentEntity>, onSpawn: (String, String) -> Unit) {
  var selectedAgent by remember { mutableStateOf("default") }
  var taskText by remember { mutableStateOf("") }
  var expanded by remember { mutableStateOf(false) }

  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Agent picker
    Box {
      OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Text(selectedAgent.ifEmpty { "Select Agent" }, fontSize = 12.sp)
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("default", fontSize = 12.sp) }, onClick = {
          selectedAgent = "default"
          expanded = false
        })
        agents.forEach { agent ->
          DropdownMenuItem(text = { Text(agent.name, fontSize = 12.sp) }, onClick = {
            selectedAgent = agent.name
            expanded = false
          })
        }
      }
    }

    // Task input
    OutlinedTextField(
      value = taskText,
      onValueChange = { taskText = it },
      placeholder = { Text("Describe the task...", fontSize = 12.sp) },
      modifier = Modifier.fillMaxWidth().weight(1f),
      textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
    )

    // Spawn button
    Button(
      onClick = {
        if (taskText.isNotBlank()) {
          onSpawn(selectedAgent, taskText)
          taskText = ""
        }
      },
      modifier = Modifier.fillMaxWidth().height(36.dp),
      enabled = taskText.isNotBlank(),
    ) {
      Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp))
      Spacer(Modifier.width(4.dp))
      Text("Spawn", fontSize = 12.sp)
    }
  }
}

// ── Tab 2: Monitor ──

@Composable
private fun MonitorTab(
  runningTasks: List<AgentExecutor.RunningTask>,
  persistedTasks: List<AgentTaskEntity>,
  onSteer: (String, String) -> Unit,
  onCancel: (String) -> Unit = {},
  onRerun: (String) -> Unit = {},
) {
  var selectedRunning by remember { mutableStateOf<AgentExecutor.RunningTask?>(null) }
  var selectedPersisted by remember { mutableStateOf<AgentTaskEntity?>(null) }

  if (runningTasks.isEmpty() && persistedTasks.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No tasks yet", color = Color(0xFF666666), fontSize = 12.sp)
    }
    return
  }

  // Max 10 history: show running + up to (10 - running.size) persisted
  val maxHistory = 30
  val runningIds = runningTasks.map { it.id }.toSet()
  val historySlots = (maxHistory - runningTasks.size).coerceAtLeast(0)
  val visiblePersisted = persistedTasks
    .filter { it.id !in runningIds }
    .take(historySlots)

  LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    items(runningTasks, key = { "run_${it.id}" }) { task ->
      MonitorRow(
        name = task.agentName,
        description = task.description,
        status = task.stage.name.lowercase(),
        isRunning = true,
        onClick = { selectedRunning = task },
      )
    }
    items(visiblePersisted, key = { "done_${it.id}" }) { task ->
      MonitorRow(
        name = task.agentName,
        description = task.prompt.take(60),
        status = task.status,
        isRunning = false,
        onClick = { selectedPersisted = task },
      )
    }
  }

  // Running task detail popup
  if (selectedRunning != null) {
    RunningTaskDialog(
      task = selectedRunning!!,
      onDismiss = { selectedRunning = null },
      onSteer = onSteer,
      onCancel = {
        onCancel(selectedRunning!!.id)
        selectedRunning = null
      },
      onRerun = {
        onRerun(selectedRunning!!.id)
        selectedRunning = null
      },
    )
  }

  // Persisted task detail popup
  if (selectedPersisted != null) {
    PersistedTaskDialog(
      task = selectedPersisted!!,
      onDismiss = { selectedPersisted = null },
      onRerun = {
        onRerun(selectedPersisted!!.id)
        selectedPersisted = null
      },
      onSteer = onSteer,
    )
  }
}

@Composable
private fun MonitorRow(name: String, description: String, status: String, isRunning: Boolean, onClick: () -> Unit) {
  val statusColor = when {
    status == "finished" -> Color(0xFF4CAF50)
    status == "failed" || status == "error" -> Color(0xFFFF5252)
    isRunning -> Color(0xFFFFB74D)
    else -> Color(0xFF888888)
  }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(Color(0xFF151515))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
    Spacer(Modifier.width(6.dp))
    Column(Modifier.weight(1f)) {
      Text(name, fontSize = 10.sp, color = Color(0xFFBBBBBB), fontWeight = FontWeight.Medium)
      Text(description, fontSize = 9.sp, color = Color(0xFF777777), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(status, fontSize = 9.sp, color = statusColor, fontFamily = FontFamily.Monospace)
  }
}

@Composable
private fun RunningTaskDialog(
  task: AgentExecutor.RunningTask,
  onDismiss: () -> Unit,
  onSteer: (String, String) -> Unit,
  onCancel: () -> Unit,
  onRerun: () -> Unit = {},
) {
  var steerText by remember { mutableStateOf("") }
  val scrollState = rememberScrollState()

  // Auto-scroll to bottom when result updates
  LaunchedEffect(task.result) {
    scrollState.animateScrollTo(scrollState.maxValue)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFB74D)))
        Spacer(Modifier.width(6.dp))
        Text("${task.agentName} — ${task.stage.name.lowercase()}", fontSize = 13.sp)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Prompt
        Text("Prompt:", fontSize = 9.sp, color = Color(0xFF888888))
        Text(task.description, fontSize = 10.sp, color = Color(0xFFAAAAAA), maxLines = 3, overflow = TextOverflow.Ellipsis)

        // Live stream output
        Text("Output:", fontSize = 9.sp, color = Color(0xFF888888))
        Box(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp, max = 200.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF0A0A0A))
            .verticalScroll(scrollState)
            .padding(6.dp),
        ) {
          if (task.result.isNotEmpty()) {
            UniversalMarkdown(content = task.result, modifier = Modifier.fillMaxWidth())
          } else {
            Text("...", fontSize = 10.sp, color = Color(0xFF999999), fontFamily = FontFamily.Monospace)
          }
        }

        // Steer input
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = steerText,
            onValueChange = { steerText = it },
            placeholder = { Text("Steer agent...", fontSize = 10.sp) },
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 36.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
            singleLine = true,
          )
          Spacer(Modifier.width(4.dp))
          TextButton(onClick = {
            if (steerText.isNotBlank()) {
              onSteer(task.id, steerText)
              steerText = ""
            }
          }) { Text("Steer", fontSize = 10.sp) }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      Row {
        val isFinished = task.stage == AgentExecutor.Stage.FINISHED || task.stage == AgentExecutor.Stage.ERROR
        if (isFinished) {
          TextButton(onClick = onRerun) { Text("Rerun", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
        } else {
          TextButton(onClick = onCancel) { Text("Cancel Task", fontSize = 11.sp, color = Color(0xFFFF5252)) }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) { Text("Close", fontSize = 11.sp) }
      }
    },
  )
}

@Composable
private fun PersistedTaskDialog(
  task: AgentTaskEntity,
  onDismiss: () -> Unit,
  onRerun: () -> Unit,
  onSteer: (String, String) -> Unit = { _, _ -> },
) {
  val scrollState = rememberScrollState()
  val statusColor = if (task.status == "finished") Color(0xFF4CAF50) else Color(0xFFFF5252)
  var steerText by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
        Spacer(Modifier.width(6.dp))
        Text("${task.agentName} — ${task.status}", fontSize = 13.sp)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Prompt
        Text("Prompt:", fontSize = 9.sp, color = Color(0xFF888888))
        Text(task.prompt, fontSize = 10.sp, color = Color(0xFFAAAAAA), maxLines = 4, overflow = TextOverflow.Ellipsis)

        // Result
        Text("Result:", fontSize = 9.sp, color = Color(0xFF888888))
        Box(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp, max = 200.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF0A0A0A))
            .verticalScroll(scrollState)
            .padding(6.dp),
        ) {
          if (task.result.isNotEmpty()) {
            UniversalMarkdown(content = task.result, modifier = Modifier.fillMaxWidth())
          } else {
            Text("(no output)", fontSize = 10.sp, color = Color(0xFF999999), fontFamily = FontFamily.Monospace)
          }
        }

        // Steer input (works after completion to re-engage agent)
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = steerText,
            onValueChange = { steerText = it },
            placeholder = { Text("Steer agent...", fontSize = 10.sp) },
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 36.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
            singleLine = true,
          )
          Spacer(Modifier.width(4.dp))
          TextButton(onClick = {
            if (steerText.isNotBlank()) {
              onSteer(task.id, steerText)
              steerText = ""
            }
          }) { Text("Steer", fontSize = 10.sp) }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onRerun) { Text("Rerun", fontSize = 11.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Close", fontSize = 11.sp) }
    },
  )
}

// ── Tab 3: Timers ──

@Composable
private fun TimersTab(
  scheduledTasks: List<ScheduledTaskEntity>,
  agents: List<AgentEntity>,
  onSave: (ScheduledTaskEntity) -> Unit,
  onDelete: (String) -> Unit,
) {
  var showAdd by remember { mutableStateOf(false) }
  var editingTimer by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

  Column(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Timers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFCCCCCC))
      TextButton(
        onClick = { showAdd = true },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
      ) {
        Icon(Icons.Default.Add, "Add timer", modifier = Modifier.size(14.dp), tint = Color(0xFF888888))
        Spacer(Modifier.width(4.dp))
        Text("New Timer", fontSize = 12.sp)
      }
    }

    if (scheduledTasks.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("No timers yet", fontSize = 13.sp, color = Color(0xFF888888))
          Text("Schedule an agent to run automatically.", fontSize = 11.sp, color = Color(0xFF666666))
          TextButton(onClick = { showAdd = true }) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Create timer", fontSize = 12.sp)
          }
        }
      }
    } else {
      LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      ) {
        items(scheduledTasks, key = { it.id }) { t ->
          TimerRow(
            timer = t,
            onEdit = { editingTimer = t },
            onDelete = { onDelete(t.id) },
          )
        }
      }
    }
  }

  if (showAdd) {
    AddTimerDialog(
      agents = agents,
      onDismiss = { showAdd = false },
      onSave = {
        onSave(it)
        showAdd = false
      },
    )
  }
  editingTimer?.let { t ->
    AddTimerDialog(
      agents = agents,
      editing = t,
      onDismiss = { editingTimer = null },
      onSave = {
        onSave(it)
        editingTimer = null
      },
    )
  }
}
private fun fmtTime(h: Int, m: Int) = String.format("%02d:%02d", h, m)

private fun describeSchedule(t: ScheduledTaskEntity): String = when (t.scheduleType) {
  "once" -> "Once, shortly after save"
  "interval" -> "Every ${t.intervalValue} ${t.intervalUnit}"
  "daily" -> "Daily at ${fmtTime(t.timeHour, t.timeMinute)}"
  "weekly" -> "Weekly (${t.daysOfWeek.ifBlank { "Mon-Fri" }}) at ${fmtTime(t.timeHour, t.timeMinute)}"
  "monthly" -> "Monthly day ${t.dayOfMonth} at ${fmtTime(t.timeHour, t.timeMinute)}"
  else -> t.scheduleType
}

@Composable
private fun TimerRow(timer: ScheduledTaskEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
  val tools = timer.tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }
  val runs = if (timer.maxRuns > 0) "${timer.runsCompleted}/${timer.maxRuns} runs" else "${timer.runsCompleted} runs"
  val nextLabel = timer.nextRun?.let {
    "next " + java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(it))
  } ?: "not scheduled"
  val statusColor = when {
    timer.status == "running" -> Color(0xFF4FC3F7)
    timer.status == "failed" -> Color(0xFFEF5350)
    timer.status == "finished" || (timer.maxRuns > 0 && timer.runsCompleted >= timer.maxRuns) -> Color(0xFF66BB6A)
    else -> Color(0xFF757575)
  }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(Color(0xFF1E1E1E))
      .clickable(onClick = onEdit)
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
        Text(
          timer.agentName,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = Color(0xFFDDDDDD),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(timer.prompt, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(describeSchedule(timer), fontSize = 10.sp, color = Color(0xFF777777), fontFamily = FontFamily.Monospace)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(runs, fontSize = 9.sp, color = Color(0xFF999999))
        Text(nextLabel, fontSize = 9.sp, color = Color(0xFF555555), fontFamily = FontFamily.Monospace)
        if (tools.isEmpty()) {
          Text("\u00b7 no tools", fontSize = 9.sp, color = Color(0xFFB8860B))
        } else {
          Text(
            "\u00b7 " + tools.take(3).joinToString(", ") + if (tools.size > 3) " +${tools.size - 3}" else "",
            fontSize = 9.sp,
            color = Color(0xFF6E6E6E),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
      Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(14.dp), tint = Color(0xFF555555))
    }
  }
}

private val timerToolGroups = listOf(
  "Actions" to listOf(
    "run_sh" to "Shell",
    "ssh_exec" to "Remote SSH",
    "send_notification" to "Notify",
    "set_alarm" to "Alarm",
  ),
  "Memory" to listOf(
    "memory_store" to "Store fact",
    "memory_recall" to "Recall",
  ),
)
private val allTimerTools: List<String> = timerToolGroups.flatMap { it.second }.map { it.first }

private fun weekdayLabels(days: List<String>): String {
  val names = mapOf("1" to "Mon", "2" to "Tue", "3" to "Wed", "4" to "Thu", "5" to "Fri", "6" to "Sat", "7" to "Sun")
  return if (days.isEmpty()) "none" else days.mapNotNull { names[it] }.joinToString(" ")
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text.uppercase(),
    fontSize = 10.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.8.sp,
    color = Color(0xFF8A8A8A),
    modifier = Modifier.padding(top = 2.dp),
  )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddTimerDialog(
  agents: List<AgentEntity> = emptyList(),
  editing: ScheduledTaskEntity? = null,
  onDismiss: () -> Unit,
  onSave: (ScheduledTaskEntity) -> Unit,
) {
  val scheduleTypes = listOf("once", "interval", "daily", "weekly", "monthly")
  val scheduleLabels = mapOf("once" to "Once", "interval" to "Interval", "daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly")
  val unitLabels = listOf("min", "hour", "day")

  var agentId by remember { mutableStateOf(editing?.agentId ?: agents.firstOrNull()?.id.orEmpty()) }
  var agentName by remember { mutableStateOf(editing?.agentName ?: "") }
  var prompt by remember { mutableStateOf(editing?.prompt.orEmpty()) }
  var scheduleType by remember { mutableStateOf(editing?.scheduleType ?: "once") }
  var intervalValue by remember { mutableStateOf(editing?.intervalValue ?: 30) }
  var intervalUnit by remember { mutableStateOf(editing?.intervalUnit ?: "min") }
  var timeHour by remember { mutableStateOf(editing?.timeHour ?: 9) }
  var timeMinute by remember { mutableStateOf(editing?.timeMinute ?: 0) }
  var selectedDays by remember {
    mutableStateOf(editing?.daysOfWeek?.takeIf { it.isNotBlank() }?.split(",") ?: listOf("1", "2", "3", "4", "5"))
  }
  var dayOfMonth by remember { mutableStateOf(editing?.dayOfMonth ?: 1) }
  var maxRuns by remember { mutableStateOf(editing?.maxRuns ?: 0) }
  var selectedTools by remember {
    mutableStateOf(
      editing?.tools?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: allTimerTools,
    )
  }

  val selectedAgent = agents.firstOrNull { it.id == agentId }

  val summary = buildString {
    when (scheduleType) {
      "once" -> append("Runs once, 60s from now")
      "interval" -> append("Every $intervalValue $intervalUnit${if (intervalValue > 1) "s" else ""}")
      "daily" -> append("Daily at ${fmtTime(timeHour, timeMinute)}")
      "weekly" -> append("Weekly (${weekdayLabels(selectedDays)}) at ${fmtTime(timeHour, timeMinute)}")
      "monthly" -> append("Monthly day $dayOfMonth at ${fmtTime(timeHour, timeMinute)}")
    }
    if (maxRuns > 0) append(" \u00b7 max $maxRuns runs") else append(" \u00b7 unlimited")
    append(" \u00b7 ${selectedTools.size} tool${if (selectedTools.size == 1) "" else "s"}")
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (editing == null) "New Timer" else "Edit Timer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SectionHeader("Agent")
        if (agents.isEmpty()) {
          Text("No agents yet \u2014 create one in the Builder tab.", fontSize = 11.sp, color = Color(0xFF888888))
        } else {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            agents.forEach { a ->
              FilterChip(
                selected = a.id == agentId,
                onClick = {
                  agentId = a.id
                  agentName = a.name
                },
                label = { Text(a.name, fontSize = 11.sp) },
              )
            }
          }
        }

        SectionHeader("Prompt")
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          placeholder = { Text("What should the agent do?", fontSize = 12.sp) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
          textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
          minLines = 2,
          maxLines = 4,
        )

        SectionHeader("Schedule")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          scheduleTypes.forEach { t ->
            FilterChip(
              selected = scheduleType == t,
              onClick = { scheduleType = t },
              label = { Text(scheduleLabels.getValue(t), fontSize = 11.sp) },
            )
          }
        }
        when (scheduleType) {
          "once" -> Text("Runs a single time, about 60 seconds after saving.", fontSize = 11.sp, color = Color(0xFF888888))

          "interval" -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              NumberRoller(intervalValue, 1..720, "every", { intervalValue = it })
              FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                unitLabels.forEach { u ->
                  FilterChip(
                    selected = intervalUnit == u,
                    onClick = { intervalUnit = u },
                    label = { Text(if (u == "min") "minutes" else u, fontSize = 11.sp) },
                  )
                }
              }
            }
          }

          "daily" -> TimeRollers(timeHour, timeMinute) { h, m ->
            timeHour = h
            timeMinute = m
          }

          "weekly" -> {
            TimeRollers(timeHour, timeMinute) { h, m ->
              timeHour = h
              timeMinute = m
            }
            WeekdayChips(selectedDays) { selectedDays = it }
          }

          "monthly" -> {
            TimeRollers(timeHour, timeMinute) { h, m ->
              timeHour = h
              timeMinute = m
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              NumberRoller(dayOfMonth, 1..28, "day", { dayOfMonth = it })
            }
          }
        }

        SectionHeader("Limits")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          NumberRoller(maxRuns, 0..60, "max runs (0 = unlimited)", { maxRuns = it })
        }

        SectionHeader("Tools")
        timerToolGroups.forEach { (group, tools) ->
          Text(group, fontSize = 10.sp, color = Color(0xFF666666), fontWeight = FontWeight.Medium)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tools.forEach { (tool, label) ->
              val selected = tool in selectedTools
              FilterChip(
                selected = selected,
                onClick = { selectedTools = if (selected) selectedTools - tool else selectedTools + tool },
                label = { Text(label, fontSize = 11.sp) },
              )
            }
          }
        }
        if (selectedTools.isEmpty()) {
          Text("No tools \u2014 the agent can only reason and produce text.", fontSize = 11.sp, color = Color(0xFFB8860B))
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
        Text(summary, fontSize = 11.sp, color = Color(0xFF9E9E9E))
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          if (prompt.isBlank()) return@TextButton
          val days = if (scheduleType == "weekly") selectedDays.joinToString(",") else ""
          onSave(
            ScheduledTaskEntity(
              id = editing?.id ?: java.util.UUID.randomUUID().toString(),
              agentId = agentId,
              agentName = selectedAgent?.name ?: agentName.ifBlank { "Timer Agent" },
              prompt = prompt.trim(),
              tools = selectedTools.sorted().joinToString(","),
              scheduleType = scheduleType,
              intervalValue = intervalValue,
              intervalUnit = intervalUnit,
              timeHour = timeHour,
              timeMinute = timeMinute,
              daysOfWeek = days,
              dayOfMonth = dayOfMonth,
              maxRuns = maxRuns,
            ),
          )
        },
      ) { Text(if (editing == null) "Create" else "Save", fontSize = 13.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 13.sp) }
    },
  )
}

@Composable
private fun TimeRollers(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    NumberRoller(hour, 0..23, "hour", { onChange(it, minute) })
    Text(":", color = Color(0xFF888888), fontSize = 12.sp)
    NumberRoller(minute, 0..59, "min", { onChange(hour, it) })
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(days: List<String>, onChange: (List<String>) -> Unit) {
  val labels = listOf("Mon" to "1", "Tue" to "2", "Wed" to "3", "Thu" to "4", "Fri" to "5", "Sat" to "6", "Sun" to "7")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    labels.forEach { (label, value) ->
      val selected = days.contains(value)
      FilterChip(
        selected = selected,
        onClick = { onChange(if (selected) days - value else days + value) },
        label = { Text(label, fontSize = 11.sp) },
      )
    }
  }
}

@Composable
private fun NumberRoller(value: Int, range: IntRange, label: String, onValueChange: (Int) -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
    IconButton(onClick = { if (value < range.last) onValueChange(value + 1) }, modifier = Modifier.size(32.dp)) {
      Text("▲", fontSize = 16.sp, color = Color(0xFF888888))
    }
    Text(
      value.toString().padStart(2, '0'),
      fontSize = 22.sp,
      fontFamily = FontFamily.Monospace,
      color = Color(0xFFCCCCCC),
      fontWeight = FontWeight.Medium,
    )
    IconButton(onClick = { if (value > range.first) onValueChange(value - 1) }, modifier = Modifier.size(32.dp)) {
      Text("▼", fontSize = 16.sp, color = Color(0xFF888888))
    }
    Text(label, fontSize = 10.sp, color = Color(0xFF555555))
  }
}

// ── Shared: Tool Group Selector ──

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ToolGroupSelector(
  toolGroups: List<Pair<String, List<String>>>,
  selectedTools: List<String>,
  onToggle: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    toolGroups.forEach { (group, tools) ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(group, fontSize = 9.sp, color = Color(0xFF666666), fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          tools.forEach { tool ->
            FilterChip(
              selected = tool in selectedTools,
              onClick = { onToggle(tool) },
              label = { Text(tool.replace("_", " "), fontSize = 9.sp) },
              modifier = Modifier.height(26.dp),
            )
          }
        }
      }
    }
  }
}

// ── Tab 4: Builder ──

@Composable
private fun BuilderTab(
  agents: List<AgentEntity>,
  onSave: (AgentEntity) -> Unit,
  onDelete: (String) -> Unit,
  models: List<String> = emptyList(),
) {
  var editingAgent by remember { mutableStateOf<AgentEntity?>(null) }

  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Agent Roster", fontSize = 11.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)
      IconButton(onClick = { editingAgent = AgentEntity(name = "", prompt = "") }, modifier = Modifier.size(24.dp)) {
        Icon(Icons.Default.Add, "New agent", modifier = Modifier.size(14.dp), tint = Color(0xFF888888))
      }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      items(agents, key = { it.id }) { agent ->
        AgentRow(agent = agent, onEdit = { editingAgent = agent }, onDelete = { if (!agent.builtin) onDelete(agent.id) })
      }
    }
  }

  if (editingAgent != null) {
    AgentEditorDialog(
      agent = editingAgent!!,
      models = models,
      onSave = {
        onSave(it)
        editingAgent = null
      },
      onDismiss = { editingAgent = null },
    )
  }
}

@Composable
private fun AgentRow(agent: AgentEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(Color(0xFF151515))
      .clickable(onClick = onEdit)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(agent.name, fontSize = 11.sp, color = Color(0xFFCCCCCC), fontWeight = FontWeight.Medium)
        if (agent.builtin) {
          Spacer(Modifier.width(4.dp))
          Text("builtin", fontSize = 8.sp, color = Color(0xFF555555))
        }
      }
      Text(agent.prompt.take(60), fontSize = 9.sp, color = Color(0xFF666666), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = onEdit, modifier = Modifier.size(20.dp)) {
      Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(12.dp), tint = Color(0xFF555555))
    }
    if (!agent.builtin) {
      IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(12.dp), tint = Color(0xFF555555))
      }
    }
  }
}

@Composable
private fun AgentEditorDialog(
  agent: AgentEntity,
  models: List<String>,
  onSave: (AgentEntity) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(agent.name) }
  var prompt by remember { mutableStateOf(agent.prompt) }
  var model by remember { mutableStateOf(agent.model) }
  var tools by remember { mutableStateOf(agent.tools) }
  var temperature by remember { mutableFloatStateOf(agent.temperature) }
  var topP by remember { mutableFloatStateOf(agent.topP) }
  var topK by remember { mutableIntStateOf(agent.topK) }
  var maxContext by remember { mutableIntStateOf(agent.maxContext) }
  var modelExpanded by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (agent.name.isEmpty()) "New Agent" else "Edit: ${agent.name}", fontSize = 14.sp) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // Name
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Name", fontSize = 10.sp) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
          textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
          singleLine = true,
        )

        // System Prompt
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          label = { Text("System Prompt", fontSize = 10.sp) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 80.dp),
          textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
          minLines = 3,
        )

        // Model picker (selectable list)
        Text("Model", fontSize = 10.sp, color = Color(0xFF888888))
        Box {
          OutlinedButton(onClick = { modelExpanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
            Text(model.ifEmpty { "(use active)" }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
            DropdownMenuItem(text = { Text("(use active)", fontSize = 11.sp) }, onClick = {
              model = ""
              modelExpanded = false
            })
            models.forEach { m ->
              DropdownMenuItem(text = { Text(m, fontSize = 11.sp) }, onClick = {
                model = m
                modelExpanded = false
              })
            }
          }
        }

        // Tools (selectable list)
        Text("Tools", fontSize = 10.sp, color = Color(0xFF888888))
        val builderToolGroups = listOf(
          "Web" to listOf("search_web", "search_images", "search_location", "fetch_url"),
          "Files" to listOf("read_file", "list_directory", "write_file"),
          "Execute" to listOf("run_sh", "run_proot", "ssh_exec"),
          "Memory" to listOf("memory_recall", "memory_store", "query_data"),
          "Media" to listOf("image_generate", "analyze_image"),
          "Browser" to listOf("browser_content", "browser_elements", "browser_click", "browser_fill", "browser_eval"),
        )
        val selectedTools = remember(tools) { tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() }
        ToolGroupSelector(
          toolGroups = builderToolGroups,
          selectedTools = selectedTools.toList(),
          onToggle = { tool ->
            if (tool in selectedTools) selectedTools.remove(tool) else selectedTools.add(tool)
            tools = selectedTools.joinToString(",")
          },
        )

        // Temperature slider
        SliderRow(label = "Temperature", value = temperature, range = 0f..2f, format = "%.1f") { temperature = it }

        // Top P slider
        SliderRow(label = "Top P", value = topP, range = 0f..1f, format = "%.2f") { topP = it }

        // Top K
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Top K: $topK", fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.width(70.dp))
          Slider(
            value = topK.toFloat(),
            onValueChange = { topK = it.toInt() },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
          )
        }

        // Max Context
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Max Context: ${maxContext / 1000}k", fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.width(100.dp))
          Slider(
            value = maxContext.toFloat(),
            onValueChange = { maxContext = it.toInt() },
            valueRange = 4000f..128000f,
            steps = 30,
            modifier = Modifier.weight(1f),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        if (name.isNotBlank() && prompt.isNotBlank()) {
          onSave(agent.copy(name = name, prompt = prompt, model = model, tools = tools, temperature = temperature, topP = topP, topK = topK, maxContext = maxContext))
        }
      }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onValueChange: (Float) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text("$label: ${format.format(value)}", fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.width(100.dp))
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      modifier = Modifier.weight(1f),
    )
  }
}
