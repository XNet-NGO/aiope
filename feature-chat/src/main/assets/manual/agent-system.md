# Agent System (Multi-Agent Orchestration)

keywords: agent, agents, sub-agent, subagent, multi-agent, orchestration, orchestrate, pipeline, DAG, wavefront, roster, builtin agents, Architect, Coder, Researcher, QA, DevOps, Security, Writer, Reviewer, spawn, monitor, steer, timer, scheduled task, schedule, AlarmManager, WorkManager, background run, AgentPanel, AgentExecutor, PipelineExecutor, AgentScheduler, AgentRunWorker

The Agent System lets AIOPE run named agents on tasks — either interactively from the Agent panel, as a chat tool the main model calls (`orchestrate`, `schedule_task`), or automatically in the background on a timer. Each agent has its own system prompt, tool allow-list, and model/sampling config. This page documents the panel UI, the builtin roster, the orchestrate pipeline, and scheduling — all verified against the Kotlin source under `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/`.

## Overview

Key source files:

- `AgentPanel.kt` — the four-tab UI (Spawn, Monitor, Timers, Builder).
- `engine/AgentExecutor.kt` — resolves an agent from the roster and runs it to completion, streaming its output.
- `engine/PipelineExecutor.kt` — runs a multi-agent DAG pipeline with parallel wavefront scheduling (the `orchestrate` tool).
- `engine/AgentScheduler.kt` — exact-alarm scheduler; computes next run and (re)arms `AlarmManager` alarms.
- `engine/AgentAlarmReceiver.kt` — `BroadcastReceiver` that fires when an alarm goes off and enqueues the worker.
- `engine/AgentRunWorker.kt` — `CoroutineWorker` (WorkManager) that executes one scheduled run in the background.
- `engine/AgentRescheduleWorker.kt` — re-arms all enabled task alarms on app start / after reboot.
- `db/AgentSeeder.kt` — seeds the 8 builtin agents.

Data is persisted in Room (`ChatDatabase.kt`) across four entities: `AgentEntity` (agents table), `AgentTaskEntity` (agent_tasks — run history / monitor), `ScheduledTaskEntity` (scheduled_tasks — timers), and `TaskRunEntity` (task_runs — per-run context carry-over).

## Agent Panel — Four Tabs

`AgentPanel` shows a tab row with exactly four tabs (in order): **Spawn**, **Monitor**, **Timers**, **Builder** (`val tabs = listOf("Spawn", "Monitor", "Timers", "Builder")`).

### Tab 1 — Spawn

Launch a single agent on an ad-hoc task.

- **Agent picker** — a dropdown listing `default` plus every roster agent by name.
- **Task input** — free-text field ("Describe the task...").
- **Spawn button** — enabled only when the task text is non-blank; calls `onSpawn(selectedAgent, taskText)` and clears the field.

The default selected agent is `default`.

### Tab 2 — Monitor

Live and historical task view. Combines in-memory running tasks (`AgentExecutor.RunningTask`) with persisted history (`AgentTaskEntity`).

- Shows running tasks first, then persisted history. History is capped: `maxHistory = 30`, and visible persisted rows = `(30 - runningTasks.size)` (persisted tasks whose id is already running are filtered out). Empty state reads "No tasks yet".
- Each row shows agent name, a short description/prompt, and a color-coded status dot:
  - finished → green (`0xFF4CAF50`)
  - failed / error → red (`0xFFFF5252`)
  - running → amber (`0xFFFFB74D`)
  - otherwise → grey.
- **Running task dialog** — shows prompt, a live-streaming Markdown **Output** box (auto-scrolls as `task.result` updates), and a **Steer** input to inject a mid-run message (`onSteer(task.id, text)`). When the task is finished/errored it offers **Rerun**; while running it offers **Cancel Task**.
- **Persisted task dialog** — shows prompt and final **Result**, plus a **Steer** input (works after completion to re-engage the agent) and a **Rerun** button.

### Tab 3 — Timers

Create, edit, and delete scheduled agent tasks (`ScheduledTaskEntity`). Empty state: "No timers yet / Schedule an agent to run automatically."

Each timer row shows the agent name, prompt, a human-readable schedule description, run progress (`runsCompleted/maxRuns` or `runsCompleted runs`), the next-run time, and a summary of enabled tools (first 3, "+N" for the rest, or "· no tools"). Status dot colors: running → blue, failed → red, finished/cap-reached → green, otherwise grey.

The **New Timer / Edit Timer** dialog fields:

