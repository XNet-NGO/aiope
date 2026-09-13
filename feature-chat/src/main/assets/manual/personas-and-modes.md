# Personas & Operating Modes

keywords: persona, personas, mode, modes, operating mode, agent mode, Chat, Plan, Build, Media, AgentMode, AiopePersona, system prompt, identity, personality, About You, user context, user_info, environment, projects, buildAgentPrompt, buildSystemMessages, forMode, systemPrefix, disablesAllTools, prompt caching, timestamp, date time, dynamic UI, RAG directive, remote context, tool defaults, CHAT_DEFAULT, PLAN_DEFAULT, PLAN_EXTRA

AIOPE's character is not user-authored free text — it is a set of fixed, built-in personas baked into the code, one per operating mode. The user only supplies personal context ("About You"). Every chat turn assembles a system prompt from the mode's persona, the user context, a knowledge-base directive, remote-server context, and a volatile timestamp. This page documents the four modes, the built-in personas, the editable user fields, and the exact assembly order — all verified against the Kotlin source under `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/`.

## Overview

Key source files:

- `engine/AgentMode.kt` — the four modes (`CHAT`, `PLAN`, `BUILD`, `MEDIA`), their provider category, per-mode tool defaults, the `disablesAllTools` flag, and each mode's `systemPrefix`.
- `settings/AgentDefaults.kt` — the `AGENT_SECTIONS` "About You" fields (user-editable) and the `AiopePersona` object (fixed built-in personas).
- `settings/AgentScreen.kt` — the Agent settings UI and `buildAgentPrompt(dao, mode, dynamicUiEnabled)` = persona + user context.
- `ChatViewModel.buildSystemMessages(...)` — final system-message assembly (persona, RAG directive, remote context, timestamp).

A key design point: the user does **not** describe the assistant's character anywhere. The comment in `AgentDefaults.kt` states this directly — "AIOPE's own identity/behavior now lives in fixed, built-in per-mode personas … The Agent settings screen exposes ONLY the user's personal context." (An older `agent_prompt` key is kept in `AgentScreen.kt` only for migration and is not used to build the prompt.)

## The Four Operating Modes

`AgentMode` is an enum with exactly four entries, each with a display `label`:

| Mode | Label | Provider category | Tools exposed |
| --- | --- | --- | --- |
| `CHAT` | "Chat" | `TEXT` | Per-mode defaults (`CHAT_DEFAULT`), user-overridable |
| `PLAN` | "Plan" | `TEXT` | `PLAN_DEFAULT` (= `CHAT_DEFAULT` + `PLAN_EXTRA`), user-overridable |
| `BUILD` | "Build" | `TEXT` | All tools default-on, user-overridable |
| `MEDIA` | "Media" | `MEDIA` | **None** (`disablesAllTools == true`) |

Derived properties on each mode:

- `providerCategory` — `MEDIA` maps to `ProviderCategory.MEDIA`; every other mode maps to `ProviderCategory.TEXT`. This drives the model picker and generation path.
- `disablesAllTools` — `true` only for `MEDIA`. In Media mode no tools are exposed to the model at all.
- `key` — the lowercased enum name (`"chat"`, `"plan"`, `"build"`, `"media"`); used as the stable key for per-mode tool-enablement settings.

### Per-mode tool defaults

The defaults only **seed** the settings the first time and act as the fallback when a tool has no explicit per-mode setting. Users override tool enablement per-tool-per-mode in Settings → Tools. From `AgentMode.Companion`:

- `CHAT_DEFAULT` — 41 tool ids: `read_file`, `list_directory`, `get_location`, `device_info`, `datetime_now`, `fetch_url`, `query_data`, `search_location`, `search_web`, `search_images`, `browser_navigate`, `browser_content`, `browser_elements`, `browser_click`, `browser_fill`, `browser_eval`, `browser_back`, `browser_scroll`, `browser_open`, `browser_close`, `browser_maximize`, `memory_store`, `memory_recall`, `memory_forget`, `rag_search`, `analyze_image`, `image_generate`, `read_calendar`, `create_event`, `delete_event`, `set_alarm`, `dismiss_alarm`, `read_contacts`, `send_notification`, `clipboard_copy`, `clipboard_read`, `read_sms`, `send_sms`, `delete_sms`, `media_control`, `open_intent`. (Described in-code as "everyday phone assistant: reads, search, browsing, memory, image gen, and light non-destructive device actions.")
- `PLAN_EXTRA` — 11 tool ids added on top of Chat: `write_file`, `edit_file`, `search_files`, `http_request`, `rag_index`, `todo_write`, `todo_read`, `schedule_task`, `cancel_schedule`, `list_schedules`, `orchestrate`. (Research/authoring/task-setting tools.)
- `PLAN_DEFAULT` — defined as `CHAT_DEFAULT + PLAN_EXTRA`.

`toolDefaultEnabled(mode, toolId)` resolves the default:

