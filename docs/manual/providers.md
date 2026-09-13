# Providers & Per-Task Model Routing

keywords: providers, provider profiles, OpenAI-compatible, BYOK, bring your own key, AIOPE Gateway, Google AI Studio, Pollinations, Cloudflare Workers AI, Novita, custom provider, ProviderProfile, ProviderTemplates, ProviderStore, ProviderCategory, Multimodal Text, Media Generation, TaskModelStore, ModelTask, per-task routing, task defaults, active media provider, MCP, McpManager, McpServerConfig, HTTP, SSE, OAuth2, model config

AIOPE talks to every LLM through a single **OpenAI-compatible** client. A *provider* is just a connection (a base URL + optional API key) plus a selected model and per-model settings. Because everything is OpenAI-compatible, any endpoint that speaks that API — hosted or self-hosted — can be added as a provider by pasting its base URL and key (**BYOK**, bring your own key). There is no per-vendor SDK; the same request shape is reused everywhere.

This page is written from the actual source. Primary files:
- `core-network/.../network/LlmProvider.kt` — `ModelConfig`, `ModelDef`, `ProviderCategory`, `ProviderProfile`, `BuiltinProvider`, `ProviderTemplates`
- `core-network/.../network/TaskModelStore.kt` — `ModelTask` enum and per-task config storage
- `feature-chat/.../settings/ProviderStore.kt` — seeding of default providers and per-task defaults, active-text vs active-media slots
- `feature-chat/.../settings/ProviderListScreen.kt` — the Providers UI (two category sections)
- `feature-chat/.../settings/McpManager.kt` and `settings/ToolStore.kt` — MCP server connections (`McpServerConfig`, `McpTransport`, `McpAuthType`)

## Provider model (data shapes)

A provider is a `ProviderProfile` (`LlmProvider.kt`). The fields that matter:
- `id` — stable UUID (some seeded providers use fixed ids like `default_gateway`).
- `builtinId` — which template it is based on; defaults to `"custom"`.
- `label`, `apiKey`, `apiBase`, `selectedModelId`, `isActive`.
- `modelConfigs: Map<String, ModelConfig>` — per-model overrides.
- `category: ProviderCategory` — `TEXT` or `MEDIA` (see below).

`effectiveApiBase()` falls back to the template's `apiBase` when the profile leaves it blank, so a builtin provider works with no manual URL entry.

Each model has a `ModelConfig` (`LlmProvider.kt`) carrying, among others: `endpointOverride`, ability overrides (`toolsOverride`, `visionOverride`, `audioOverride`, `videoOverride` — `null` means auto-detect), sampling params (`temperature` default `0.6`, `topP`, `topK`, `maxTokens`), `reasoningEffort` (`null`/`"auto"`/`"low"`/`"medium"`/`"high"`), `contextTokens` (default `10_000_000`), `autoCompact`, `systemPromptOverride`, and truncation limits (`shellOutputLimit` 12000, `fetchLimit` 30000, `fileReadLimit` 50000).

## Provider categories

`ProviderCategory` (`LlmProvider.kt`) has exactly two values:
- `TEXT` — id `"text"`, display name **"Multimodal Text"**. Chat, tools, and vision. One active profile drives conversations.
- `MEDIA` — id `"media"`, display name **"Media Generation"**. Image/video generation. One active profile drives media tools.

`ProviderCategory.from(id)` defaults to `TEXT` for any unknown id. `ProviderListScreen` renders these as two sections and lets you move a provider between them from the per-row overflow menu ("Move to Media Generation" / "Move to Multimodal Text").

There are **two independent active slots** (`ProviderStore.kt`):
- The **active text** provider is the DB `isActive` flag, set via `setActive(id)` and read by `getActive()`. This is what chat and tools use.
- The **active media** provider is stored separately in `settings_kv` under the key `active_media_provider`, set via `setActiveMedia(id)` and read by `getActiveMedia()`. If none is stored, it falls back to the first profile whose `category == MEDIA`. Keeping it out of the DB `isActive` flag means selecting a media provider never disturbs the text conversation provider.

## Built-in provider templates

`ProviderTemplates.ALL` (`LlmProvider.kt`) contains **exactly four** `BuiltinProvider` entries. These are the only pre-defined templates in code:

