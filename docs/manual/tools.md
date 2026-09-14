# Tools System

keywords: tools, tool calling, function calling, buildToolDefs, ToolExecutor, ToolStore, agent modes, per-mode tools, master switch, MCP, remote tools, SSH, remote browser, tool loop, run_sh, run_proot, browser, memory, RAG, orchestrate, schedule, todo

AIOPE's agent acts on the device and the world through **tools** — callable functions the model can invoke during a turn. Tools are defined and executed by `ToolExecutor` (`engine/ToolExecutor.kt`), gated per agent mode by `AgentMode` (`engine/AgentMode.kt`) and by user settings in `ToolStore` (`settings/ToolStore.kt`), and configured in the UI by `ToolToggleScreen` (`settings/ToolToggleScreen.kt`).

## Overview

There are **73 tools total: 57 built-in (local) tools + 16 remote tools**.

- The 57 local tools are registered in `ToolExecutor.buildToolDefs()` via the `td(name, description, jsonSchema)` helper and dispatched in the big `when (name)` block of `ToolExecutor.execute()`.
- The 16 remote tools come from the optional `remoteToolBridge` (`RemoteToolBridge` in `core-model`), exposed only when `feature-remote` is initialized. They cover SSH and a server-side ("remote") browser.
- Additional dynamic tools can appear at runtime from enabled **MCP servers** (`toolStore.getMcpServers()`), discovered via `McpManager`. These are on top of the 70 and vary by configuration.

## The 57 local tools (grouped)

Grouping mirrors `ToolToggleScreen`, with each tool cited by its exact registered name.

### System & Files
- `run_sh` — Execute an Android shell command (timeout 10–600s; default 300).
- `run_proot` — Run a command in the Alpine Linux proot environment (apk, python, gcc). Requires arm64-v8a and an installed proot; otherwise returns guidance to use `run_sh`.
- `read_file` — Read file contents (capped by `fileReadLimit`, default 50000).
- `write_file` — Write a file (creates parent dirs).
- `edit_file` — Exact find/replace in a text file; errors if `old_string` is not found or matches multiple times unless `replace_all=true`.
- `list_directory` — List directory entries.
- `search_files` — Walk a directory tree (max depth 10, skips `.git`/`node_modules`/`build`); `target=content` (regex grep, `path:line:text`) or `target=files` (name match), optional `glob`.
- `device_info` — Battery, storage, RAM, network, model.
- `media_control` — `play_pause`, `next`, `previous`, `stop` via media key events.
- `clipboard_copy` — Copy text to the clipboard.
- `clipboard_read` — Read current clipboard contents.
- `datetime_now` — Local timestamp, timezone/offset, day of week, epoch ms.

### Web & Data
- `search_web` — Web search (SearxNG at search.xnet.ngo, DuckDuckGo HTML fallback).
- `search_images` — Image search (same backend, `categories=images`).
- `fetch_url` — Fetch a URL; extracts text + images as `![alt](url)`; `mode=raw` for raw body; `offset`/`limit` paginate (`fetchLimit` default 30000).
- `http_request` — Generic HTTP API call (GET/POST/PUT/PATCH/DELETE); returns status, key headers, and body truncated to ~20KB; timeout 1–300s (default 30).
- `query_data` — Live real-time data feeds via the gateway `/v1/data` endpoint; categories are fetched dynamically (e.g. weather, air_quality, alerts, uv, tides, earthquakes, iss, apod). Location categories use device GPS.
- `get_location` — Device GPS location (requests location permission; reverse-geocodes).
- `search_location` — Search a place/address/landmark/business; geocodes addresses, uses gateway `places` / Geoapify for amenities; call `get_location` first for nearby searches.
- `open_intent` — Open a URI: `https://`, `geo:`, `google.navigation:q=`, `tel:`, `mailto:`, etc.

### Browser (in-app WebView)
- `browser_navigate` — Navigate the in-app browser to a URL.
- `browser_content` — Get page text, URL, title (`offset`/`limit` paginate).
- `browser_elements` — List interactive elements with selectors.
- `browser_click` — Click by CSS selector.
- `browser_fill` — Fill an input by CSS selector.
- `browser_eval` — Run JavaScript and return the result.
- `browser_back` — Go back in history.
- `browser_scroll` — Scroll up/down (default 500px).
- `browser_open` — Show the browser panel.
- `browser_close` — Hide the browser panel.
- `browser_maximize` — Maximize/restore the browser panel.