- `BUILD` → always `true` (every tool defaults on).
- `MEDIA` → always `false` (nothing).
- `CHAT` → `toolId in CHAT_DEFAULT`.
- `PLAN` → `toolId in PLAN_DEFAULT`.

### Mode `systemPrefix`

Each mode carries a `systemPrefix` string:

- `CHAT` → empty string (`""`). Chat adds no prefix; its behavior comes entirely from the persona.
- `PLAN` → instructs PLAN mode to analyze, explore with read-only tools, and produce a numbered plan **without executing changes**, for review before switching to Build.
- `BUILD` → "Execute autonomously. Do not ask for confirmation. Chain tools to complete the goal. If a step fails, adapt. Report progress briefly."
- `MEDIA` → instructs the model that the request is a description of visual media to generate directly via the media model, with no tools available, and to iterate on the prompt when asked.

Important accuracy note: in the assembled chat system prompt, `systemPrefix` is used **only for MEDIA** (see assembly below). For `CHAT`/`PLAN`/`BUILD`, `buildSystemMessages` uses the `AiopePersona` text rather than `systemPrefix`. The Plan/Build `systemPrefix` strings therefore overlap in intent with the persona "## Personality" blocks but are not what gets injected in normal chat turns. (`systemPrefix` may be consumed elsewhere, but it is not part of the non-Media chat assembly path documented here.)

## Built-in AIOPE Personas (fixed, per mode)

The `AiopePersona` object in `AgentDefaults.kt` holds the assistant's fixed character. It is composed from several private blocks:

Shared blocks (used in every non-Media mode):

