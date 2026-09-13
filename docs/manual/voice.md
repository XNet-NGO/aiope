# Realtime Voice

keywords: voice, live, realtime, gemini live, bidigeneratecontent, gateway, wss, speech, transcription, echo cancellation, aec, speakerphone, floating mic, overlay, assist gesture, assistant role, headless, barge-in, session resumption, aoede, voice tools

Live, bidirectional spoken conversation with AIOPE. Audio streams both ways over a
WebSocket: the phone mic is captured as PCM and sent up; the model's speech comes
back as PCM and is played out. During a session the model can also call a curated
subset of AIOPE's tools and speak the results.

This page is written from the actual source. Primary files:
- `feature-chat/.../engine/RealtimeStreaming.kt` — the WebSocket protocol layer
- `feature-chat/.../engine/RealtimeAudioManager.kt` — mic capture, playback, echo cancellation
- `feature-chat/.../engine/VoiceSessionController.kt` — the single process-scoped session owner
- `feature-chat/.../engine/StreamEvent.kt` — event and data types
- `feature-chat/.../settings/VoiceSettingsScreen.kt` — voice selection
- `feature-chat/.../settings/VoiceOverlayControl.kt` — floating-mic overlay control
- `feature-chat/.../settings/AssistantRole.kt` — assistant-role helper
- `app/.../AiopeVoiceInteractionSession.kt`, `AiopeVoiceInteractionSessionService.kt`,
  `AssistantOverlayService.kt` — assist-gesture and floating-mic entry points

## Two transport protocols

`RealtimeStreaming` supports two wire protocols, chosen automatically at connect time by
`isGoogleDirect`, which is true when the provider's effective API base contains
`generativelanguage.googleapis.com`:

1. **AIOPE Gateway** (default) — a custom protocol over `wss://inf.xnet.ngo/ws/voice`.
   The URL is built as `wss://inf.xnet.ngo/ws/voice?model=<modelId>` with an
   `Authorization: Bearer <apiKey>` header (`buildGatewayConnection`).
2. **Google AI Studio Live API (direct)** — native Gemini `BidiGenerateContent` at
   `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<apiKey>`
   (`buildGoogleConnection`).

The default realtime model, when task resolution yields nothing, is
`google-ai-studio/gemini-3.1-flash-live-preview` (see `VoiceSessionController.start`).
`googleModelId()` strips any provider prefix (e.g. `google-ai-studio/`) and ensures a
`models/` prefix before it is sent in the setup.

## Connection lifecycle

`createStream()` returns a `Flow<StreamEvent>` built with `callbackFlow`, running on
`Dispatchers.IO`. On `onOpen`:
- **Gateway:** send `sendGatewaySetup`, set `audioManager.googleDirect = false`, attach the
  WebSocket, call `audioManager.startCapture()`, and emit `StreamEvent.Connected`.
- **Google-direct:** send `sendGoogleSetup`, set `audioManager.googleDirect = true`, attach
  the WebSocket, but **do not** start capture yet — capture begins only after the server
  acknowledges with `setupComplete`, at which point `Connected` is emitted.

Google Live API delivers all responses as **binary** frames; `onMessage(bytes)` decodes them
as UTF-8 JSON. A **heartbeat** `java.util.Timer` sends an empty frame every 15 seconds to
prevent NAT timeout. `awaitClose` cancels the heartbeat and calls `stop()`.

Two lifecycle signals are handled from the binary channel:
- `sessionResumptionUpdate.newHandle` is captured into `lastSessionHandle` for resumption.
- `goAway.timeLeft` is logged as a warning (server is about to disconnect).

## Google-direct setup message

`sendGoogleSetup` builds a `setup` object with:
- `model` = `googleModelId()`
- `generationConfig`: `responseModalities: ["AUDIO"]`, `thinkingConfig.thinkingLevel: "minimal"`,
  and a `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName` set to the selected voice.
- `systemInstruction` (only when the system prompt is non-blank).
- `outputAudioTranscription: {}` and `inputAudioTranscription: {}` — both transcription
  streams are requested.
- `tools.functionDeclarations` from `buildGoogleToolDeclarations()` (only when non-empty).
- `sessionResumption`: replays the provided `sessionHandle` if present, otherwise sends an
  empty object to request handles for future resumption.
- `contextWindowCompression`: `slidingWindow.targetTokens = 8000`, `triggerTokens = 25000`
  (extends past ~15 min, controls cost).
- `realtimeInputConfig.turnCoverage = "TURN_INCLUDES_ALL_INPUT"`.

The gateway setup (`sendGatewaySetup`) is much smaller: it sends `setup.systemPrompt` and
`setup.voiceName`, and only when the system prompt is non-blank.

## Curated voice tool subset (NOT all tools)

Voice sessions expose only a **curated allow-list of exactly 33 tools**, not the full tool
registry. `buildGoogleToolDeclarations()` filters the incoming `tools` list against the
`voiceTools` set (chosen for low latency / usefulness in spoken interaction). The 33 allowed
tool names are:

