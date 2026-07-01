# AIOPE Agent System — Feature Spec

## Overview

Add a persistent agent management system to AIOPE with spawnable agents, real-time monitoring, and scheduled tasks. Accessed via a new toolbar icon that opens a tri-panel below the chat input (like Browser/Terminal).

---

## UI Layout

### Toolbar Icon
- New icon: `Smart_Toy` (or robot head) placed between Terminal and Settings icons in the top toolbar
- Tapping opens the Agent Panel below the text input area
- Panel slides up from bottom (same animation as Browser/Terminal panels)

### Agent Panel — Four Tabs

```
┌─────────────────────────────────────┐
│  Chat messages...                    │
│                                      │
├─────────────────────────────────────┤
│  [Text input area]                   │
├────────┬─────────┬────────┬─────────┤
│ Spawn  │ Monitor │ Timers │ Builder │
└────────┴─────────┴────────┴─────────┘
```

- **Collapsed**: Single row showing active tab name + badge count
- **Half-screen**: Panel takes ~40% of screen (default on open)
- **Full-screen**: Expands to title bar (drag up or tap expand icon)
- Tapping a tab selects it for the full panel width

---

## Tab 1: Spawn (Agent + Task + Go)

The primary action tab — quick agent dispatch.

### Layout
```
┌──────────────────────────────┐
│ [Agent picker dropdown]       │
│                               │
│ ┌───────────────────────────┐ │
│ │ Task prompt input         │ │
│ │ (multiline, expandable)   │ │
│ └───────────────────────────┘ │
│                               │
│ Report to: (●) User  ( ) Chat │
│                               │
│         [ Spawn ]             │
└──────────────────────────────┘
```

### Behavior
- Agent picker shows all agents (builtin + custom) as a dropdown/chip selector
- Shows selected agent's description/tool count as subtitle
- Task prompt: what this agent should do
- Report to: results go to notification (User) or injected into chat (Chat)
- "Spawn" button fires the task → Monitor tab badge increments
- Long-press an agent in the picker → shows its full config (read-only)

---

## Tab 2: Monitor (Live Tasks)

| Name | Tools | Temperature | Purpose |
|------|-------|-------------|---------|
| Architect | read_file, list_directory, search_web, fetch_url, memory_* | 0.7 | System design, specs |
| Coder | run_sh, read_file, write_file, list_directory, search_web, fetch_url | 0.3 | Implementation |
| Researcher | read_file, search_web, search_images, fetch_url, memory_* | 0.5 | Research, summarize |
| QA | run_sh, read_file, write_file, list_directory | 0.3 | Testing, bug finding |
| DevOps | run_sh, read_file, write_file, ssh_*, fetch_url | 0.3 | Infrastructure |
| Security | run_sh, read_file, list_directory, search_web, fetch_url | 0.4 | Auditing |
| Writer | read_file, write_file, list_directory, search_web | 0.6 | Documentation |
| Reviewer | read_file, list_directory, search_web, fetch_url | 0.4 | Code review |

### Agent Builder (Create/Edit)

Fields:
- **Name** — string, required
- **System Prompt** — multiline text, defines personality and behavior
- **Model** — picker from available gateway models (or "default" = active model)
- **Tools** — checklist of all available tools (subset selection)
- **Max Context** — slider/input (4K–256K)
- **Temperature** — slider (0.0–2.0)
- **Top P** — slider (0.0–1.0)
- **Top K** — input (0–256, 0 = off)

Storage: Room entity `AgentEntity` in `feature-chat` database

```kotlin
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val model: String = "", // empty = use active
    val tools: String = "", // comma-separated
    val maxContext: Int = 32000,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 0,
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
```

---

## Tab 2: Monitor (Live Tasks)

### Active Tasks List
- Shows all running/completed agent tasks
- Each row: **Agent name** | status badge | elapsed time | progress stage
- Status: `QUEUED` → `RUNNING` → `FINISHED` | `FAILED`
- Stages (auto-detected from tool use): Searching → Reading → Writing → Thinking

### Task Detail (tap to expand)
- Full prompt that was given
- Tool calls made (expandable)
- Current output (streams live)
- Result (when finished)
- "Stop" button for running tasks
- "Respawn" button for finished tasks

