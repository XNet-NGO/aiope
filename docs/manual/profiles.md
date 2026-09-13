# Assistant Profiles (Provider Profiles & Per-Model Settings)

keywords: profiles, assistant profiles, provider profiles, ProviderProfile, ProviderStore, ProfileEditor, ProfileList, ProviderListScreen, edit provider, add provider, switch profile, active profile, active text, active media, ProviderCategory, Multimodal Text, Media Generation, ModelConfig, ModelDef, per-model settings, temperature, top-p, topP, top-k, topK, max tokens, maxTokens, reasoning effort, context tokens, auto-compact, endpoint override, abilities, tools, vision, audio, video, base URL, API key, load models, custom model, test connection, save and activate, TaskModelStore, ModelTask, AssistantRole

In AIOPE, an "assistant profile" is a **`ProviderProfile`** — a saved connection to one LLM endpoint plus a chosen model and per-model tuning. The in-app screens label these **"Providers"** (the list is **Providers**, the editor's title is **"Edit Provider"**), so "profile" and "provider" refer to the same object here. Switching profiles is how you change which model, endpoint, and parameter set drives the assistant.

This page is written from the actual source. Primary files:
- `feature-chat/.../settings/ProfileEditorScreen.kt` — the provider/profile editor (`ProfileEditor`) with every field documented below, plus `TemplatePicker`.
- `feature-chat/.../settings/ProfileListScreen.kt` — the top-level **Settings** list (`ProfileList`) and the **Add Provider** template picker (`TemplatePicker`).
- `feature-chat/.../settings/ProviderListScreen.kt` — the **Providers** screen (`ProviderListScreen`) where you switch the active profile.
- `feature-chat/.../settings/ProviderStore.kt` — persistence, seeding, and the active-text vs active-media slots.
- `core-network/.../network/LlmProvider.kt` — the data model: `ProviderProfile`, `ModelConfig`, `ModelDef`, `ProviderCategory`, `BuiltinProvider`, `ProviderTemplates`.
- `core-network/.../network/TaskModelStore.kt` — per-task model routing (`ModelTask`, `TaskModelConfig`).
- `feature-chat/.../settings/AssistantRole.kt` — the Android **ASSISTANT** system role (a separate concept from these profiles; see the last section).

## What a profile is (data model)

A profile is a `ProviderProfile` (`LlmProvider.kt`). Its fields:
- `id: String` — stable UUID by default; some seeded profiles use fixed ids (`default_gateway`, `default_gateway_media`, `novita_byok`).
- `builtinId: String` — which template it derives from; defaults to `"custom"`.
- `label: String` — the display name shown in lists (falls back to the template's display name when blank).
- `apiKey: String`, `apiBase: String` — the connection.
- `selectedModelId: String` — the currently selected model.
- `isActive: Boolean` — whether this is the active text profile (persisted via the DB flag, not this field directly).
- `modelConfigs: Map<String, ModelConfig>` — **per-model** overrides keyed by model id.
- `category: ProviderCategory` — `TEXT` (default) or `MEDIA`.

Helpers on the profile:
- `effectiveApiBase()` — returns `apiBase`, or falls back to the template's `apiBase` when blank, so a builtin template works without typing a URL.
- `effectiveModel()` — returns `selectedModelId`.
- `activeModelConfig()` — returns the `ModelConfig` for the selected model, or a fresh `ModelConfig(modelId = selectedModelId)` if none is stored yet.

**Connection settings live on the profile; everything else lives per-model.** The source comment states: "all settings except provider connection live here" (on `ModelConfig`). So temperature, reasoning, context, abilities, etc. are stored per model id, not once per profile.

## Creating, switching, and deleting profiles

### Where profiles live in the UI
From the app's **Settings** list (`ProfileList`), the first row is **"Providers"** ("API providers, endpoints, and models"). Tapping it opens `ProviderListScreen`, which lists all profiles split into two category sections.

### Creating a profile (Add Provider)
- On the **Providers** screen, tap the **+** (Add) action in the top bar. This opens the **Add Provider** template picker (`TemplatePicker`), listing every `BuiltinProvider` in `ProviderTemplates.ALL`.
- Picking a template creates a new `ProviderProfile(builtinId = b.id, label = b.displayName, apiBase = b.apiBase ?: "", selectedModelId = <first default model>)`, saves it, **marks it active**, copies any model cache from a sibling profile with the same template, and opens the editor. (Wiring in `SettingsScreen.kt`, `"pick"` branch.)

### Switching the active profile
There are **two independent active slots** (`ProviderStore.kt`):
- **Active text** profile — the DB `isActive` flag (`setActive(id)` / `getActive()`). This drives chat, tools, and vision. On `ProviderListScreen`, tapping a row in the **Multimodal Text** section calls `setActive` on it.
- **Active media** profile — stored separately in `settings_kv` under key `active_media_provider` (`setActiveMedia(id)` / `getActiveMedia()`). Tapping a row in the **Media Generation** section sets this. If unset, it falls back to the first `MEDIA` profile.

The active profile in each section is marked with a **✔** in the row's trailing area (`ProviderListScreen`). Selecting a media profile never disturbs the text profile, because the slots are stored independently.

### Editing a profile
Three ways reach the editor (`ProfileEditor`):
- **Tap-and-hold (long-press)** a row on the Providers screen (`combinedClickable { onLongClick = onEdit }`).
- Open the row's overflow (**Move** icon / `SwapHoriz`) menu and choose **"Edit"**.
- It also opens automatically right after creating a profile via the template picker.

### Moving between categories
The overflow menu offers **"Move to Media Generation"** or **"Move to Multimodal Text"** (the opposite of the current section). This re-saves the profile with the new `category`.

### Deleting a profile
Inside the editor, the top bar's **Delete** (trash) action calls `onDelete`, which removes the profile via `ProviderStore.delete(id)` and returns to the list. There is no separate confirmation dialog in code.

### Saving
The editor's bottom button reads **"Save & Activate"**. On tap it saves the current per-model config, persists the model cache, saves the profile, and — per `SettingsScreen.kt` — always calls `setActive(it.id)`. So saving an edited profile also makes it the active text profile.

## The profile editor — every field

The editor (`ProfileEditor` in `ProfileEditorScreen.kt`) is organized into sections. The top bar shows the profile's `label` (or "Edit Provider" if blank), a back button, and a **Delete** action. Below are all controls, in order.

### Section: "Provider" (connection — shared across all this profile's models)
1. **Name** (text) → `label`. Free text; falls back to "Edit Provider" in the title when blank.
2. **Base URL** (text) → `apiBase`. Placeholder is the template's `apiBase` (or `https://api.example.com/v1`). Left blank, `effectiveApiBase()` uses the template default.
3. **API Key** (text, **obfuscated** with a password transformation) → `apiKey`. Placeholder is the template's `apiKeyHint` (or "API key").

### Section: "Model"
4. **Load Models** (button) → calls `fetchModels(effectiveApiBase(), apiKey)` and caches results. Shows "Loading…" while running and the count as "N models" beside it. If nothing is returned, it shows the error "No /models endpoint — add models manually below". Model tags shown in the list use the pattern `"<ctx>k"` plus `T` (tools), `V` (vision), `R` (reasoning) when known.
5. **Selected Model** (read-only dropdown, `ExposedDropdownMenu`) → `selectedModelId`. Picking a model also loads/creates that model's `ModelConfig` (`configFromModel`) and saves it. If empty, shows "Select model".
6. **Add Custom Model** (text + **Add** icon) → adds a `ModelDef` for a hand-typed model id, prepends it to the list, selects it, and creates its config. Modality is inferred from the id: ids starting with `cf-image/` or containing `/image/` → `image`; `cf-audio/` → `audio`; otherwise `text` (and `supportsTools = true` only for text).

### Section: "Model Settings: <model>" (per the **selected** model)
This entire section only appears when `selectedModelId` is not blank. All controls write into the selected model's `ModelConfig` and immediately `saveModelConfig()` (into `modelConfigs`).

7. **Endpoint Override** (editable dropdown) → `ModelConfig.endpointOverride`. Default display is `/chat/completions` when blank. Preset options (`ENDPOINT_PRESETS`): `/chat/completions`, `/completions`, `/responses`, `/embeddings`, `/rerank`, `/audio/speech`, `/audio/transcriptions`, `/images/generations`, `/moderations`. You can also type a custom path.

**Abilities** (label). Controls the four ability overrides. Each override is `Boolean?` where `null` = auto-detect.
8. **Auto-detect** (switch). ON when all four ability overrides are `null`. Turning it ON clears all four to `null` (let the app detect from model metadata). Turning it OFF reveals the four explicit toggles below.
9. **Tool Calling** (switch, only when Auto-detect is OFF) → `toolsOverride` (shown as `?: true`).
10. **Vision** (switch, only when Auto-detect is OFF) → `visionOverride` (shown as `?: false`).
11. **Audio** (switch, only when Auto-detect is OFF) → `audioOverride` (shown as `?: false`).
12. **Video** (switch, only when Auto-detect is OFF) → `videoOverride` (shown as `?: false`).

**Reasoning** (label).
13. **Reasoning Effort** (slider, 5 stops) → `reasoningEffort`. Options in order: `off`, `auto`, `low`, `medium`, `high` (slider `valueRange = 0f..4f, steps = 3`). Selecting `off` stores `null`; any other value stores that string.

**Parameters** (label).
14. **Temperature** (slider) → `temperature`. Range **0f..2f**. `LogSlider` displays "off" at ≤ 0 and stores `null` for that; otherwise stores the float (formatted to 2 decimals). Data-class default is `0.6f`.
15. **Top-P** (slider) → `topP`. Range **0f..1f**. ≤ 0 shows "off" and stores `null`. Default `null` (omitted).
16. **Max Tokens** (step slider) → `maxTokens`. Snaps to `TOKEN_STEPS`: `0, 256, 512, 1024, 2048, 4096, 8192, 16000, 32000, 64000, 128000, 200000, 500000, 1000000`. Step `0` shows "off" and stores `null`; values ≥ 1000 display as "<n>K". Default `null` (omitted).
17. **Top-K** (slider) → `topK`. Range **0..200** (integer). `0` shows "off" and stores `null`. Default `null`.

**Context** (label).
18. **Context Tokens** (step slider) → `contextTokens`. Snaps to `HISTORY_STEPS`: `1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000, 200000, 256000, 384000, 500000, 750000, 1000000, 2000000, 5000000, 10000000`. Values ≥ 1,000,000 display as "<n>M", ≥ 1000 as "<n>K". The data-class default is `10_000_000` (10M), but `configFromModel` sets it to the model's context window when known (else 128000).
19. **Auto-compact at 95%** (switch) → `autoCompact`. When ON, history is compacted at 95% of `contextTokens`. Data-class default `false`; `configFromModel` sets it ON when the model reports a context window.

### Bottom actions
20. **Test Connection** (button) → runs `testConnection(profile, modelConfig)`: POSTs a tiny "Reply with exactly: OK" chat request and reports `[OK] Chat: OK (<n> tok)` or a `[FAIL]`. It **additionally** probes abilities that are enabled — tools (unless `toolsOverride == false`), and vision/audio/video when their overrides are `true` — reporting `[OK]`/`[WARN]`/`[FAIL]` per ability. Results render green for `[OK]` lines, error color otherwise.
21. **Save & Activate** (button) → saves the model config, saves the model cache, saves the profile, and activates it (see "Saving" above).

## Fields present in `ModelConfig` but NOT exposed in this editor

Honesty note: `ModelConfig` (`LlmProvider.kt`) carries several fields the profile editor does **not** surface as controls:
- `systemPromptOverride: String?` — a per-model system prompt exists in the data model and is (de)serialized, but there is **no** system-prompt field, and **no "dynamic UI toggle"**, in `ProfileEditorScreen.kt`. The global system prompt is edited on the separate **Agent** screen (`AgentScreen.kt`), and dynamic-UI behavior is governed elsewhere (tool/agent settings), not per profile here.
- `shellOutputLimit` (default `12000`), `fetchLimit` (default `30000`), `fileReadLimit` (default `50000`) — truncation limits with no slider or text field in this editor.

If you are looking for "where do I change the system prompt / dynamic UI," it is not in this profile editor.

## Built-in templates (Add Provider list)

`ProviderTemplates.ALL` (`LlmProvider.kt`) defines the templates offered by **Add Provider**. There are exactly four `BuiltinProvider` entries:

| `builtinId` | Display name | `apiBase` | Requires key |
|---|---|---|---|
| `aiope_gateway` | AIOPE Gateway | `https://inf.xnet.ngo/v1` | yes (hint "Gateway key") |
| `custom` | Custom | *(none — you supply it)* | no |
| `cloudflare_ai` | Cloudflare Workers AI | `https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1` | yes |
| `google_ai_studio` | Google AI Studio | `https://generativelanguage.googleapis.com/v1beta/openai` | yes |

Each template carries `defaultModels` (a list of `ModelDef`) used to prefill the model dropdown before a live **Load Models** fetch. A separate BYOK **"Novita AI"** profile is seeded disabled (`novita_byok`, base `https://api.novita.ai/v3/openai`) — it is a profile, not a template, and auto-detects models once you paste a key.

## `ModelDef` (what a model row knows)

Each entry in the model dropdown is a `ModelDef` (`LlmProvider.kt`): `id`, `displayName`, `contextWindow`, `supportsTools` (default `true`), `supportsVision`, `supportsAudio`, `supportsVideo`, `supportsReasoning`, `outputModality` (default `"text"`), `maxOutput`, `family`, plus realtime-voice fields (`useStreaming`, `audioInputType`, `sampleRate`). The editor uses `contextWindow`, `supportsTools/Vision/Reasoning` for the tag badges, and `configFromModel` seeds a new `ModelConfig` from these capabilities.

## How profiles relate to per-task model routing

A profile sets the model for normal chat. **Other tasks can point at a different profile+model** via `TaskModelStore` (`TaskModelStore.kt`), configured on the **"Default Models per Task"** screen (`TaskModelScreen.kt`, reached from the Settings list). Each `ModelTask` stores a `TaskModelConfig(taskId, profileId?, modelId?)`; if unset, the task falls back to the active profile.

`ModelTask` values (id → label): `chat`→Chat (driven by the toolbar model selector, **excluded** from the task-config UI via `ModelTask.configurable`), `summary`→Summary, `title`→Title Generation, `translation`→Translation, `rag`→RAG Embedding, `image`→Image Recognition, `audio`→Audio Recognition, `video`→Video Recognition, `subagent`→Subagent (Task Tool), `image_gen`→Image Generation, `audio_gen`→Audio Generation, `video_gen`→Video Generation, `realtime_speech`→Realtime Speech. `ProviderStore.seedTaskDefaults()` seeds a handful of these to gateway models on first run when unset.

## Profiles vs. the Android ASSISTANT role

Don't confuse an in-app profile with the device **assistant role**. `AssistantRole.kt` wraps Android's `RoleManager` for `ROLE_ASSISTANT` (the default digital-assistant / assist-gesture handoff). It exposes `isAvailable`, `isHeld`, and `requestIntent` (which launches the system consent dialog — only the user can grant it). The source explicitly notes holding this role does **not** by itself grant any background-network parity. This is an OS-level role, unrelated to which `ProviderProfile` is active.