`run_sh`, `read_file`, `write_file`, `list_directory`, `edit_file`, `search_files`,
`get_location`, `open_intent`, `fetch_url`, `search_web`, `search_images`, `search_location`,
`query_data`, `send_notification`, `set_alarm`, `dismiss_alarm`,
`read_calendar`, `create_event`, `read_contacts`, `send_sms`, `read_sms`,
`memory_store`, `memory_recall`, `memory_forget`,
`rag_search`, `device_info`, `media_control`, `image_generate`,
`clipboard_copy`, `clipboard_read`, `http_request`,
`datetime_now`, `ssh_exec`.

Any tool not in this set is skipped. For each allowed tool, the declaration carries `name`,
`description`, and `parameters`; if a tool's parameter `properties` object is empty, both
`properties` and `required` are removed so the schema stays valid.

Note: in the pure-headless path the controller supplies **no** tools at all
(`toolExecutorProvider` stays null) — voice still works, just without tool calls.

## Messages and events

`StreamEvent` (in `StreamEvent.kt`) is the sealed event type:
`TextDelta`, `AudioChunk`, `TurnStart`, `TurnComplete`, `Interrupted`, `InputTranscription`,
`OutputTranscription`, `ToolCallEvent`, `Error`, `Connected`, `Disconnected`. A tool call
carries `FunctionCall(name, id, args)`.

Google-direct parsing (`parseGoogleMessages`) reads `serverContent.modelTurn.parts`, handling
**all** parts in one event (Gemini 3.1 can put audio and transcript in the same part list):
- `inlineData` with an `audio/` MIME type → `AudioChunk` (Base64-decoded PCM).
- `text` → `TextDelta`.
- `functionCall` → `ToolCallEvent`.
It also emits `TurnComplete` on `serverContent.turnComplete`, `Interrupted` on
`serverContent.interrupted` (barge-in), and `InputTranscription` / `OutputTranscription` from
both `serverContent` and top-level transcription objects. Top-level `toolCall.functionCalls`
are also handled.

Gateway parsing (`parseGatewayMessage`) reads `audio.pcm`, `text.delta`, `turnStart`,
`turnComplete`, `inputTranscription`, `outputTranscription`, `toolCall.functionCalls`, and
`error`.

## Sending input

- `sendAudio(pcmBase64)` — Google-direct wraps as `realtimeInput.audio` with
  `mimeType: "audio/pcm;rate=16000"`; gateway wraps as `audio.pcm`.
- `sendText(text)` — Google-direct uses `realtimeInput.text`; gateway uses `text.content`.
- `sendClientContent(parts)` — for multimodal turns; Google-direct sends any `inlineData`
  image parts first (as `realtimeInput.video`), then text, so the model has image context
  before the question.
- `sendToolResponse(responses)` — Google-direct wraps each as
  `toolResponse.functionResponses[].response.output.result`; gateway uses a flatter
  `response.result`.
- `endTurn()` — Google-direct sends `clientContent.turnComplete: true`; gateway sends
  `turnEnd: true`.
- `stop()` — stops capture, closes the WebSocket (code 1000).

## Audio: capture, playback, and echo cancellation

`RealtimeAudioManager` owns mic capture and playback. `RealtimeStreaming` sets the WebSocket
on it after connecting.

**Capture** (`startCapture`): uses `AudioRecord` with
`MediaRecorder.AudioSource.VOICE_COMMUNICATION`, mono, 16-bit PCM at `config.sampleRate`
(16000 Hz by default). It guards against re-entry by releasing any existing record first. Each
read is Base64-encoded (NO_WRAP) and sent as `realtimeInput.audio` (Google-direct) or
`audio.pcm` (gateway). Capture runs on `Dispatchers.IO`.

**Acoustic Echo Cancellation (AEC):** the `AudioRecord`'s `audioSessionId` is stored as
`sharedSessionId`. If `AcousticEchoCanceler.isAvailable()`, an `AcousticEchoCanceler` is
created on that session and enabled. Crucially, the **playback `AudioTrack` is rebuilt to share
the same session ID** so capture and playback are paired for AEC to work; if a track already
exists on a different session it is torn down and restarted with the shared session.

**Playback** (`startPlayback`): `AudioTrack` in stream mode at 24000 Hz output, mono, 16-bit,
with `USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH` attributes and a buffer sized 4× the
minimum. A dedicated playback thread drains a `LinkedBlockingQueue<ByteArray>` (poll timeout
100 ms) to avoid garbling. Incoming PCM chunks are queued via `playAudio`. On barge-in
(`Interrupted`), `clearPlayback()` empties the queue immediately.

**Speakerphone routing** is handled by the controller (`routeAudioToSpeaker`): it sets
`AudioManager.MODE_IN_COMMUNICATION`, turns on speakerphone, and on Android S+ explicitly
selects `TYPE_BUILTIN_SPEAKER` as the communication device. On stop, `restoreAudioMode`
returns to `MODE_NORMAL`, disables speakerphone, and clears the communication device.

## Transcription

Both directions are transcribed and surfaced as turns:
- `InputTranscription` (user speech) → persisted as a `user` message and emitted as
  `VoiceTurn.UserTranscript`.