### Steering Input
- When a task is selected and running, a text input appears at the bottom of the Monitor tab
- User can type messages to steer/redirect the agent mid-task
- Messages are injected as a `user` message into the agent's conversation context
- The agent sees it on its next tool-loop iteration and can adjust behavior
- Examples: "focus on the auth module", "skip tests, just implement", "also check the API docs"
- Input only visible when a running task is selected (hidden when idle/finished)
- Send button or enter to inject, shows as a "steered" badge on the task row

### Spawn Quick-Action
- From Monitor tab: "+" button opens spawn dialog
- Select agent → enter task prompt → optional: report to chat toggle → Go
- Spawned task appears in monitor immediately

### Implementation
- Reuse existing `SubagentManager` from ChatViewModel
- Add persistence: `TaskEntity` in Room

```kotlin
@Entity(tableName = "agent_tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val agentName: String,
    val prompt: String,
    val status: String = "queued", // queued, running, finished, failed
    val result: String = "",
    val toolCalls: String = "", // JSON array
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val conversationId: String? = null, // if report-to-chat
    val scheduledTaskId: String? = null // if triggered by timer
)
```

---

## Tab 3: Timers (Scheduled Tasks)

### Timer List
- Shows all scheduled agent tasks
- Each row: **Agent name** | schedule description | next run | enabled toggle
- Swipe to delete, tap to edit

### Create Timer
- Select agent from roster
- Enter task prompt (what the agent should do each run)
- Schedule picker:
  - **Quick presets**: Every hour, Every morning (8am), Every evening (6pm), Daily, Weekly
  - **Custom**: Cron-style picker (hour, minute, day-of-week)
- Report mode: Notification only | Add to conversation | Both
- Enabled toggle

### Timer Entity

```kotlin
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val agentName: String,
    val prompt: String,
    val cronHour: Int = -1, // -1 = every hour
    val cronMinute: Int = 0,
    val cronDaysOfWeek: String = "", // empty = every day, "1,2,3,4,5" = weekdays
    val reportMode: String = "notification", // notification, conversation, both
    val conversationId: String? = null,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val nextRun: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
```

### Scheduler Service

- `AgentSchedulerWorker` — WorkManager periodic worker (15 min check interval)
- On each tick: query `scheduled_tasks` where `enabled = true` and `nextRun <= now()`
- For each due task:
  1. Resolve agent config from `agents` table
  2. Create `StreamingOrchestrator` with agent's model/tools
  3. Run the prompt through the tool loop
  4. Store result in `agent_tasks`
  5. If `reportMode` includes notification: post Android notification with summary
  6. If `reportMode` includes conversation: insert messages into conversation
  7. Update `lastRun` and calculate `nextRun`
- Foreground notification: "AIOPE Agent Scheduler active" (persistent, low priority)

### Background Constraints
- WorkManager handles Doze/battery optimization
- 15-minute minimum interval (Android WorkManager constraint)
- Battery-not-low constraint for non-critical timers
- Network-required constraint (needs API access)
- User can force-run any timer immediately from the UI

---

## Tab 4: Builder (Custom Agents)

Create, edit, and manage the agent roster.

### Agent Roster View
- Scrollable list of all agents (builtin + custom)
- Each row: **Name** | model badge | tool count | ⋮ menu (edit/duplicate/delete)
- Builtin agents show lock icon — editable (override) but not deletable
- Custom agents can be fully edited or deleted
- "+" FAB to create new agent

### Builtin Agents (pre-seeded, overridable)

| Name | Tools | Temperature | Purpose |
|------|-------|-------------|---------|
| Architect | read_file, list_directory, search_web, fetch_url, memory_* | 0.7 | System design, specs |
| Coder | run_sh, read_file, write_file, list_directory, search_web, fetch_url | 0.3 | Implementation |
| Researcher | read_file, search_web, search_images, fetch_url, memory_* | 0.5 | Research, summarize |
| QA | run_sh, read_file, write_file, list_directory | 0.3 | Testing, bug finding |
| DevOps | run_sh, read_file, write_file, ssh_*, fetch_url | 0.3 | Infrastructure |
| Security | run_sh, read_file, list_directory, search_web, fetch_url | 0.4 | Auditing |
| Writer | read_file, write_file, list_directory, search_web | 0.6 | Documentation |
| Reviewer | read_file, list_directory, search_web, fetch_url | 0.4 | Code review |