- **Agent** — chosen from the roster via filter chips (falls back to name "Timer Agent" if none).
- **Prompt** — what the agent should do each run.
- **Schedule** — one of five types (see below).
- **Limits** — `max runs` roller, range `0..60` (0 = unlimited).
- **Tools** — grouped chips (see Timer tool groups below); default selection is **all** timer tools. If none are selected: "No tools — the agent can only reason and produce text."
- A live **summary** line describes the resulting schedule, run cap, and tool count.

**Schedule types** (`scheduleTypes = ["once", "interval", "daily", "weekly", "monthly"]`):

| Type | Controls | Behavior |
| --- | --- | --- |
| `once` | none | Runs a single time, ~60 seconds after saving |
| `interval` | value roller `1..720` + unit (min / hour / day) | Every N units |
| `daily` | hour `0..23`, minute `0..59` | Daily at HH:MM |
| `weekly` | time + weekday chips | Weekly on selected days at HH:MM (default Mon–Fri) |
| `monthly` | time + day-of-month roller `1..28` | Monthly on that day at HH:MM |

Weekday values are `1=Mon … 7=Sun` in the dialog chips. Time/number pickers are `NumberRoller` spinners (▲/▼).

**Timer tool groups** (`timerToolGroups`) — the limited tool set available to background runs:

- **Web**: `search_web` (Search web), `fetch_url` (Fetch URL)
- **Actions**: `run_sh` (Shell), `ssh_exec` (Remote SSH), `send_notification` (Notify), `set_alarm` (Alarm)
- **Memory**: `memory_store` (Store fact), `memory_recall` (Recall)

That is 8 timer tools total. This is intentionally a subset of the full tool catalog — background runs only implement these (see `AgentRunWorker.workerToolCatalog`).

### Tab 4 — Builder

Manage the agent roster. Lists every agent; builtins are tagged "builtin" and cannot be deleted (delete/`onDelete` is gated on `!agent.builtin`). A "+" creates a new blank agent.

**Agent editor dialog** fields (map to `AgentEntity`):

- **Name** (single line)
- **System Prompt** (multi-line, min 3 lines)
- **Model** — picker; "(use active)" means empty string = use the active provider's model.
- **Tools** — grouped selectable chips. Builder tool groups (`builderToolGroups`):
  - **Web**: `search_web`, `search_images`, `search_location`, `fetch_url`
  - **Files**: `read_file`, `list_directory`, `write_file`
  - **Execute**: `run_sh`, `run_proot`, `ssh_exec`
  - **Memory**: `memory_recall`, `memory_store`, `query_data`
  - **Media**: `image_generate`, `analyze_image`
  - **Browser**: `browser_content`, `browser_elements`, `browser_click`, `browser_fill`, `browser_eval`
- **Temperature** slider `0..2`
- **Top P** slider `0..1`
- **Top K** slider `0..100`
- **Max Context** slider `4000..128000` (shown in "k")

Save requires a non-blank name and prompt.

## Builtin Roster (8 Agents)

`AgentSeeder.kt` seeds exactly **8** builtin agents on launch (it upserts all builtins every time so prompt/tool updates take effect). Each has a stable id derived from `UUID.nameUUIDFromBytes("builtin-<name>")`. Exact names and configs:

| Name | Purpose (role) | Tools | Temp |
| --- | --- | --- | --- |
| **Architect** | Senior software architect — analyze requirements, design systems, produce plans (never writes code) | `read_file, list_directory, search_web, fetch_url` | 0.5 |
| **Coder** | Expert implementation agent — write complete, production-ready code; verify by build/test | `read_file, list_directory, write_file, run_sh, run_proot, ssh_start, ssh_exec, search_web, fetch_url` | 0.3 |
| **Researcher** | Research & analysis — search, fetch, synthesize findings with source attribution | `search_web, search_images, fetch_url, search_location, read_file, list_directory, ssh_start, ssh_exec` | 0.7 |
| **QA** | Quality assurance & testing — find bugs, write and run tests | `read_file, list_directory, run_sh, run_proot, write_file, ssh_start, ssh_exec` | 0.2 |
| **DevOps** | Infrastructure & operations — deploy, CI/CD, containers, cloud | `read_file, list_directory, write_file, run_sh, ssh_start, ssh_exec, fetch_url` | 0.3 |
| **Security** | Security audit — vulnerabilities, OWASP/CWE, CVEs, remediation | `read_file, list_directory, run_sh, ssh_start, ssh_exec, search_web, fetch_url` | 0.2 |
| **Writer** | Technical documentation — clear, example-driven docs | `read_file, list_directory, write_file, search_web, fetch_url` | 0.6 |
| **Reviewer** | Senior code review — correctness, style, performance, maintainability | `read_file, list_directory, search_web, fetch_url` | 0.4 |

