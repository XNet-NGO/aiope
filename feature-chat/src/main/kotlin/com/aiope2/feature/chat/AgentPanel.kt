package com.aiope2.feature.chat

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
import com.aiope2.feature.chat.db.AgentEntity
import com.aiope2.feature.chat.db.AgentTaskEntity
import com.aiope2.feature.chat.db.ScheduledTaskEntity
import com.aiope2.feature.chat.engine.AgentExecutor

@Composable
fun AgentPanel(
  modifier: Modifier = Modifier,
  agents: List<AgentEntity> = emptyList(),
  runningTasks: List<AgentExecutor.RunningTask> = emptyList(),
  persistedTasks: List<AgentTaskEntity> = emptyList(),
  scheduledTasks: List<ScheduledTaskEntity> = emptyList(),
  models: List<String> = emptyList(),
  onSpawn: (agentName: String, task: String, reportTo: String) -> Unit = { _, _, _ -> },
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
private fun SpawnTab(agents: List<AgentEntity>, onSpawn: (String, String, String) -> Unit) {
  var selectedAgent by remember { mutableStateOf("default") }
  var taskText by remember { mutableStateOf("") }
  var expanded by remember { mutableStateOf(false) }
  var reportTo by remember { mutableStateOf("agent") } // agent, user, both

  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Agent picker
    Box {
      OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Text(selectedAgent.ifEmpty { "Select Agent" }, fontSize = 12.sp)
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("default", fontSize = 12.sp) }, onClick = { selectedAgent = "default"; expanded = false })
        agents.forEach { agent ->
          DropdownMenuItem(text = { Text(agent.name, fontSize = 12.sp) }, onClick = { selectedAgent = agent.name; expanded = false })
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

    // Report to
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Report to:", fontSize = 10.sp, color = Color(0xFF888888))
      listOf("agent" to "Agent", "user" to "User").forEach { (value, label) ->
        FilterChip(
          selected = reportTo == value,
          onClick = { reportTo = value },
          label = { Text(label, fontSize = 9.sp) },
          modifier = Modifier.height(24.dp),
        )
      }
    }

    // Spawn button
    Button(
      onClick = {
        if (taskText.isNotBlank()) {
          onSpawn(selectedAgent, taskText, reportTo)
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
  val maxHistory = 10
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
      onCancel = { onCancel(selectedRunning!!.id); selectedRunning = null },
      onRerun = { onRerun(selectedRunning!!.id); selectedRunning = null },
    )
  }

  // Persisted task detail popup
  if (selectedPersisted != null) {
    PersistedTaskDialog(
      task = selectedPersisted!!,
      onDismiss = { selectedPersisted = null },
      onRerun = { onRerun(selectedPersisted!!.id); selectedPersisted = null },
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
          Text(
            task.result.ifEmpty { "..." },
            fontSize = 10.sp,
            color = Color(0xFF999999),
            fontFamily = FontFamily.Monospace,
            lineHeight = 13.sp,
          )
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
          Text(
            task.result.ifEmpty { "(no output)" },
            fontSize = 10.sp,
            color = Color(0xFF999999),
            fontFamily = FontFamily.Monospace,
            lineHeight = 13.sp,
          )
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

  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("Scheduled Tasks", fontSize = 11.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)
      IconButton(onClick = { showAdd = true }, modifier = Modifier.size(24.dp)) {
        Icon(Icons.Default.Add, "Add timer", modifier = Modifier.size(14.dp), tint = Color(0xFF888888))
      }
    }

    if (scheduledTasks.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No timers set", color = Color(0xFF666666), fontSize = 12.sp)
      }
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(scheduledTasks, key = { it.id }) { timer ->
          TimerRow(timer = timer, onDelete = { onDelete(timer.id) })
        }
      }
    }
  }

  if (showAdd) {
    AddTimerDialog(agents = agents, onDismiss = { showAdd = false }, onSave = { onSave(it); showAdd = false })
  }
}

