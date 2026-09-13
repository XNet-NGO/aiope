# Model-Per-Task Settings

keywords: model per task, per-task model, task routing, default models per task, TaskModelScreen, TaskModelStore, ModelTask, TaskModelConfig, seedTaskDefaults, active profile fallback, summary model, title model, translation model, RAG embedding model, image recognition, audio recognition, video recognition, subagent model, image generation, audio generation, video generation, realtime speech, toolbar model selector, chat model

AIOPE can run different internal jobs on different models. The **Default Models per Task** screen (`TaskModelScreen.kt`) lets you assign a specific provider profile — and optionally a specific model within it — to each task. Anything you don't assign falls back to the currently active profile. This page covers the per-task screen and the full task list; the broader provider/connection story lives in `providers.md`.

This page is written from the actual source. Primary files:
- `feature-chat/.../settings/TaskModelScreen.kt` — the per-task assignment UI
- `core-network/.../network/TaskModelStore.kt` — the `ModelTask` enum, `TaskModelConfig`, and persistence
- `feature-chat/.../settings/ProviderStore.kt` — `seedTaskDefaults()`, the seeded default model per task

## What per-task routing is

Each internal task (summarization, title generation, embeddings, subagents, media generation, etc.) resolves to a model independently of the chat model. The assignment for a task is a `TaskModelConfig(taskId, profileId?, modelId?)` (`TaskModelStore.kt`):
- `profileId == null` — no override; the task uses the **active profile** (the fallback).
- `profileId` set, `modelId == null` — use that profile's `selectedModelId`.
- `profileId` and `modelId` set — use exactly that profile + model.

`TaskModelStore` persists each task under key `task_<id>` in the `task_models` SharedPreferences. `getTaskConfig(task)` reads it (returning an empty config when absent), `setTaskConfig(task, config)` writes it, and `clearTaskConfig(task)` removes the override (restoring the active-profile fallback). The screen's header card states this directly: "Assign different models to different tasks. Each task falls back to the active profile if not configured."

## The task list

`ModelTask` (`TaskModelStore.kt`) defines **13** tasks. The screen renders `ModelTask.configurable`, which is every entry **except `CHAT`** — so **12** cards are shown. `CHAT` is intentionally excluded because it is set by the toolbar model selector in the chat screen, not here.

| `ModelTask` | id | label | description (from code) | Shown in screen? |
|---|---|---|---|---|
| `CHAT` | `chat` | Chat | Set by toolbar model selector | No (toolbar only) |
| `SUMMARY` | `summary` | Summary | Conversation summarization and compaction | Yes |
| `TITLE` | `title` | Title Generation | Auto-generate conversation titles | Yes |
| `TRANSLATION` | `translation` | Translation | Text translation between languages | Yes |
| `RAG` | `rag` | RAG Embedding | Embedding model used for document indexing and semantic search | Yes |
| `IMAGE_RECOGNITION` | `image` | Image Recognition | Describe and analyze images | Yes |
| `AUDIO_RECOGNITION` | `audio` | Audio Recognition | Transcribe and understand audio | Yes |
| `VIDEO_RECOGNITION` | `video` | Video Recognition | Analyze video content | Yes |
| `SUBAGENT` | `subagent` | Subagent (Task Tool) | Model used by spawned subagents for research and background tasks | Yes |
| `IMAGE_GENERATION` | `image_gen` | Image Generation | Generate images from text prompts | Yes |
| `AUDIO_GENERATION` | `audio_gen` | Audio Generation | Generate speech and audio | Yes |
| `VIDEO_GENERATION` | `video_gen` | Video Generation | Generate video from prompts | Yes |
| `REALTIME_SPEECH` | `realtime_speech` | Realtime Speech | Live voice conversation with streaming audio | Yes |

## CHAT is set elsewhere (no seed)

`CHAT` is deliberately absent from this screen (`ModelTask.configurable = entries.filter { it != CHAT }`) and is **not** seeded by `seedTaskDefaults()`. The chat model is chosen by the **toolbar model selector** on the chat screen, which changes the active profile's selected model. If you're looking for "how do I change the model the assistant chats with," that control is in the chat toolbar, not in Default Models per Task.