Each builtin's system prompt identifies it as `AIOPE:<role>`. All are marked `builtin = true`.

## How an Agent Runs — AgentExecutor

`AgentExecutor.runAgent(agentName, prompt, conversationId?)`:

1. Resolves the agent by name via `dao.getAgentByName`. If the name is unknown and is not `"default"`, it returns `<task_error>Unknown agent '…'. Available agents: …</task_error>` listing the roster.
2. Resolves the allowed tool set (`resolveTools`) and sampling config (`resolveConfig`).
3. Creates a `RunningTask` (8-char id) and persists an `AgentTaskEntity` with status `running`.
4. Streams via a `StreamingOrchestrator`; each tool call is checked against the allow-list before executing.
5. Advances a **Stage** as work progresses.
6. On completion updates the task to `FINISHED` and returns `<task_result>…</task_result>`; on exception returns `<task_error>…</task_error>` and marks it `failed`.

**Stages** (`enum Stage`): `QUEUED, SEARCHING, READING, EXECUTING, SUMMARIZING, FINISHED, ERROR`. Tool calls map to stages — search tools → SEARCHING; `fetch_url`/`read_file`/`list_directory` → READING; `write_file`/`run_sh`/`run_proot`/`ssh_exec` → EXECUTING; output past 200 chars → SUMMARIZING.

**Tool resolution rules:**

- If the agent has tools configured, the allowed set is the agent's tools intersected with `allToolNames`. If the agent has no tools listed, it falls back to `readOnlyTools`.
- If the agent is not in the roster (name resolved to null), only `readOnlyTools` are allowed — this forces the use of proper roster agents for privileged actions.
- `allToolNames` (interactive catalog, 17 tools): `search_web, search_images, search_location, fetch_url, read_file, list_directory, query_data, memory_recall, write_file, run_sh, run_proot, ssh_start, ssh_exec, image_generate, analyze_image, browser_content, browser_elements`.
- `readOnlyTools` (8): `search_web, search_images, search_location, fetch_url, read_file, list_directory, query_data, memory_recall`.

**Default config** (`AgentConfig`): temperature 0.7, topP 0.9, topK 0, maxContext 32000.

If the agent produces no final text, the executor falls back to the captured tool-result log (extracting up to 20 Markdown images, then up to 3000 chars of cleaned text).

## The `orchestrate` Tool — Multi-Agent DAG Pipeline

`PipelineExecutor` powers the chat tool `orchestrate` (registered in `ToolExecutor.kt`):

> "Run a multi-agent DAG pipeline. Each stage dispatches a named roster agent; stages without depends_on run in parallel; results flow to dependent stages."

**Arguments:** `task` (overall description) and `stages` — an array of stage objects, each with:

- `name` — unique stage name (required)
- `agent` — one of `Architect, Coder, Researcher, QA, DevOps, Security, Writer, or Reviewer` (required; defaults to `default`)
- `prompt` — the task for this stage (required)
- `depends_on` — array of stage names this stage waits on (optional)

**Execution model** (`runPipeline`, runs on `Dispatchers.IO`):

1. **Wavefront scheduling** — repeatedly find all stages whose dependencies are already completed ("ready"), and run them **in parallel** (`async { … }.awaitAll()`).
2. **Context flow** — a dependent stage receives the outputs of each dependency prepended as "Context from prior stages" blocks.
3. **Per-stage timeout** — each stage runs under `withTimeoutOrNull(5 * 60 * 1000L)`; on expiry it yields "timeout: stage exceeded 5 minute limit".
4. **Deadlock detection** — if no stage is ready but stages remain (a cycle / unsatisfiable `depends_on`), it reports "Deadlock detected: <stuck stage names>" and stops.
5. **Combined result** — stages are joined in order as Markdown: `## <name> (<agent>)` followed by each stage's (tag-stripped) result.

`parseStages` accepts both native `List/Map` and `org.json.JSONArray/JSONObject` shapes; stages missing `name` or `prompt` are dropped. Progress is logged via the `onProgress` callback.

## Scheduling (Timers) — AlarmManager → WorkManager

Scheduled agent tasks (`ScheduledTaskEntity`) run in the background even when the app is closed. The pipeline is: **`AgentScheduler` arms an exact `AlarmManager` alarm → `AgentAlarmReceiver` fires → enqueues `AgentRunWorker` (WorkManager) → the worker runs the agent and re-arms the next alarm.**