### Agent Editor (Create/Edit)

Fields:
- **Name** — string, required
- **System Prompt** — multiline text, defines personality and behavior
- **Model** — picker from available gateway models (or "default" = active model)
- **Tools** — checklist of all available tools (subset selection)
- **Max Context** — slider/input (4K–256K)
- **Temperature** — slider (0.0–2.0)
- **Top P** — slider (0.0–1.0)
- **Top K** — input (0–256, 0 = off)

### Import/Export
- Export agent as JSON (share to other AIOPE installs)
- Import from JSON file or URL
- "Duplicate" action on any agent → creates custom copy for editing

---

## Chained Triggers / Orchestration (DAG Pipelines)

Proven pattern from ax — include from day one.

### `orchestrate` Tool — Deep Dive

The orchestrate tool is a **synchronous blocking call** from the calling agent's perspective. When the LLM calls `orchestrate(...)`, the tool call blocks until ALL stages complete.

#### DAG Resolution Algorithm (Wavefront Execution)

```
while (completed < total_stages):
    ready = stages where ALL deps are in completed set
    if no ready stages → deadlock, break
    run all ready stages in parallel (coroutines)
    awaitAll parallel stages
    loop
```

Each iteration identifies a "wave" of parallelizable stages:
- Wave 1: all stages with no deps (run in parallel)
- Wave 2: stages whose deps were all in wave 1
- Wave 3: stages whose deps were all in waves 1+2, etc.

#### Context Injection Between Stages

Dependency results are concatenated into the dependent stage's prompt:
```
Original prompt + "\n\nContext from prior stages:\n--- research result ---\n{result}\n--- design result ---\n{result}\n"
```

This is how information flows between stages — downstream agents see upstream outputs.

#### Edge Cases

| Case | Behavior |
|------|----------|
| No deps on any stage | All run in parallel (single wave) |
| Linear chain A→B→C | Sequential, one per wave |
| Fan-out A→[B,C] | A first, then B+C parallel |
| Fan-in [A,B]→C | A+B parallel, then C with both results |
| Circular A→B→A | Deadlock detected, returns partial results |
| Stage timeout (5min) | Result = "timeout", dependents still run with that text |
| Stage error | Result = "error: ...", dependents see error text |
| Empty stages array | Returns empty string immediately |

#### Mobile-Specific Challenges

| Challenge | Solution |
|-----------|----------|
| Long duration (25+ min for 5 stages) | Run in foreground service with notification |
| Memory (5 agents × 32K context) | Limit agent context to 16K, stream to disk |
| Cellular drops mid-pipeline | Retry logic per-stage, resume from last completed wave |
| Background kill | Persist pipeline state to Room, resume on relaunch |
| User cancellation | Cancel entire pipeline OR individual stages via coroutine cancellation |

#### Kotlin Implementation

```kotlin
class PipelineExecutor(
    private val agentExecutor: AgentExecutor,
    private val onProgress: (String) -> Unit
) {
    suspend fun runPipeline(stages: List<Stage>): String = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, String>()
        val completed = ConcurrentHashMap<String, Boolean>()

        while (completed.size < stages.size) {
            val ready = stages.filter { s ->
                !completed.containsKey(s.name) &&
                s.dependsOn.all { completed.containsKey(it) }
            }
            if (ready.isEmpty()) break // deadlock

            // Run wave in parallel
            ready.map { stage ->
                async {
                    val ctx = stage.dependsOn.joinToString("\n") {
                        "--- $it result ---\n${results[it]}\n"
                    }
                    val prompt = stage.prompt +
                        if (ctx.isNotBlank()) "\n\nContext from prior stages:\n$ctx" else ""

                    onProgress("[${stage.name}] started (${stage.agent})")

                    val result = withTimeoutOrNull(5 * 60 * 1000L) {
                        agentExecutor.runAgent(stage.agent, prompt)
                    } ?: "timeout"

                    results[stage.name] = result
                    completed[stage.name] = true
                    onProgress("[${stage.name}] completed")
                }
            }.awaitAll()
        }

        stages.joinToString("\n\n") { "## ${it.name} (${it.agent})\n${results[it.name]}" }
    }
}
```