## Seeded per-task defaults

`ProviderStore.seedTaskDefaults()` runs on startup and assigns a default **only when a task has no `profileId` yet** (`if (taskStore.getTaskConfig(task).profileId == null)`). Every seeded task points at the default **AIOPE Gateway** profile (`builtinId == "aiope_gateway"`, `id = default_gateway`). If no gateway profile exists, no defaults are seeded (the function returns early). The exact seeded model IDs, from code:

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

These are the only eight tasks seeded. The remaining configurable tasks — `AUDIO_RECOGNITION`, `VIDEO_RECOGNITION`, `AUDIO_GENERATION`, `VIDEO_GENERATION` — get **no seed** and fall back to the active profile until you assign them explicitly. Because seeding is guarded by "no `profileId` yet," it never overwrites a choice you've already made.

## Reading a task card

Each configurable task is a `TaskCard`. Collapsed, it shows:
- The task `label` (bold) and `description`.
- A "Config: …" / "Model: …" summary of the current assignment:
  - `Config` shows the assigned profile's `label`, or **"Active profile"** when there's no override.
  - `Model` shows the config's `modelId`, else the assigned profile's `selectedModelId`, else the literal `"default"`.
- A chevron (up/down) indicating expand state.

Tapping the card header toggles the expanded selection area.

## Changing a task's model in the UI

Open **Settings → Default Models per Task**, then for the task you want:

1. **Tap the task card** to expand it. A "Select configuration" section appears.
2. To route the task through the **active profile** (i.e. remove any override), tap **"Use active profile (default)"**. This calls `clearTaskConfig(task)`, resets the card to an empty `TaskModelConfig`, and collapses it. The default option shows a check mark when it is the current selection (`tc.profileId == null`).
3. To pin a **specific profile**, tap that profile row:
   - If the profile exposes **one model or fewer**, tapping it immediately assigns `TaskModelConfig(taskId, profile.id, profile.selectedModelId)` via `setTaskConfig(...)` and collapses the card. Its row shows the profile `label` and its `selectedModelId`.
   - If the profile exposes **more than one model**, tapping it does **not** assign yet — it expands a sub-list of that profile's models (the row shows "N models" and a chevron).
4. In the expanded model sub-list, **tap a model** to assign `TaskModelConfig(taskId, profile.id, model.id)`. This persists via `setTaskConfig(...)` and collapses both the model list and the card. The chosen model row shows a check mark.

The selected profile/model row is highlighted (primary container color + primary border), and the "Use active profile (default)" row is highlighted when no override is set.

### Where the model list per profile comes from

For each profile row, the model list is resolved in this priority order (`TaskModelScreen.kt`):
1. Fresh model cache for that profile (`providerStore.getModelCache(profile.id)`).
2. Stale model cache for that profile (`getModelCacheStale(profile.id)`).
3. Stale cache of a sibling profile sharing the same `builtinId`.
4. The template's `defaultModels` for that `builtinId` (`ProviderTemplates.byId[...]`).
5. Empty list if none of the above resolve.

So which models you can pick per profile depends on what has been fetched/cached for that provider; see `providers.md` for how model caches are populated.

## Honesty notes (what the code does and does not enforce)

- **Persistence is per-task in SharedPreferences**, not in the provider DB. Clearing app storage resets all task overrides; startup will then re-seed the eight defaults above (assuming a gateway profile exists).
- **Fallback wording**: the UI promises each unassigned task "falls back to the active profile." `TaskModelStore.resolve(task)` here only returns the stored `(profileId, modelId)` pair — the active-profile fallback itself is applied by the calling code that consumes `resolve()`, not inside `TaskModelStore`.
- **No validation** that a seeded/assigned model id actually exists on the provider or supports the task's modality. The screen lets you assign any model the profile lists; if the underlying model can't perform the task, that surfaces at call time, not here.
- **The "N models" threshold** is literal: profiles with `models.size <= 1` assign on a single tap and never show a model sub-list, so if a profile's cache is empty you can still pin it (it will use the profile's `selectedModelId`).
- **CHAT** never appears on this screen and is never seeded here by design; it is controlled by the toolbar model selector.