- `OutputTranscription` (assistant speech) → buffered into `assistantBuffer` and emitted as
  `VoiceTurn.AssistantDelta`; the full text is persisted as an `assistant` message when the
  turn completes.

Google-direct requests both streams via the empty `inputAudioTranscription` /
`outputAudioTranscription` objects in setup.

## VoiceSessionController — single process-scoped owner

`VoiceSessionController` is a Hilt `@Singleton` scoped to the **app process**, not the Activity.
Because it lives in the process, the floating overlay and the assistant session can start/stop
live voice **headlessly** (no need to foreground the app). Being the single authority for voice
structurally prevents double-session and "can't hang up" bugs.

Key behavior:
- `VoiceState` enum: `IDLE`, `STARTING`, `LISTENING`, `SPEAKING`, exposed as a `StateFlow`.
- `VoiceTurn` sealed type: `UserTranscript`, `AssistantDelta`, `Error`, emitted on a
  `MutableSharedFlow` (replay=0, extraBufferCapacity=64) so headless emits don't block.
- `toggle()` / `start()` / `stop()` are `@Synchronized`; `toggle` starts if idle, stops if
  active.
- On start it resolves provider+model for `ModelTask.REALTIME_SPEECH` via `TaskModelStore`,
  builds a `RealtimeAudioManager` at the model sample rate, starts playback, routes to
  speaker, then collects the `RealtimeStreaming` flow.
- Tool calls received during a session are executed via a `ToolExecutor` (from
  `toolExecutorProvider`) and returned with `sendToolResponse`.
- The live system prompt is: *"You are AIOPE in a live voice session. Be concise. Execute
  tools directly when needed."* If `pendingScreenContext` is set (from the assist session), the
  visible screen text (truncated to 4000 chars) is appended.
- `ngo.xnet.aiope.core.preferences.VoiceBridge.isActive` is flipped true on start and false on
  stop so the rest of the app knows voice is live.

**Wiring:** `ensureHeadlessWiring()` sets up minimal collaborators (provider store,
conversation id, DB message sink) for headless overlay/assist use, writing turns into a
"Voice" conversation via `chatDao`. When the app is open, the ViewModel calls
`markWiredByViewModel()` to claim richer wiring (including tools). Persistence goes straight to
the DB through `messageSink` so turns are present when the user next opens the app.

## Entry points: floating mic and assist gesture

**Floating mic overlay** — `AssistantOverlayService` (in `:app`) is a foreground service that
draws a 56dp draggable mic bubble over other apps using
`TYPE_APPLICATION_OVERLAY` (SYSTEM_ALERT_WINDOW). It is purely AIOPE's own overlay window — it
does not read from or inject input into other apps. Dragging moves it; a tap (movement < 12px)
calls `launchVoice()`, which toggles the shared `VoiceSessionController` **headlessly** via a
Hilt `EntryPoint` (`VoiceOverlayEntryPoint`) — without launching the Activity. The bubble is
live-tinted by voice state: blue = IDLE, amber = STARTING, green = LISTENING, purple = SPEAKING.
It is only started when the user enables the feature and has granted draw-over-other-apps
permission (`canDraw`). `VoiceOverlayControl` (in feature-chat) starts/stops this service by
explicit class name (`ngo.xnet.aiope.AssistantOverlayService`) so feature-chat can control the
:app service without a compile dependency.

**Assist gesture / long-press home** — `AiopeVoiceInteractionSessionService` produces
`AiopeVoiceInteractionSession`, triggered when AIOPE holds the system ASSISTANT role. In
`onHandleAssist` it captures the foreground app's visible content by walking the
`AssistStructure` view tree (collecting `text` and `contentDescription`) plus any
`AssistContent.structuredData` / `webUri`, then stores it (truncated to 4000 chars) as
`controller().pendingScreenContext`. In `onShow` it starts the headless voice session via the
shared controller and immediately hides its own UI — so AIOPE's UI never comes to the
foreground for an assist invocation.

**Assistant role** — `AssistantRole` wraps Android's `RoleManager` for `ROLE_ASSISTANT`
(Android Q+). It exposes `isAvailable`, `isHeld`, and `requestIntent` (which launches the
system consent dialog; only the user can grant the role). Holding the role makes AIOPE the
device's assistant for the assist gesture / long-press home handoff. Holding it does not by
itself grant background-network parity.

## Voice selection

The spoken voice is chosen in `VoiceSettingsScreen`, stored in SharedPreferences
(`voice_settings` / key `voice_name`), and read by `getVoiceName(context)`. The default is
**`Aoede`** (shown to the user as "Aria"). There are **30** selectable Gemini prebuilt voices,
each with a friendly display name and a style label — for example: `Aoede`→Aria (Breezy),
`Puck`→Jake (Upbeat), `Kore`→Maya (Firm), `Charon`→Marcus (Informative), `Zephyr`→Zoe (Bright),
`Sulafat`→Sophie (Warm). The API voice name (not the display name) is what is sent in
`prebuiltVoiceConfig.voiceName`.