Key design choices:
- `withTimeoutOrNull` — clean 5-min timeout per stage
- `async/awaitAll` — parallel stages within each wave, blocks until wave completes
- Coroutine cancellation propagates cleanly (no manual cancel flag needed)
- `ConcurrentHashMap` for thread-safe result/completion tracking
- Progress callback for live UI updates in Monitor tab
- Each spawned agent gets its own `StreamingOrchestrator` + tool loop (up to 20 turns)

### `orchestrate` Tool

The primary agent (or any spawned agent) can call the `orchestrate` tool to design and execute a multi-agent pipeline as a DAG:

```json
{
  "name": "orchestrate",
  "arguments": {
    "task": "Build and test the new auth module",
    "stages": [
      {"name": "research", "agent": "Researcher", "prompt": "Find best practices for JWT auth in Android"},
      {"name": "design", "agent": "Architect", "prompt": "Design the auth module based on research", "depends_on": ["research"]},
      {"name": "implement", "agent": "Coder", "prompt": "Implement the auth module per the design", "depends_on": ["design"]},
      {"name": "test", "agent": "QA", "prompt": "Write and run tests for the auth module", "depends_on": ["implement"]},
      {"name": "review", "agent": "Reviewer", "prompt": "Review the implementation for quality", "depends_on": ["implement"]}
    ]
  }
}
```

**Execution:**
- Stages without `depends_on` run in parallel
- Dependent stages wait for all deps to complete
- Results from completed stages are passed as context to dependents
- Returns combined results from all stages
- Deadlock detection (no progress = abort)
- 5-minute timeout per stage

**Implementation:**
```kotlin
// In AgentExecutor.kt
fun runPipeline(task: String, stages: List<Stage>, onProgress: (String) -> Unit): String {
    val results = ConcurrentHashMap<String, String>()
    val completed = ConcurrentHashMap<String, Boolean>()
    
    while (completed.size < stages.size) {
        val ready = stages.filter { s ->
            !completed.containsKey(s.name) && s.dependsOn.all { completed.containsKey(it) }
        }
        if (ready.isEmpty()) break // deadlock
        
        runBlocking {
            ready.map { stage ->
                async(Dispatchers.IO) {
                    val ctx = stage.dependsOn.joinToString("\n") { 
                        "--- ${it} result ---\n${results[it]}\n" 
                    }
                    val prompt = stage.prompt + if (ctx.isNotBlank()) "\n\nContext:\n$ctx" else ""
                    onProgress("[${stage.name}] started (${stage.agent})")
                    val result = spawnAndWait(stage.agent, prompt)
                    results[stage.name] = result
                    completed[stage.name] = true
                    onProgress("[${stage.name}] completed")
                }
            }.awaitAll()
        }
    }
    
    return stages.joinToString("\n\n") { "## ${it.name} (${it.agent})\n${results[it.name]}" }
}
```

### `spawn_agent` Tool (Recursive)

Any agent — including spawned agents — can spawn sub-agents:

```json
{
  "name": "spawn_agent",
  "arguments": {
    "agent": "coder",
    "task": "Implement the database migration for the new schema"
  }
}
```

**Behavior:**
- Blocking — spawning agent waits for sub-agent to finish
- Sub-agent gets its own tool loop (up to 20 turns)
- Sub-agent can also spawn further sub-agents (max depth: 3)
- Result returned as tool output to the parent agent
- Timeout: 5 minutes per spawn

**Tool definitions available to spawned agents:**
- `run_sh` — shell commands
- `read_file` / `write_file` / `list_directory` — filesystem
- `search_web` / `fetch_url` — web access
- `spawn_agent` — recursive delegation (depth-limited)
- NOT: `orchestrate` (only top-level or user-triggered)

### Depth Limiting

```
User → Primary Agent (depth 0)
  └→ orchestrate / spawn_agent → Agent A (depth 1)
       └→ spawn_agent → Agent B (depth 2)
            └→ spawn_agent → Agent C (depth 3, max — cannot spawn further)
```

Prevents infinite recursion. Depth tracked in task context.

---

## Tool Definitions (Agent System)

Add to `ToolExecutor.buildToolDefs()`:

