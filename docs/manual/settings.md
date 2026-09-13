# Settings Hub, Navigation & Import/Export

keywords: settings, settings screen, settings hub, navigation, router, providers, agent, default models per task, task models, mcp servers, remote servers, ssh, voice, theme, tools, tool toggles, rag documents, security, app lock, export settings, import settings, backup, restore, SettingsPorter, JSON backup, alpine, proot, add provider, edit provider, provider profile, profile editor, voice selection, where do I change, how do I configure

This is the **index** to AIOPE's own configurable UI. The Settings screen is a single-Activity
router that swaps between a scrolling list of section entries and each section's detail screen.
Use this page to answer "how do I change X" / "where is Y" and to jump to the detailed manual
doc for each area.

This page is written from the actual source. Primary files:
- `feature-chat/.../settings/SettingsScreen.kt` — the router (`SettingsScreen`) that switches
  between the list and every section by a single `screen` string state.
- `feature-chat/.../settings/ProfileListScreen.kt` — `ProfileList`, the actual settings hub
  (the list of entries) **and** where the Export/Import and Alpine-proot rows live; also
  `TemplatePicker` (the "Add Provider" screen).
- `feature-chat/.../settings/SettingsPorter.kt` — JSON export/import of settings.
- `feature-chat/.../settings/VoiceSettingsScreen.kt` — voice selection.
- `feature-chat/.../settings/SecuritySettingsScreen.kt` — the Security settings UI.

## How to reach Settings, and how navigation works

`SettingsScreen(providerStore, toolStore, chatDao, onBack, serversContent)` holds a single
`var screen by remember { mutableStateOf("list") }`. It starts on `"list"` (the hub). Tapping a
row sets `screen` to that section's key; each section's back arrow sets `screen = "list"`.
Pressing back from the hub itself calls the caller's `onBack`. There is **no** deep multi-level
nav stack here — it is a flat, one-level `when (screen)` switch, plus provider add/edit
sub-states.

The complete set of `screen` values in the `when` block, and what each opens:

| `screen` | Composable opened | Reached from the hub via |
|---|---|---|
| `list` | `ProfileList(...)` — the hub itself | (initial state) |
| `providers` | `ProviderListScreen(...)` | "Providers" |
| `agent` | `AgentScreen(dao)` | "Agent" |
| `tasks` | `TaskModelScreen(providerStore)` | "Default Models per Task" |
| `mcp` | `McpServerScreen(toolStore)` | "MCP Servers" |
| `security` | `SecuritySettingsScreen()` | "Security" |
| `servers` | `serversContent?.invoke {...}` (injected) | "Remote Servers" |
| `voice` | `VoiceSettingsScreen()` | "Voice" |
| `theme` | `theme.ThemeSettingsScreen()` | "Theme" |
| `tools` | `ToolToggleScreen(toolStore)` | "Tools" |
| `rag` | `RagScreen()` | "RAG Documents" |
| `pick` | `TemplatePicker(...)` — "Add Provider" | the "+" in Providers |
| `edit` | `ProfileEditor(...)` — edit one provider | tapping a provider's edit action |

Note that `"servers"` is not built into this module: it is passed in as a
`serversContent: (@Composable (onBack) -> Unit)?` lambda, so if the host doesn't provide it the
Remote Servers row opens nothing.

## On "profiles" — what the code actually calls a profile

The brief refers to a "profiles list/edit." In this code there is **no** separate persona/profile
concept in the Settings router — the composable named `ProfileList` **is** the settings hub, and
the thing it edits (`ProfileEditor`, reached via `screen = "edit"`) is a **provider profile**
(`ProviderProfile` / `ProviderProfile.getById(id)`). "Add Provider" (`TemplatePicker`, `screen =
"pick"`) creates a new `ProviderProfile` from a `BuiltinProvider` template, saves it, marks it
active, copies any sibling model cache, then drops straight into the editor. So "profiles list"
= the Providers screen, and "profile edit" = the provider editor. There is no persona editor on
this screen. (For assistant persona/mode behavior, see `personas-and-modes.md`, which is a
different subsystem.)

## The hub entries (in on-screen order)

`ProfileList` renders these rows top-to-bottom. Each entry below gives: the exact label +
subtitle from code, what it controls, and the detailed manual doc that covers it.

1. **Providers** — "API providers, endpoints, and models". Opens `ProviderListScreen`: add/edit
   provider connections (base URL, API key, model), pick the active **Multimodal Text** and
   **Media Generation** providers, and move a provider between those two categories.
   → see `providers.md`.

2. **Agent** — "Customize the system prompt and agent behavior". Opens `AgentScreen(dao)`: agent
   system-prompt and behavior fields (persisted under the `agent_` key prefix in `settings_kv`).
   → see `agent-system.md`.