### Communication (device)
- `read_sms` — Read recent SMS (default 10).
- `send_sms` — Send an SMS.
- `delete_sms` — Delete an SMS by id.
- `read_contacts` — Search/list contacts (up to 20).
- `send_notification` — Post a device notification.
- `read_calendar` — Read upcoming events (default 7 days).
- `create_event` — Open the calendar app pre-filled with event details.
- `delete_event` — Delete a calendar event by id.
- `set_alarm` — Set an alarm (falls back to AlarmManager if no clock app); `skip_ui` sets silently.
- `dismiss_alarm` — Dismiss an alarm by label.

### AI & Knowledge
- `memory_store` — Store a persistent fact/preference by key (categories: general, preference, learning, error).
- `memory_recall` — Search stored memories (empty query lists all).
- `memory_forget` — Delete a memory by key.
- `rag_search` — Semantic search of the on-device knowledge base (`RagEngine`), returns scored chunks.
- `rag_index` — Index a document (title + content) into the knowledge base.
- `image_generate` — Generate an image from a prompt (supports optional reference images for image-to-image); saved as `file://` PNG.
- `analyze_image` — Vision analysis of an image URL/`file://` path (JPEG/PNG/WebP/GIF/BMP/SVG; SVG rasterized).
- `detect_objects` — On-device object detection (RT-DETRv4-S ONNX) over an image URL/`file://` path; returns labeled COCO-class boxes with confidence. Requires the model to be downloaded. See [Vision](vision.md).
- `facial_scan` — On-device front-camera face identification against enrolled identities; returns the enrolled name or "unidentified". Requires face models downloaded + camera permission. See [Vision](vision.md).
- `introspect` — Answer questions about AIOPE itself from the bundled manual (separate `aiope_manual.db`, semantic search, returns the full matching page). See [Introspect](introspect.md).
- `orchestrate` — Run a multi-agent DAG pipeline (`PipelineExecutor`); stages dispatch named roster agents (Architect, Coder, Researcher, QA, DevOps, Security, Writer, Reviewer); requires `subagentManager`.

### Tasks
- `todo_write` — Write/replace/upsert the persistent todo list (`merge=true` upserts by id); stored in `aiope_agent_state` prefs.
- `todo_read` — Read the todo list grouped by status with counts.
- `schedule_task` — Schedule a background agent run (`once`/`interval`/`daily`/`weekly`/`monthly`); `tools` restricts which tools background runs may use (allow-list); `max_runs` caps executions.
- `cancel_schedule` — Cancel and delete a scheduled task by id.
- `list_schedules` — List scheduled tasks with recurrence, next run, and progress.

## The 16 remote tools

Exposed only when `remoteToolBridge` is present (feature-remote). Executed by delegating to `rtp.execute(name, args)`:

- SSH (3): `ssh_start`, `ssh_exec`, `ssh_exit`.
- Remote browser (13): `remote_browser_start`, `remote_browser_navigate`, `remote_browser_content`, `remote_browser_elements`, `remote_browser_click`, `remote_browser_fill`, `remote_browser_status`, `remote_browser_eval`, `remote_browser_screenshot`, `remote_browser_back`, `remote_browser_scroll`, `remote_browser_detect`, `remote_browser_stop`.

If the bridge is not initialized, calling any of these returns "Remote tools not available."

## Agent modes and per-mode availability

`AgentMode` has four modes: **Chat**, **Plan**, **Build**, **Media**. Each mode determines which tools are offered to the model.