@Composable
private fun TimerRow(timer: ScheduledTaskEntity, onDelete: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(Color(0xFF151515))
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(timer.agentName, fontSize = 10.sp, color = Color(0xFFBBBBBB), fontWeight = FontWeight.Medium)
      Text(timer.prompt.take(50), fontSize = 9.sp, color = Color(0xFF777777), maxLines = 1)
      val schedule = buildString {
        if (timer.oneShot) append("Once")
        else if (timer.cronHour == -1) append("Every hour")
        else append("${timer.cronHour}:${timer.cronMinute.toString().padStart(2, '0')}")
        if (timer.cronDaysOfWeek.isNotEmpty()) append(" (${timer.cronDaysOfWeek})")
      }
      Text(schedule, fontSize = 9.sp, color = Color(0xFF555555), fontFamily = FontFamily.Monospace)
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
      Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(12.dp), tint = Color(0xFF555555))
    }
  }
}

@Composable
private fun AddTimerDialog(agents: List<AgentEntity>, onDismiss: () -> Unit, onSave: (ScheduledTaskEntity) -> Unit) {
  var selectedAgent by remember { mutableStateOf("Researcher") }
  var prompt by remember { mutableStateOf("") }
  var preset by remember { mutableStateOf("daily") }
  var hour by remember { mutableIntStateOf(9) }
  var minute by remember { mutableIntStateOf(0) }
  var second by remember { mutableIntStateOf(0) }
  var selectedDays by remember { mutableStateOf("") }
  var selectedMonth by remember { mutableIntStateOf(-1) } // -1 = every month
  var agentExpanded by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New Timer", fontSize = 14.sp) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Agent picker
        Box {
          OutlinedButton(onClick = { agentExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedAgent, fontSize = 12.sp)
          }
          DropdownMenu(expanded = agentExpanded, onDismissRequest = { agentExpanded = false }) {
            agents.forEach { a ->
              DropdownMenuItem(text = { Text(a.name, fontSize = 12.sp) }, onClick = { selectedAgent = a.name; agentExpanded = false })
            }
          }
        }

        // Prompt
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          placeholder = { Text("Task prompt...", fontSize = 12.sp) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
          textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
          minLines = 2,
        )

        // Preset chips
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          listOf("once", "hourly", "daily", "weekly", "monthly").forEach { p ->
            FilterChip(
              selected = preset == p,
              onClick = { preset = p },
              label = { Text(p, fontSize = 9.sp) },
              modifier = Modifier.height(26.dp),
            )
          }
        }

        // Time rollers (H:M:S)
        if (preset != "hourly") {
          Text("Time", fontSize = 10.sp, color = Color(0xFF888888))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberRoller(value = hour, range = 0..23, label = "H", onValueChange = { hour = it })
            Text(":", fontSize = 14.sp, color = Color(0xFF888888))
            NumberRoller(value = minute, range = 0..59, label = "M", onValueChange = { minute = it })
            Text(":", fontSize = 14.sp, color = Color(0xFF888888))
            NumberRoller(value = second, range = 0..59, label = "S", onValueChange = { second = it })
          }
        }

        // Day of week (for weekly)
        if (preset == "weekly") {
          Text("Days", fontSize = 10.sp, color = Color(0xFF888888))
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val dayLabels = listOf("S" to "1", "M" to "2", "T" to "3", "W" to "4", "T" to "5", "F" to "6", "S" to "7")
            val selected = selectedDays.split(",").filter { it.isNotEmpty() }.toMutableSet()
            dayLabels.forEach { (label, value) ->
              FilterChip(
                selected = value in selected,
                onClick = {
                  if (value in selected) selected.remove(value) else selected.add(value)
                  selectedDays = selected.joinToString(",")
                },
                label = { Text(label, fontSize = 9.sp) },
                modifier = Modifier.height(24.dp).width(32.dp),
              )
            }
          }
        }

        // Month (for monthly)
        if (preset == "monthly") {
          Text("Month", fontSize = 10.sp, color = Color(0xFF888888))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            var monthExpanded by remember { mutableStateOf(false) }
            val monthNames = listOf("Every", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            Box {
              OutlinedButton(onClick = { monthExpanded = true }, modifier = Modifier.height(32.dp)) {
                Text(monthNames[selectedMonth + 1], fontSize = 10.sp)
              }
              DropdownMenu(expanded = monthExpanded, onDismissRequest = { monthExpanded = false }) {
                monthNames.forEachIndexed { idx, name ->
                  DropdownMenuItem(text = { Text(name, fontSize = 11.sp) }, onClick = { selectedMonth = idx - 1; monthExpanded = false })
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        if (prompt.isNotBlank()) {
          val agent = agents.firstOrNull { it.name == selectedAgent }
          val cronHour = when (preset) {
            "hourly" -> -1
            else -> hour
          }
          onSave(
            ScheduledTaskEntity(
              agentId = agent?.id ?: "",
              agentName = selectedAgent,
              prompt = prompt,
              cronHour = cronHour,
              cronMinute = minute,
              cronDaysOfWeek = if (preset == "weekly") selectedDays else "",
              oneShot = preset == "once",
              nextRun = if (preset == "once") System.currentTimeMillis() + 60_000L else null,
            )
          )
        }
      }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun NumberRoller(value: Int, range: IntRange, label: String, onValueChange: (Int) -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    IconButton(onClick = { if (value < range.last) onValueChange(value + 1) }, modifier = Modifier.size(20.dp)) {
      Text("▲", fontSize = 10.sp, color = Color(0xFF888888))
    }
    Text(
      value.toString().padStart(2, '0'),
      fontSize = 14.sp,
      fontFamily = FontFamily.Monospace,
      color = Color(0xFFCCCCCC),
      fontWeight = FontWeight.Medium,
    )
    IconButton(onClick = { if (value > range.first) onValueChange(value - 1) }, modifier = Modifier.size(20.dp)) {
      Text("▼", fontSize = 10.sp, color = Color(0xFF888888))
    }
    Text(label, fontSize = 8.sp, color = Color(0xFF555555))
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
      onSave = { onSave(it); editingAgent = null },
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
          value = name, onValueChange = { name = it },
          label = { Text("Name", fontSize = 10.sp) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
          textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
          singleLine = true,
        )

        // System Prompt
        OutlinedTextField(
          value = prompt, onValueChange = { prompt = it },
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
            DropdownMenuItem(text = { Text("(use active)", fontSize = 11.sp) }, onClick = { model = ""; modelExpanded = false })
            models.forEach { m ->
              DropdownMenuItem(text = { Text(m, fontSize = 11.sp) }, onClick = { model = m; modelExpanded = false })
            }
          }
        }

        // Tools
        OutlinedTextField(
          value = tools, onValueChange = { tools = it },
          label = { Text("Tools (comma-separated)", fontSize = 10.sp) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
          textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
          singleLine = true,
        )

        // Temperature slider
        SliderRow(label = "Temperature", value = temperature, range = 0f..2f, format = "%.1f") { temperature = it }

        // Top P slider
        SliderRow(label = "Top P", value = topP, range = 0f..1f, format = "%.2f") { topP = it }

        // Top K
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Top K: $topK", fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.width(70.dp))
          Slider(
            value = topK.toFloat(), onValueChange = { topK = it.toInt() },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
          )
        }

        // Max Context
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Max Context: ${maxContext / 1000}k", fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.width(100.dp))
          Slider(
            value = maxContext.toFloat(), onValueChange = { maxContext = it.toInt() },
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
      value = value, onValueChange = onValueChange,
      valueRange = range,
      modifier = Modifier.weight(1f),
    )
  }
}