3. **Default Models per Task** — "Set different models for chat, agent, titles, etc.". Opens
   `TaskModelScreen(providerStore)`: per-task model routing (summary, title, translation, RAG,
   image/audio/video recognition, subagent, image/audio/video generation, realtime speech).
   → see `providers.md` (the "Per-task model routing" section).

4. **MCP Servers** — "Add remote tool servers via Model Context Protocol". Opens
   `McpServerScreen(toolStore)`: add/enable MCP servers (HTTP/SSE, header or OAuth2 auth) that
   contribute extra tools. → see `providers.md` (the MCP section) and `tools.md`.

5. **Security** — "Optional sign-in factors: biometric, security key, TOTP". Opens
   `SecuritySettingsScreen()`. → see `authentication.md` (and the Security section below).

6. **Remote Servers** — "Deploy and manage SSH dev servers controlled by AIOPE". Opens the
   host-injected `serversContent` lambda. → see `remote-servers.md`.

7. **Voice** — "Voice selection, speech settings for live calls". Opens `VoiceSettingsScreen()`.
   → see `voice.md` (and the Voice section below).

8. **Theme** — "Colors, background, bubbles, display options". Opens
   `ThemeSettingsScreen()`. → see `themes.md`.

9. **Tools** — "Enable or disable individual tools". Opens `ToolToggleScreen(toolStore)`:
   per-tool on/off switches. → see `tools.md`.

10. **RAG Documents** — "Upload and manage files for on-device retrieval". Opens `RagScreen()`:
    document upload/indexing for retrieval. → see `rag.md`.

Below those, still inside `ProfileList`, are the maintenance rows:

11. **Export Settings** — "Backup providers, tools, agent, memories" (see below).
12. **Import Settings** — "Restore from a backup file" (see below).
13. **Alpine (proot)** — a Deploy/Redeploy control for the on-device Alpine Linux rootfs used by
    the shell/terminal (`ProotBootstrap`). It shows "Installed"/"Not installed", and the button
    reads Deploy, Redeploy, or Deploying… by state. Redeploy wipes the existing rootfs first.
    → see `terminal.md`. (This row is only present in the hub itself; the brief did not list it,
    but it is really there.)

The Export/Import/Alpine rows only render when `chatDao != null` (Export/Import) — in practice
the hub always receives a `chatDao`.

## Voice settings (VoiceSettingsScreen)

`VoiceSettingsScreen(onBack)` is a single scrolling list titled **"Voice"**. It presents one
radio-selectable row per voice; tapping a row writes the choice immediately. Details from code:

- Storage: `SharedPreferences` file **`voice_settings`**, key **`voice_name`**. Read elsewhere
  via `getVoiceName(context)`.
- Default: **`Aoede`** (constant `DEFAULT_VOICE`), shown to the user as **"Aria"**.
- There are **30** selectable voices (the `VOICES` list). Each row shows a friendly display name
  (from `DISPLAY_NAMES`, e.g. `Aoede`→"Aria", `Puck`→"Jake", `Kore`→"Maya") and a style label
  (e.g. "♀ Breezy", "♂ Upbeat", "♀ Firm"). The value persisted is the **API voice name** (e.g.
  `Aoede`), not the display name.
- That is the **only** control on this screen — there is no rate/pitch/language/echo control
  here. Mic capture, echo cancellation, speakerphone routing, and the floating-mic overlay live
  elsewhere (see `voice.md`); the overlay toggle is actually on the **Security** screen (below).

→ Full voice-engine detail: `voice.md`.

## Security settings (SecuritySettingsScreen) — brief

`SecuritySettingsScreen(onBack)` is titled **"Security"**. It exposes the three optional,
opt-in auth factors plus an app-launch gate, and two extra device toggles:

- **Factor toggles**, one `FactorRow` each, from `AuthFactor`:
  - Biometric unlock — "Fingerprint, face, or device PIN/pattern. No Google services."
  - Hardware security key — "YubiKey, Thetis, or any CTAP2 key over USB or NFC."
  - Authenticator app (TOTP) — "Time-based codes (RFC 6238). Secret sealed in hardware
    Keystore." After enrolling, a card shows the Base32 secret (grouped in 4s), "Copy secret" /
    "Copy otpauth link", and a 6-digit verify box.
- **"Require authentication to open the app"** — the app-lock switch. It is **disabled until at
  least one factor is enrolled** (`enabled = state.hasAnyFactor`).
- **"Set AIOPE as device assistant"** — requests the Android `ROLE_ASSISTANT` (assist gesture /
  long-press home). Only shown when `AssistantRole.isAvailable`.