- **Media** — `disablesAllTools = true`. `buildToolDefs()` returns an empty list, so **no tools** are available. Media mode generates visual media directly with no tool calls.
- **Chat** — the everyday phone-assistant set (`AgentMode.CHAT_DEFAULT`): file reads, location, device info, datetime, web/data/search, the full browser set, memory, RAG search, image generate/analyze, calendar, alarms, contacts, notifications, clipboard, SMS, media control, and `open_intent`. It intentionally excludes destructive/heavy authoring tools like `write_file`, `edit_file`, `run_sh`, and scheduling.
- **Plan** — `PLAN_DEFAULT = CHAT_DEFAULT + PLAN_EXTRA`. Adds research/authoring/task tools: `write_file`, `edit_file`, `search_files`, `http_request`, `rag_index`, `todo_write`, `todo_read`, `schedule_task`, `cancel_schedule`, `list_schedules`, `orchestrate`. (Plan mode's system prompt still steers the model to plan rather than execute.)
- **Build** — every tool defaults ON (`toolDefaultEnabled` returns `true` for BUILD). This is the full 70-tool set (plus any enabled MCP/remote tools).

Defaults are computed by `AgentMode.toolDefaultEnabled(mode, toolId)`:
- `BUILD` → always true
- `MEDIA` → always false
- `CHAT` → tool in `CHAT_DEFAULT`
- `PLAN` → tool in `PLAN_DEFAULT`

These are only defaults/seed values; users can override any tool in any mode in Settings.

## Configuring tools in Settings

`ToolToggleScreen` (Settings → Tools) exposes two layers of control per tool:

1. **Master switch** — `ToolStore.setToolEnabled(toolId, enabled)`. When off, the tool is disabled **everywhere**, in every mode. `execute()` also hard-blocks a disabled tool ("Tool 'X' is disabled.").
2. **Per-mode chips** — one FilterChip each for **Chat / Plan / Build** (Media is omitted because it has no tools). These call `ToolStore.setToolEnabledForMode(toolId, mode, enabled)`, stored under composite keys `"<mode>:<toolId>"` (e.g. `chat:run_sh`). Chips are only actionable when the master switch is on.

Enablement resolution (`ToolStore.isToolEnabledForMode`):
- First check the global master toggle; if off, the tool is off in all modes.
- Otherwise use the explicit per-mode override if set, else fall back to `AgentMode.toolDefaultEnabled(mode, toolId)`.

The screen also has a **Dynamic UI** toggle (`isDynamicUiEnabled`), controlling whether aiope-ui rich interactive blocks are used in responses.

Trimming a mode's tool set shrinks the tool schema sent with each request, reducing prompt size and the chance of misuse.

## MCP servers

`ToolStore` also manages **MCP (Model Context Protocol) servers** (`McpServerConfig`, transports HTTP/SSE, auth NONE/HEADER/OAUTH2). Enabled servers contribute extra tools in `buildToolDefs()`: `mcpManager.getToolDefs(server.id)`, auto-discovered on first use. A disabled **Vinkius Cloud** server template is seeded once (token placeholder; no secret shipped). Unknown tool names fall through to `mcpManager.executeTool(name, args)`.

## The tool loop

1. **Build schema** — `ToolExecutor.buildToolDefs()` produces the list of `ToolDef(name, description, JSONObject params)` for the current mode. If the mode disables all tools (Media), it returns empty. The list is then filtered by `toolStore.isToolEnabledForMode(it.name, getAgentMode())`, so only tools enabled for the active mode (and not disabled by the master switch) are offered.
2. **Offer to the model** — the caller (e.g. `ChatViewModel`, `VoiceSessionController`, `AgentRunWorker`) passes these defs to the streaming orchestrator as the request's tool schema (`tools = toolExecutor.buildToolDefs()`).
3. **Model requests a call** — when the model emits a tool call, the orchestrator invokes `onToolCall = { name, args -> toolExecutor.execute(name, args) }`.
4. **Execute** — `ToolExecutor.execute(name, args)` first rejects disabled tools, then dispatches through the `when (name)` block (or the remote bridge / MCP manager). Per-mode gating is enforced at step 1 (the allow-list in `buildToolDefs`), **not** re-checked in `execute()`, so a tool allowed in a mode is never "offered but rejected".
5. **Return result** — the string result is fed back to the model, which continues the turn — chaining more tool calls as needed until it produces a final answer. Long-running tools report progress via `ToolProgressBus`.

Background/scheduled runs (`schedule_task`) execute through the same `ToolExecutor` but are restricted to an allow-list of tools (`search_web`, `fetch_url`, `http_request`, `run_sh`, `ssh_exec`, `send_notification`, `set_alarm`, `memory_store`, `memory_recall`, `read_file`, `write_file`, `list_directory`, `datetime_now`).