### AgentScheduler

- Each enabled task owns one alarm, a `PendingIntent` → `AgentAlarmReceiver` keyed by `taskId.hashCode()` (action `ngo.xnet.aiope.action.AGENT_ALARM`, extra `EXTRA_TASK_ID`).
- `computeNextRun` returns the next fire time, or `null` when the schedule is done (once, or max-runs cap reached):
  - `interval` → now + `intervalValue × unit` (min = 60_000 ms, hour = 3_600_000, day = 86_400_000)
  - `daily` → next wall-clock HH:MM
  - `weekly` → next HH:MM on an allowed weekday (`daysOfWeek`, Calendar 1=Sun..7=Sat)
  - `monthly` → next `dayOfMonth` (clamped 1..28) at HH:MM
  - `once` → now + 60_000 ms
- `schedule` uses `setExactAndAllowWhileIdle(RTC_WAKEUP, …)` when exact alarms are permitted, else `setAndAllowWhileIdle` (inexact fallback). On Android 12+ (`S`), exact alarms require `SCHEDULE_EXACT_ALARM` (`canScheduleExact`).
- When the schedule is complete, `schedule` returns a disabled copy (`enabled = false, nextRun = null, status = "finished"`).

### AgentAlarmReceiver

A lightweight `BroadcastReceiver`: on receive it reads the task id and enqueues a one-time `AgentRunWorker` as unique work `"agent-run-<taskId>"` with `ExistingWorkPolicy.REPLACE`.

### AgentRunWorker

A `CoroutineWorker` that executes **one** scheduled run:

1. Loads the task; skips if not `enabled`.
2. Inserts a `running` `AgentTaskEntity` (so the run appears in the Monitor tab) linked via `scheduledTaskId`.
3. Resolves the active provider; errors if none configured.
4. Builds the system prompt from the agent's prompt plus an **Environment** block (date/time, "Android (AIOPE scheduled task)", background execution), a **Recurring Task** instruction to report what changed since last run, and tool guidance.
5. **Context carry-over** — includes the last `MAX_CONTEXT_RUNS = 5` prior run outputs (from `TaskRunEntity`) as a "Run Context" section.
6. Streams the agent via `StreamingOrchestrator`, executing only the timer-allowed tools.
7. Persists a `TaskRunEntity` (output truncated to `OUTPUT_TRUNCATE = 4000`), advances `runsCompleted`, sets status (`finished`/`failed`/`scheduled`), and either **re-arms** the next alarm or **cancels** and marks the task finished (when `once` or the max-runs cap is reached).
8. Shows a notification (channel `agent_tasks`) summarizing the run.
9. Retries on exception up to `MAX_RETRIES = 3` (`Result.retry()`), then `Result.failure()`.

**Background worker tool catalog** (`workerToolCatalog`, 8 tools, kept in sync with `executeWorkerTool`): `search_web`, `fetch_url`, `run_sh` (shell, timeout clamped 1..120 s), `ssh_exec` (remote SSH), `send_notification`, `set_alarm`, `memory_store`, `memory_recall`. These match the Timer tab's tool groups. Any other tool returns "not available in background mode".

### AgentRescheduleWorker

Exact alarms do not survive reboot or force-stop. `AgentRescheduleWorker` re-arms alarms for every enabled scheduled task; it runs once on app start and after `BOOT_COMPLETED`.

## Chat Tools for Scheduling

Besides `orchestrate`, `ToolExecutor.kt` exposes scheduling tools the main model can call directly:

- `schedule_task` — schedule the agent to run a prompt automatically (same `schedule_type` options and `tools` subset as the Timers tab; `max_runs` 0 = unlimited; each run reports via notification).
- `list_schedules` — list all scheduled tasks with recurrence description, next run, and progress.
- `cancel_schedule` — cancel and delete a scheduled task by id (also cancels its pending alarm).

## Where Agents Are Available (Modes)

`AgentMode.kt` defines four chat modes: **Chat**, **Plan**, **Build**, **Media**. The orchestration and scheduling tools (`orchestrate`, `schedule_task`, `cancel_schedule`, `list_schedules`) are part of `PLAN_EXTRA`, so they default ON in **Plan** and **Build** modes, default OFF in **Chat** (unless the user enables them in Settings → Tools), and are entirely disabled in **Media** (which exposes no tools). Users override tool enablement per-tool-per-mode.
