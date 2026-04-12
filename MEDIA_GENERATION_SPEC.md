# Media Generation Feature Spec

## Overview
Treat image/audio/video generation models as first-class providers — same config flow as text LLMs. Users configure them in Settings, assign them to tasks, and use them directly in chat.

## Architecture

### 1. Model Capability Flags
**LlmProvider.kt — ModelDef** (add output flags):
```
outputsImage: Boolean = false   // can generate images (DALL-E, FLUX, SD)
outputsAudio: Boolean = false   // can generate speech/audio (TTS, music)
outputsVideo: Boolean = false   // can generate video (Sora, Runway)
```

**LlmProvider.kt — ModelConfig** (add per-model output settings):
```
imageSize: String? = null       // "512x512", "1024x1024", etc.
imageSteps: Int? = null         // diffusion steps
imageGuidance: Float? = null    // CFG scale
audioVoice: String? = null      // voice ID for TTS
audioFormat: String? = null     // "mp3", "wav", etc.
```

### 2. Task Model Assignments
**TaskModelStore.kt — add generation tasks:**
```kotlin
IMAGE_GENERATION("image_gen", "Image Generation", "Generate images from text prompts")
AUDIO_GENERATION("audio_gen", "Audio Generation", "Generate speech and audio")
VIDEO_GENERATION("video_gen", "Video Generation", "Generate video from prompts")
IMAGE_EDIT("image_edit", "Image Editing", "Edit/alter images with prompts")
```

### 3. Provider Setup (Settings UI)
No new UI screens needed. Existing flow:
1. Add Provider → select "Cloudflare" (or Custom)
2. Enter API key
3. Fetch models → model list includes image models
4. Select model (e.g. `@cf/black-forest-labs/flux-1-schnell`)
5. In model config, output type auto-detected or manually set
6. Go to Task Models → assign "Image Generation" → pick this provider+model

### 4. Chat Integration — Two Modes

#### Mode A: Tool-based (LLM decides to generate)
LLM has a `generate_image` tool. When user says "draw me a cat", the LLM calls:
```json
{"name": "generate_image", "arguments": {"prompt": "a cute orange cat"}}
```
Tool handler:
1. `resolveTaskModel(IMAGE_GENERATION)` → gets provider + model
2. Checks provider's API format:
   - OpenAI spec: POST `/v1/images/generations` with `{model, prompt, size, n}`
   - Cloudflare: POST `/ai/run/{model}` with `{prompt}`
3. Gets back image bytes or URL
4. Saves to app storage, returns `![generated](file:///path/to/img.png)`
5. Coil renders inline

#### Mode B: Direct generation (user selects image model in toolbar)
User switches model in toolbar dropdown to an image model.
- Input field behavior changes: prompt goes directly to image API (not chat completions)
- Attached photo + prompt → image edit API (`/v1/images/edits`)
- Text only → text-to-image API (`/v1/images/generations`)
- Response rendered as inline image in chat bubble

### 5. API Endpoint Mapping

| User Action | API Endpoint | Input | Output |
|---|---|---|---|
| Text prompt → image model selected | POST `/v1/images/generations` | `{prompt, model, size}` | PNG/base64 |
| Photo + prompt → image model selected | POST `/v1/images/edits` | multipart: image + prompt | PNG/base64 |
| Text prompt → audio model selected | POST `/v1/audio/speech` | `{input, model, voice}` | audio bytes |
| Audio + prompt → audio model selected | POST `/v1/audio/transcriptions` | multipart: audio + model | text |
| Text prompt → video model selected | POST `/v1/videos/generations` | `{prompt, model}` | video URL |

### 6. Cloudflare-Specific Handling
Cloudflare uses different endpoint pattern:
- Text-to-image: POST `/ai/run/@cf/black-forest-labs/flux-1-schnell`
- Image-to-image: POST `/ai/run/@cf/runwayml/stable-diffusion-v1-5-img2img`
- Inpainting: POST `/ai/run/@cf/runwayml/stable-diffusion-v1-5-inpainting`

Use `endpointOverride` in ModelConfig to handle this — or detect from apiBase containing "cloudflare.com".

### 7. Response Handling in MessageBubble
MessageBubble already renders:
- `![alt](url)` → Coil AsyncImage (just added today)
- Need to add: audio player widget for audio responses
- Need to add: video player widget for video responses

### 8. Files Changed

| File | Changes |
|---|---|
| `LlmProvider.kt` | Add `outputsImage/Audio/Video` to ModelDef, add image/audio settings to ModelConfig |
| `TaskModelStore.kt` | Add IMAGE_GENERATION, AUDIO_GENERATION, VIDEO_GENERATION, IMAGE_EDIT tasks |
| `ChatViewModel.kt` | Add `generate_image`, `generate_audio`, `generate_video` tools + handlers; add `sendMedia()` for direct mode |
| `ChatScreen.kt` | Detect when selected model is media type, change input behavior |
| `ChatMessage.kt` | Add `mediaType` field (text/image/audio/video) to track response type |
| `MessageBubble.kt` | Add audio player and video player composables for media responses |
| `StreamingOrchestrator.kt` | No changes — media gen doesn't use SSE streaming |

### 9. Implementation Order
1. Add capability flags to LlmProvider.kt
2. Add generation tasks to TaskModelStore.kt  
3. Add generate_image tool + handler (Mode A — LLM-driven)
4. Add direct media send in ChatViewModel (Mode B — user-driven)
5. Update ChatScreen model picker to show model type indicators
6. Update MessageBubble for audio/video rendering
7. Test with Cloudflare FLUX

### 10. Available Models (Confirmed Working)
**Cloudflare (free tier):**
- `@cf/black-forest-labs/flux-1-schnell` — 1.7s, tested ✓
- `@cf/black-forest-labs/flux-2-klein-4b`
- `@cf/black-forest-labs/flux-2-klein-9b`
- `@cf/black-forest-labs/flux-2-dev`
- `@cf/bytedance/stable-diffusion-xl-lightning`
- `@cf/lykon/dreamshaper-8-lcm`
- `@cf/stabilityai/stable-diffusion-xl-base-1.0`
- `@cf/runwayml/stable-diffusion-v1-5-img2img` (image edit)
- `@cf/runwayml/stable-diffusion-v1-5-inpainting` (inpainting)
- `@cf/leonardo/phoenix-1.0`
- `@cf/leonardo/lucid-origin`