- `IDENTITY` — "## Identity" + "## Values". Establishes AIOPE as "a personal intelligent agent and system orchestrator running natively on the user's Android device," running locally with access to personal data, apps, filesystem, and sensors. Values: privacy first, efficiency (chain tools, minimize round-trips), honesty (verify with tools, don't fabricate).
- `TOOL_OUTPUT` — "## Tool Output Handling". Instructs the model to NEVER repeat raw tool output verbatim (results already show in collapsible panels) and to summarize/extract instead.
- `FORMATTING` — "## Formatting". Markdown rules: fenced code blocks with language tags, tables, lists; always use markdown image syntax `![alt](url)` (local paths as `file:///…`).
- `DYNAMIC_UI` — "## Dynamic UI". A large block describing `aiope-ui` interactive components and actions. **Only appended when dynamic UI is enabled** (see `forMode`).

Mode-specific personality blocks (exactly one is chosen per mode):

- `CHAT` — "## Personality (Chat)". Competent, efficient, concise everyday conversational assistant; uses tools proactively; confirms before destructive actions.
- `PLAN` — "## Personality (Plan)". A careful read-only analyst that explores and produces a numbered, reviewable plan; may write documents / set up tasks but does NOT execute device/system changes.
- `BUILD` — "## Personality (Build)". An autonomous executor with the full tool set (shell, files, SSH, remote browser driving); does not ask for confirmation on routine steps, adapts on failure, tracks work with the task list, respects genuinely destructive actions.

`modeBlock(mode)` selects the personality block: `PLAN → PLAN`, `BUILD → BUILD`, and **everything else (including `CHAT` and any fallback) → `CHAT`**. There is no dedicated persona for `MEDIA` here — Media never goes through `forMode` (see assembly).

`forMode(mode, dynamicUiEnabled)` builds the full persona by concatenating, in order:

1. `IDENTITY`
2. the selected `modeBlock(mode)` personality
3. `TOOL_OUTPUT`
4. `FORMATTING`
5. `DYNAMIC_UI` — only if `dynamicUiEnabled == true`

The result is trimmed. So the persona text differs between modes only in the personality block and in whether the Dynamic UI block is present.

## "About You" — the user-editable fields

The Agent settings screen (`AgentScreen`) exposes exactly one section, `AGENT_SECTIONS`, keyed `context` and titled **"About You"** with the description: "Tell AIOPE about yourself and your setup. This is injected into every conversation so the assistant knows who it's helping. AIOPE's own personality is built in and tuned per mode (Chat / Plan / Build)."

It has exactly **three** editable subsections (all default to empty string, persisted under the `agent_` prefix):

| Key (stored as) | Label | Hint |
| --- | --- | --- |
| `agent_user_info` | "About the User" | Your name, role, expertise level, interests |
| `agent_environment` | "Environment" | Your devices, servers, networks, OS details |
| `agent_projects` | "Projects & Workflows" | Current projects, preferred tools, common tasks |

UI behavior in `AgentScreen`:

- Each field is a multi-line `OutlinedTextField` (min 2, max 8 lines) that saves on every change (`dao.upsertSetting`).
- A per-section and a top-bar Refresh action reset the fields back to their defaults (empty).
- There is one additional field outside `AGENT_SECTIONS`: **Auto-Run** — `agent_auto_run_prompt` (single line, default `"continue"`), the message sent when auto-run continues. It is not part of the persona or "About You" context.

The old `agent_prompt` key (`AGENT_PROMPT_KEY`) is declared "kept for migration" and is not read when building the prompt.

## Building the agent prompt — `buildAgentPrompt`

`buildAgentPrompt(dao, mode, dynamicUiEnabled)` (in `AgentScreen.kt`) returns:

1. `AiopePersona.forMode(mode, dynamicUiEnabled)` — the fixed persona.
2. Then, for each populated "About You" field, a `## About You` section containing the non-blank values joined by blank lines. If all three fields are blank, no user-context section is appended.

The result is `trimEnd()`-ed. This is the persona-plus-user-context half of the system prompt; the volatile/stateful pieces are added separately in `buildSystemMessages`.

## System message assembly — `ChatViewModel.buildSystemMessages`

`buildSystemMessages(mc)` produces the final list of system messages for a turn. Behavior depends on the active mode (`_agentMode.value`):

- **MEDIA** — the prompt is **just `mode.systemPrefix`** (the thin media-generation instruction). No persona, no user context, no tools.
- **All other modes** — the prompt is `buildAgentPrompt(chatDao, mode, toolStore.isDynamicUiEnabled())`.

It then gathers three more pieces:

- `remoteCtx` = `remoteToolBridge.buildSystemContext()` — remote-server context (see below).
- `ragInstruction` = a fixed "## Knowledge Base" directive telling the model it has a local knowledge base via `rag_search` and to search it FIRST before `search_web`/`fetch_url` for questions that indexed documents might answer.
- `dateTime` = "## Current Date & Time\n" + `ZonedDateTime.now()` formatted as `EEEE, yyyy-MM-dd HH:mm:ss z` (day-of-week, date, **second-granular** time, timezone).

These are combined via `listOfNotNull(...)` (blank entries dropped) in this exact order and joined with blank lines:

1. `prompt` (persona + user context; or Media prefix)
2. `ragInstruction`
3. `remoteCtx`
4. `dateTime` — **LAST**

If the combined text is non-blank it becomes a single `"system"` message.

### Why the timestamp is last — prompt caching

The code comments this ordering explicitly: static content (persona, RAG directive, remote context) is kept at the front so it forms a stable, cacheable prefix for providers that support prompt caching, while the **volatile second-granular timestamp is placed last**. Because only the trailing line changes each turn, the large static prefix still cache-hits instead of being reprocessed. The timestamp is intentionally kept second-granular for "agent statefulness."

## What "stateful injected context" actually means (accuracy note)

The only genuinely **injected** stateful content in `buildSystemMessages` is:

- the **current date & time** (second-granular, injected every turn), and
- **remote-server context** (`remoteCtx`), which is present only when the user has configured remote servers.

The **RAG** piece injected here is a *directive/instruction* ("## Knowledge Base"), not knowledge-base content — actual retrieval happens when the model calls the `rag_search` tool.

Contrary to a common assumption, **memory, todo list, and location are NOT injected into the system prompt** by `buildSystemMessages`. They are pull-on-demand **tools** the model calls when needed:

- Memory → `memory_store` / `memory_recall` / `memory_forget`
- Todo → `todo_read` / `todo_write`
- Location → `get_location` / `search_location`
- Date/time is also available as the `datetime_now` tool in addition to the injected timestamp.

So the system prompt itself carries the live clock and (when configured) remote-server state; everything else stateful is fetched through tool calls rather than pre-injected. This is stated here to avoid overclaiming a "context dump" that the code does not perform.

### Remote-server context (`remoteCtx`)

`RemoteToolProvider.buildSystemContext()` returns empty when no servers are configured. Otherwise it appends a "## Remote Servers" block: guidance on `ssh_start`/`ssh_exec`/`ssh_exit` and `remote_browser_*` tools, then an "Available servers:" list. Each server line shows name, `host:port`, live status (`CONNECTED` when the SSH manager reports connected, else the stored status), and optional OS info. If a server has a stored browser registry, a "browsers:" line lists detected engines/versions and whether the browser is desktop/shared-session or headless.

## Quick Reference

- Modes: `CHAT`, `PLAN`, `BUILD`, `MEDIA` (labels "Chat"/"Plan"/"Build"/"Media").
- Only `MEDIA` disables all tools and targets the `MEDIA` provider category.
- Persona is fixed in code (`AiopePersona`); the user edits only three "About You" fields (`agent_user_info`, `agent_environment`, `agent_projects`).
- `forMode` order: Identity → mode Personality → Tool Output → Formatting → (Dynamic UI if enabled).
- System message order: persona/user-context → RAG directive → remote context → **timestamp last** (for prompt-cache friendliness).
- Media turns skip the persona entirely and use only `MEDIA.systemPrefix`.
- Injected stateful content = live timestamp + remote-server context; memory/todo/location/RAG are tool-driven, not pre-injected.