- **"Floating voice button"** — starts/stops a draggable mic overlay over other apps
  (`VoiceOverlayControl`), requesting the draw-over-other-apps permission if needed.

Enrolling biometric or security-key factors requires the screen to be hosted in a
`FragmentActivity`; otherwise it shows a status message instead of enrolling. Verification in
this version is **on-device** (the screen header says so).

→ Full auth model, factors, keystore storage, and the app-launch gate: `authentication.md`.

## Export / Import (SettingsPorter)

The Export/Import rows in `ProfileList` are backed by `SettingsPorter` (an `object`). Everything
it reads/writes goes through the Room DB (`ChatDao`) — **not** SharedPreferences.

### JSON format

`SettingsPorter.export(dao)` builds one JSON object (pretty-printed, 2-space indent) with:

- `version` = `1`
- `exported` = epoch millis (`System.currentTimeMillis()`)
- `providers` — array of `{ id, json, isActive }` from `dao.getProviders()` (each provider's
  full profile JSON as stored in `ProviderEntity`, **including its API key** — see honesty
  notes).
- `tool_toggles` — array of `{ toolId, enabled }` from `dao.getToolToggles()`.
- `mcp_servers` — array of `{ id, json }` from `dao.getMcpServers()` (full MCP config JSON,
  including any headers / OAuth tokens stored there).
- `agent_settings` — array of `{ key, value }` from `dao.getSettingsByPrefix("agent_")` — i.e.
  **only** `settings_kv` rows whose key starts with `agent_`.
- `memories` — array of `{ key, content, category }` from `dao.getAllMemories()`.

### Export flow (UI)

Tapping **Export Settings** runs `SettingsPorter.export(chatDao)` on `Dispatchers.IO`, then
launches a `CreateDocument("application/json")` picker with a default filename
**`aiope-settings.json`**; the JSON is written to the chosen URI. Status text shows "Exported
successfully". (`SettingsPorter.shareExport(ctx, json)` also exists — an `ACTION_SEND` share with
subject "AIOPE Settings Backup" — but the hub's Export row uses the save-to-file path, not
share.)

### Import flow (UI)

Tapping **Import Settings** launches `GetContent("application/json")`, reads the file via
`SettingsPorter.importFromUri(ctx, chatDao, uri, replace = false)`, and shows "Imported
successfully" or "Error: …". Import is **merge/upsert by default** (`replace = false`): each
section upserts rows (`upsertProvider`, `upsertToolToggle`, `upsertMcpServer`, `upsertSetting`,
`upsertMemory`) rather than clearing first.

`SettingsPorter.import(dao, json, replace)` supports a `replace = true` mode that first calls
`deleteAllProviders()` / `deleteAllMcpServers()` before re-adding (tool toggles, agent settings,
and memories are always upserted, never bulk-deleted). **The hub UI always passes
`replace = false`** — there is no "replace on import" switch exposed in `ProfileList`, so the
replace path is code-only today.

## Honesty notes (what export/import does and does NOT cover)

The Export row's subtitle says "Backup providers, tools, agent, memories" — that list is
accurate, and it is also the **complete** list. Notably **not** included in the backup:

- **Per-task model routing.** `TaskModelStore` persists task assignments in the `task_models`
  **SharedPreferences** file (keys `task_<id>`), which `SettingsPorter` never reads or writes.
  So "Default Models per Task" settings are **not** exported or imported, despite per-task models
  being a configurable surface. (The brief listed "task models" as included — that is **not**
  the case in this code.)
- **Voice selection.** Stored in the `voice_settings` SharedPreferences (`voice_name`), not in
  the DB — **not** exported.
- **Active media provider pointer.** Stored in `settings_kv` under `active_media_provider`, which
  is not an `agent_`-prefixed key, so `getSettingsByPrefix("agent_")` **excludes** it — the
  active-media selection is **not** exported (individual media providers are, via `providers`).
- **Theme, RAG documents, security/auth factors, remote-server definitions.** None of these are
  in the backup JSON.
- Only `settings_kv` rows with the **`agent_` prefix** are captured under `agent_settings`; any
  other `settings_kv` key is skipped.

Other honest points:

- **API keys are exported in cleartext.** Provider `json` (which contains `apiKey`) and MCP
  server `json` (which may contain headers / OAuth tokens) are copied verbatim into the backup
  file. The exported file is plaintext JSON; treat it as sensitive.
- **Import is a merge, not a wipe** (`replace = false` from the UI). Existing rows with the same
  id/key are overwritten; rows not present in the file are left in place. There is no
  version/compatibility check beyond reading `version` isn't even validated — `import` ignores
  the `version` field entirely and just reads whatever section keys are present.
- **No accessibility (WCAG) or encryption guarantee** is encoded for this screen or the backup
  file in the source read here.