| `builtinId` | Display name | `apiBase` | Requires key |
|---|---|---|---|
| `aiope_gateway` | AIOPE Gateway | `https://inf.xnet.ngo/v1` | yes (`Gateway key`) |
| `custom` | Custom | *(none — you supply it)* | no |
| `cloudflare_ai` | Cloudflare Workers AI | `https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1` | yes |
| `google_ai_studio` | Google AI Studio | `https://generativelanguage.googleapis.com/v1beta/openai` | yes |

Note on scope vs. common expectations: there is **no** dedicated builtin template for OpenAI, Anthropic, DeepSeek, OpenRouter, Groq, or Ollama in `ProviderTemplates`. Those services are reached one of two ways: (1) through the **AIOPE Gateway**, where model IDs are namespaced by upstream (e.g. `pollinations-pollen/deepseek`, `openrouter/openrouter-free` are among the gateway's seeded models — see below); or (2) as a **Custom** provider by pasting that service's OpenAI-compatible base URL and key. The DeepSeek "DSML" tool-call format is also parsed in `StreamingOrchestrator.kt`, so DeepSeek-style responses work when reached via gateway or custom.

### AIOPE Gateway (default, preconfigured)

The `aiope_gateway` template ships with a small set of `defaultModels` in `LlmProvider.kt`:
- `google-ai-studio/models-gemma-4-31b-it` — "Gemma 4 31B IT", 256K context
- `cloudflare/@cf-black-forest-labs-flux-1-schnell` — "FLUX.1 Schnell", `outputModality = "image"`, no tools
- `google/gemini-2.5-flash-native-audio` — "Gemini 2.5 Flash (Voice)", audio, streaming, `LINEAR_PCM` @ 16 kHz
- `google-ai-studio/gemini-3.1-flash-live-preview` — "Gemini 3 Flash Live", audio, streaming, `LINEAR_PCM` @ 16 kHz

On first run `ProviderStore.seedDefault()` creates the actual default profile (`id = default_gateway`), pointing at `https://inf.xnet.ngo/v1` with the build's `GATEWAY_KEY`, `selectedModelId = google-ai-studio/models-gemma-4-31b-it`, marks it **active**, and pre-populates `modelConfigs` for a wider set of gateway-routed models than the template lists. The seeded model IDs (exactly, from `seedDefault()`) are:

- `cline/minimax-minimax-m2.5` (tools, 200K)
- `zen/minimax-m2.5-free` (tools, 200K)
- `zen/nemotron-3-super-free` (tools, 1M)
- `zen/big-pickle` (tools)
- `cline/z-ai-glm-5` (tools, 200K)
- `google-ai-studio/models-gemma-4-31b-it` (tools, vision, 256K)
- `google-ai-studio/models-gemma-4-26b-a4b-it` (tools, vision, 256K)
- `pollinations-pollen/llama-scout` (tools, 327,680)
- `pollinations-pollen/nova-fast` (tools, 128K, reasoning off)
- `pollinations-pollen/flux` (no tools, ctx 0, no compact — image)
- `pollinations-pollen/deepseek` (tools, 1M)
- `pollinations-pollen/mistral` (tools, 128K, reasoning off)
- `pollinations-pollen/qwen-coder` (tools, 262,144)
- `openrouter/openrouter-free` (vision, 128K)
- `pollinations/openai` (tools, 128K, no compact)
- `pollinations/openai-fast` (tools, 128K)
- `google-ai-studio/gemini-3.1-flash-live-preview` (tools, audio, 131,072)

These model-ID prefixes (`google-ai-studio/`, `cloudflare/`, `pollinations`, `pollinations-pollen/`, `openrouter/`, `cline/`, `zen/`) are how the single gateway routes to different upstreams — including **Google AI Studio** and **Pollinations** as named in the brief — behind one OpenAI-compatible endpoint. After seeding, `ProviderStore.fetchModelsAsync()` calls the gateway's `/models` endpoint in the background to refresh the real model list into the local cache.

`seedDefault()` also seeds a second, **Media Generation** gateway profile (`id = default_gateway_media`, `category = MEDIA`) with the one verified image model `cloudflare/@cf-black-forest-labs-flux-1-schnell`, and marks it the active media provider via `setActiveMedia(...)` so Media mode works out of the box.

`ProviderStore.ensureLiveModel()` additionally guarantees the gateway profile has a `ModelConfig` for `google-ai-studio/gemini-3.1-flash-live-preview` (audio + tools, 131,072 context) for realtime voice.

### Google AI Studio (builtin template)

`google_ai_studio` (`LlmProvider.kt`), base `https://generativelanguage.googleapis.com/v1beta/openai`, seeds these `defaultModels`: `models/gemini-2.5-flash`, `models/gemini-2.5-pro`, `models/gemini-3.5-flash`, `models/gemini-3.5-flash-lite`, `models/gemini-3.1-flash-lite` (all 1M context), `models/gemma-4-31b-it` and `models/gemma-4-26b-a4b-it` (256K), and `models/gemini-3.1-flash-live-preview` (131,072, audio, streaming, `LINEAR_PCM` @ 16 kHz).

### Cloudflare Workers AI (builtin template)

`cloudflare_ai`, base `https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1` (replace `{ACCOUNT_ID}`). Seeded models include `@cf/meta/llama-4-scout-17b-16e-instruct`, `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, `@cf/meta/llama-3.1-8b-instruct`, `@cf/deepseek-ai/deepseek-r1-distill-qwen-32b`, `@cf/qwen/qwen2.5-coder-32b-instruct`, `@cf/google/gemma-7b-it-lora`, `@cf/mistralai/mistral-7b-instruct-v0.2-lora`.

### Custom (any OpenAI-compatible endpoint, BYOK)

`custom` has no `apiBase` and `requiresApiKey = false`. This is the path for any OpenAI-compatible service not covered by a template — including OpenAI itself, an Anthropic-compatible proxy, DeepSeek, OpenRouter, Groq, or a local **Ollama** server. You paste the base URL and (if needed) key. When the base ends in `/v1` the model list is fetched from `<base>/models`, otherwise from `<base>/v1/models` (`ProviderStore.fetchModelsAsync()`).

### Novita AI (seeded BYOK, disabled by default)

`ProviderStore.seedNovitaByok()` seeds one disabled provider (`id = novita_byok`, `builtinId = custom`, base `https://api.novita.ai/v3/openai`) **once**, guarded by the `seeded_novita_byok` settings flag so deleting it is respected. **No API key is shipped** and **no models are pre-seeded** — the editor auto-detects models from Novita's live `/models` endpoint after you paste your own key. `ProviderListScreen` shows an info card describing this with a referral sign-up link.

## Per-task model routing

Different internal jobs can run on different models. `ModelTask` (`TaskModelStore.kt`) enumerates the tasks. There are **13** tasks; the 12 shown in settings are `ModelTask.configurable` (everything **except** `CHAT`, which is set by the toolbar model selector):

| `ModelTask` | id | label | description |
|---|---|---|---|
| `CHAT` | `chat` | Chat | Set by toolbar model selector |
| `SUMMARY` | `summary` | Summary | Conversation summarization and compaction |
| `TITLE` | `title` | Title Generation | Auto-generate conversation titles |
| `TRANSLATION` | `translation` | Translation | Text translation between languages |
| `RAG` | `rag` | RAG Embedding | Embedding model for document indexing and semantic search |
| `IMAGE_RECOGNITION` | `image` | Image Recognition | Describe and analyze images |
| `AUDIO_RECOGNITION` | `audio` | Audio Recognition | Transcribe and understand audio |
| `VIDEO_RECOGNITION` | `video` | Video Recognition | Analyze video content |
| `SUBAGENT` | `subagent` | Subagent (Task Tool) | Model used by spawned subagents |
| `IMAGE_GENERATION` | `image_gen` | Image Generation | Generate images from text prompts |
| `AUDIO_GENERATION` | `audio_gen` | Audio Generation | Generate speech and audio |
| `VIDEO_GENERATION` | `video_gen` | Video Generation | Generate video from prompts |
| `REALTIME_SPEECH` | `realtime_speech` | Realtime Speech | Live voice conversation with streaming audio |

Each task's assignment is a `TaskModelConfig(taskId, profileId?, modelId?)` persisted by `TaskModelStore` in the `task_models` SharedPreferences under key `task_<id>`. `resolve(task)` returns the `(profileId, modelId)` pair. When a task has no override, the caller falls back to the active profile.

### Seeded per-task defaults

`ProviderStore.seedTaskDefaults()` assigns defaults **only when a task has no `profileId` yet**, and points every seeded task at the default AIOPE Gateway profile. The exact defaults, from code:

| Task | Default model id (via AIOPE Gateway) |
|---|---|
| `RAG` | `google-ai-studio/models-gemini-embedding-2` |
| `REALTIME_SPEECH` | `google-ai-studio/gemini-3.1-flash-live-preview` |
| `SUMMARY` | `google-ai-studio/models-gemma-4-31b-it` |
| `TRANSLATION` | `google-ai-studio/models-gemma-4-26b-a4b-it` |
| `TITLE` | `google-ai-studio/models-gemma-4-26b-a4b-it` |
| `SUBAGENT` | `google-ai-studio/models-gemma-4-31b-it` |
| `IMAGE_RECOGNITION` | `google-ai-studio/models-gemma-4-26b-a4b-it` |
| `IMAGE_GENERATION` | `cloudflare/@cf-black-forest-labs-flux-1-schnell` |

The remaining configurable tasks (`AUDIO_RECOGNITION`, `VIDEO_RECOGNITION`, `AUDIO_GENERATION`, `VIDEO_GENERATION`) are **not** seeded with a default and fall back to the active profile until you set them. `CHAT` is never seeded here — it is driven by the toolbar model selector.

## MCP servers (extra tools over HTTP + SSE)

Beyond LLM providers, AIOPE connects to **Model Context Protocol (MCP)** servers to import extra tools at runtime. Connections are handled by `McpManager` (`settings/McpManager.kt`); configs live in `ToolStore.kt`.

An `McpServerConfig` (`ToolStore.kt`) has: `id` (8-char), `name`, `url`, `transport`, `headers`, `enabled`, `toolCount`, `status`, `error`, and OAuth2 fields. The relevant enums:
- `McpTransport { HTTP, SSE }` — two transports. Requests are POSTed as JSON-RPC 2.0. For `SSE` the `Accept` header is `text/event-stream`; for `HTTP` it is `application/json, text/event-stream`. When the response `Content-Type` is `text/event-stream`, `McpManager` reads `data:` lines and parses them (`parseSse`); otherwise it reads the body as JSON.
- `McpAuthType { NONE, HEADER, OAUTH2 }`. `HEADER` uses the custom `headers` map; `OAUTH2` injects `Authorization: Bearer <token>` via `effectiveHeaders()` and auto-refreshes an expired token (`isTokenExpired()`, `McpOAuth2.fetchToken`).
- `McpStatus { IDLE, CONNECTING, CONNECTED, ERROR }`.

Handshake and lifecycle: `initialize()` sends JSON-RPC `initialize` (protocol version `2024-11-05`, clientInfo name `AIOPE2` / version `1.0`) then the `notifications/initialized` notification; a session id from the `Mcp-Session-Id` response header is stored and echoed on later calls. `discoverTools()` calls `tools/list` and caches the returned tools; each tool is exposed to the model with a per-server prefix derived from the server name (`sanitizePrefix(name) + "_"`, lowercased, non-alphanumerics to `_`, max 16 chars; a bare `mcp_` prefix if the server can't be resolved). `executeTool()` strips that prefix and calls `tools/call`, joining the response `content[].text` parts. A heartbeat timer (`startHeartbeat()`) pings every connected server every 15 s with `tools/list`; a failure flips the server to `ERROR` with "Connection lost". Request timeouts are 10 s connect / 60 s read.

MCP tools are additive: they appear on top of AIOPE's built-in tool set and vary by which servers are enabled.

## Honesty notes (what the code does and does not enforce)

- **BYOK / OpenAI-compatible**: accurate — there is a single OpenAI-compatible client and any such endpoint can be added; no key is shipped for Novita.
- **API key storage**: keys are stored in the provider JSON in the app's local DB (`ProviderEntity`) and, for MCP, in `ToolStore`. This page makes **no claim** that provider API keys are encrypted at rest — nothing in these files performs encryption of the `apiKey` field. Treat keys as stored in app-private storage only.
- **MCP OAuth2**: refresh-on-expiry is implemented, but the token exchange in `McpManager` uses the refresh grant; there is no full interactive authorization-code flow wired into this manager.
- **Named vendors** (OpenAI/Anthropic/DeepSeek/OpenRouter/Groq/Ollama): these are **not** separate builtin templates — they are reachable via the gateway's namespaced model IDs or via a Custom provider, as described above.
- No **WCAG** or accessibility guarantee is asserted for the Providers UI here; the code does not encode such a property.