```kotlin
ToolDef("spawn_agent", "Spawn a sub-agent to work on a subtask. Blocks until complete. Available agents: architect, coder, researcher, qa, security, devops, writer, reviewer.",
    """{"type":"object","required":["agent","task"],"properties":{"agent":{"type":"string","description":"Agent name"},"task":{"type":"string","description":"Task description"}}}"""),

ToolDef("orchestrate", "Execute a multi-agent pipeline (DAG). Stages run in parallel where possible. Results flow between dependent stages.",
    """{"type":"object","required":["task","stages"],"properties":{"task":{"type":"string","description":"Overall task"},"stages":{"type":"array","description":"[{name, agent, prompt, depends_on:[]}]","items":{"type":"object"}}}}"""),
```

---

## File Structure

```
feature-chat/
  src/main/kotlin/com/aiope2/feature/chat/
    agents/
      AgentPanel.kt          — Main Compose panel (3-tab layout)
      AgentRoster.kt         — Agents tab composable
      AgentBuilder.kt        — Create/edit agent sheet
      MonitorTab.kt          — Monitor tab composable
      TaskDetail.kt          — Expandable task detail
      TimerTab.kt            — Timers tab composable
      TimerEditor.kt         — Create/edit timer sheet
      AgentSchedulerWorker.kt — WorkManager worker
      AgentExecutor.kt       — Runs agent tasks (wraps StreamingOrchestrator)
    db/
      ChatDatabase.kt        — Add new entities + migration
      AgentDao.kt            — CRUD for agents, tasks, schedules
```

---

## Migration Path

### Existing Features to Evolve (Already in AIOPE)

The following are already implemented and need to be **upgraded**, not built from scratch:

**1. SubagentManager → AgentExecutor**
- Current: `SubagentManager.runBlocking(description, prompt)` — generic subagent, no agent identity
- Evolve: Accept agent name, resolve config from roster, apply per-agent model/tools/temperature
- Keep: Stage tracking (SEARCHING → READING → SUMMARIZING → FINISHED), `StateFlow<List<Task>>`
- Change: Tasks persist to Room instead of in-memory only

**2. `task` tool → `spawn_agent` tool**
- Current: `td("task", ...)` with `description` + `prompt` params
- Evolve: Rename to `spawn_agent`, add `agent` param (name from roster)
- Keep: PARALLEL_SAFE (multiple spawns run concurrently), blocking return with `<task_result>` tags
- Add: Depth tracking for recursive spawn limiting

**3. SubagentCard → Monitor tab rows**
- Current: Inline cards in chat stream showing stage indicators
- Evolve: Move to dedicated Monitor tab, add steering input, add stop/respawn
- Keep: Compact strip layout with pulsing active stage, expandable result
- Add: Elapsed time, full tool call log, steering message injection

**4. AgentMode (Chat/Plan/Build) — Keep As-Is**
- Already working correctly with tool gating and system prefixes
- No changes needed — agent panel is additive, doesn't replace modes

**5. Read-only tool subset — Evolve to per-agent**
- Current: `getReadOnlyTools()` returns fixed set for all subagents
- Evolve: Each agent in roster defines its own tool subset
- Default subagents (no agent name) still get read-only tools as fallback

**6. StreamingOrchestrator — Reuse for agent execution**
- Current: Used by both primary chat and subagents
- Keep: SSE streaming, tool loop (140 rounds primary, 20 rounds subagent), retry logic
- Add: `PipelineExecutor` wraps multiple `StreamingOrchestrator` instances for DAG stages

### New Features to Build

| Priority | Feature | Depends On |
|----------|---------|------------|
| 1 | Agent roster (Room entity + builtin seeds) | Database migration |
| 2 | Agent Builder tab (CRUD UI) | Roster entity |
| 3 | Spawn tab (agent picker + task input) | Roster |
| 4 | Rename `task` → `spawn_agent` with agent param | Roster |
| 5 | Monitor tab (move SubagentCards, add steering) | Existing SubagentManager |
| 6 | Task persistence (Room entity) | Monitor tab |
| 7 | Toolbar icon + Agent Panel shell (4 tabs) | All tabs ready |
| 8 | `orchestrate` tool (DAG pipeline executor) | Spawn working with named agents |
| 9 | Timers tab + WorkManager scheduler | Roster + AgentExecutor |
| 10 | Recursive spawn (depth limit) | spawn_agent working |

### Implementation Steps

1. **Database migration** (Room version N → N+1): create `agents`, `agent_tasks`, `scheduled_tasks` tables
2. **Seed builtin agents** on first launch after update (8 agents with prompts/tools/params)
3. **Refactor SubagentManager → AgentExecutor**: accept agent name, resolve from roster, apply config
4. **Rename `task` tool → `spawn_agent`** with new `agent` param, keep backward compat (missing agent = "default")
5. **Add toolbar icon** and panel toggle in `ChatScreen.kt`
6. **Implement AgentPanel** with four tabs (Spawn, Monitor, Timers, Builder)
7. **Move SubagentCards into Monitor tab** with steering input and task persistence
8. **Build PipelineExecutor** for `orchestrate` tool (DAG resolution + parallel waves)
9. **Add WorkManager** scheduler for timers
10. **Test on device** with: simple spawn → named agent spawn → 3-stage pipeline → daily timer

---

## UX Notes

- Panel uses same slide-up animation and drag gesture as Browser/Terminal
- When panel is open, chat input stays above it (same as current behavior)
- Agent panel icon shows badge with count of running tasks
- Monitor auto-scrolls to newest task when spawned
- Timers show relative next-run time ("in 2h 15m")
- Long-press agent in roster → quick-spawn with just a prompt input
- Panel remembers last-open tab between sessions

## UI Pattern Reference: Terminal Panel

Follow the same multi-tab pattern as `TerminalPanel.kt`:

```
┌──────────────────────────────────────┐
│ [Agents] [Monitor] [Timers]          │  ← Tab row (TextButton per tab, primary color = selected)
├──────────────────────────────────────┤
│                                      │
│  Tab content area                    │  ← Composable swapped based on selectedTab
│  (scrollable, fills available space) │
│                                      │
├──────────────────────────────────────┤
│ [Steering input]                     │  ← Only visible in Monitor when task selected & running
└──────────────────────────────────────┘
```

**Key patterns from TerminalPanel to reuse:**

1. **State holder singleton** — like `TerminalSessionHolder`, create `AgentPanelHolder` that survives recomposition:
   - Holds selected tab index
   - Holds active task selections
   - Persists across panel open/close cycles

2. **Tab switching** — `Row` of `TextButton`s, `selectedTabId` state, content swapped via `when(selectedTab)`:
   ```kotlin
   Row(Modifier.fillMaxWidth()) {
       tabs.forEach { tab ->
           TextButton(onClick = { selectedTab = tab.id }) {
               Text(tab.name, color = if (tab.id == selectedTab) cs.primary else Color.Gray)
           }
       }
   }
   ```

3. **Panel height states** — three modes like terminal:
   - **Collapsed**: ~48dp (tab bar only, shows badge counts)
   - **Half**: 40% of screen (default on first open)
   - **Full**: expands to title bar (drag gesture or expand button)
   - Height persisted in preferences between sessions

4. **Content weight** — tab content uses `Modifier.weight(1f).fillMaxWidth()` to fill between tab row and optional input bar

5. **Session persistence** — each spawned task keeps its event log in memory (like `TerminalSession` stays alive across tab switches). Monitor tab can switch between tasks without losing scroll position or streaming state.

6. **Panel integration in ChatScreen** — same conditional rendering pattern. All panels (Terminal, Browser, Agent) can be visible simultaneously — chat content shrinks via `weight(1f)` to accommodate:
   ```kotlin
   // In ChatScreen.kt portrait Column
   Column {
       ChatContent(modifier = Modifier.weight(1f))  // shrinks as panels open
       if (agentPanelVisible) {
           AgentPanel(modifier = Modifier.fillMaxWidth().height(panelHeight.dp))
       }
       if (terminalVisible) {
           TerminalPanel(modifier = Modifier.fillMaxWidth().height(240.dp))
       }
   }
   if (browserVisible) {
       BrowserPanel(modifier = ...)
   }
   ```
   - Agent panel: 240dp default (same as terminal), expandable to title bar
   - All three panels can coexist — chat area shrinks to fit
   - Panel order bottom-up: terminal → agent panel (agent sits above terminal when both open)
   - Maximize mode: agent panel can go full-screen like browser does
