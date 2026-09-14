# Conversations

keywords: conversations, chat, threads, auto-title, edit and resend, retry, fork, compact, auto-compact, attachments, images, pdf, pdfbox, text files, speech-to-text, text-to-speech, tts, stt, inline translation, translate, export, share, markdown, json, pdf export, katex, latex, room, sqlite, on-device storage, lanes, media lane, token trimming

AIOPE keeps every chat as a persistent, on-device conversation. Threads are auto-titled, editable, forkable, and compactable; messages can carry image/PDF/text attachments, be spoken aloud or dictated, translated inline, and the whole thread can be exported as text, Markdown, JSON, or a math-rendering PDF. All conversation and message data lives in a local Room/SQLite database — nothing about the thread structure is stored off-device by AIOPE itself.

This page is written from the actual source. Primary files:
- `feature-chat/.../ChatViewModel.kt` — the conversation engine: send, editAndResend, retry, fork, compact/auto-compact, title generation, export/share, translation
- `feature-chat/.../ShareFormatSheet.kt` — the export-format bottom sheet (txt / md / pdf / json)
- `feature-chat/.../LatexPdfExporter.kt` — PDF export via WebView + KaTeX (LaTeX/math rendering)
- `feature-chat/.../MessageBubble.kt` — per-message actions: Speak (TTS), Translate (inline), Export PDF, Retry, Compact, Fork
- `feature-chat/.../ChatScreen.kt` — attachment picker (image/PDF/text) and the speech-to-text mic
- `feature-chat/.../db/ChatDatabase.kt` — the Room schema (`ConversationEntity`, `MessageEntity`, `ChatDao`)
- `feature-chat/.../ChatMessage.kt` — the in-memory message model and `Role` enum

## On-device Room storage

Conversations and messages are stored in a Room database, `ChatDatabase`, currently at
**schema version 9**. The database declares **12 entities**: `ConversationEntity`,
`MessageEntity`, `MemoryEntity`, `ProviderEntity`, `ToolToggleEntity`, `McpServerEntity`,
`ModelCacheEntity`, `SettingsKvEntity`, `AgentEntity`, `AgentTaskEntity`,
`ScheduledTaskEntity`, and `TaskRunEntity`. The two that back conversations are:

- **`conversations`** (`ConversationEntity`) — `id` (PK, String), `title` (default `"New Chat"`),
  `agentName` (default `"default"`), `createdAt`, `updatedAt`. Listed via
  `getConversations()` ordered by `updatedAt DESC`.
- **`messages`** (`MessageEntity`) — `id` (PK), `conversationId`, `role`, `content`,
  `imagePaths` (comma-separated relative file paths, default empty), `timestamp`,
  `inputTokens`, `outputTokens`, `latencyMs`, and `modelUsed`. It has a **foreign key** to
  `conversations(id)` with `onDelete = CASCADE`, so deleting a conversation removes its
  messages. Fetched via `getMessages(convId)` ordered by `timestamp ASC`.

Message roles come from the `Role` enum in `ChatMessage.kt`: `USER` ("user"),
`ASSISTANT` ("assistant"), `SYSTEM` ("system"), `TOOL` ("tool"), and `AGENT_REPORT`
("agent_report").

Attachment images are not stored as blobs in the DB — `ImageProcessor.saveImagesToDisk`
writes them under the app's `filesDir`, and `MessageEntity.imagePaths` records the relative
paths. On load, `ChatViewModel` rebuilds `file://` URIs from those paths and drops any files
that no longer exist.

## Multiple auto-titled conversations

Each conversation has its own `id` and `title`. On the first exchange in a thread
(`_messages.value.size <= 2`), `send()` does two things:
1. Immediately sets a provisional title from the user's message: `updateConversation(conversationId, text.take(50))`.
2. Calls `generateTitle(firstMessage)` to replace it with a model-generated title.

`generateTitle` resolves the provider+model for `ModelTask.TITLE` (via `TaskModelStore`),
prompts *"Generate a short title (max 6 words) for a conversation that starts with: …"*, and
stores the trimmed result (**capped at 60 characters**) with `updateConversation`. The source
contains a "try local model first" branch, but that branch currently always evaluates to
`null` (it is a stub), so **in practice titling goes through the cloud/task-model path** — this
page states that honestly rather than claiming an on-device titling model is wired.

Conversation management methods:
- `newConversation()` — starts a fresh `id`, clears the message list, and inserts a
  `ConversationEntity` tagged with the current lane.
- `loadConversation(id)` / `loadConversationMessages(id)` — switch to and hydrate a thread.
- `deleteConversation(id)` — deletes the row (messages cascade); if it was the active thread,
  a new one is started.

### Two conversation lanes (Chat/Plan/Build vs. Media)

There are **two isolated lanes**. Chat, Plan, and Build modes **share** one lane
(`chatConversationId`); Media mode has its **own** lane (`mediaConversationId`) so image
generation history never bleeds into text chat and vice versa. Lane membership is tagged on
the conversation via `agentName`: the media tag is the literal string `"media"`, everything
else is `"default"`. The conversation list is filtered by lane, and switching across the Media
boundary reloads that lane's most recent thread.

## Edit & Resend

`editAndResend(text, atIndex)` truncates the thread at `atIndex` and re-sends new text:
- `truncateAt(atIndex)` cancels any active stream, keeps `_messages.take(atIndex)`, and calls
  `chatDao.deleteMessagesAfter(conversationId, cutTimestamp)` so the DB matches the trimmed UI.
- It then calls `send(text)` to generate a fresh response from that point.

Exposed in the message menu as **"Edit & Resend"**.

## Retry

`retry(atIndex)` re-runs the last user turn when the given index is an **assistant** message:
- It removes that assistant message and everything after it (in memory and via
  `deleteMessagesAfter`).
- It finds the last remaining `USER` message and calls `resend(...)` on its content.

`resend` rebuilds the message list into role pairs, skips blank assistant messages, and streams
a new completion against the **active** provider (`providerStore.getActive()`). Exposed as
**"Retry"** in the message menu and as a refresh action icon.

## Fork

`fork(atIndex)` branches a conversation into a new thread:
- It copies `_messages.take(atIndex + 1)` into a brand-new conversation (`UUID`).
- The new conversation title is `"Fork: <first user message, first 30 chars>"` (or
  `"Fork: chat"` if there is no user message).
- Each forked message is re-inserted with a **new message id** under the new conversation,
  then AIOPE switches to the fork.

Exposed as **"Fork"** in the message menu.

## Compact and auto-compact

Compaction summarizes the earlier part of a thread into a single `SYSTEM` message so the live
tail keeps fitting in the context window.

**Manual compact** — `compact(atIndex)`:
- No-ops when `atIndex < 1` or the thread has fewer than 4 messages.
- **Always preserves the last 3 messages** (`idx = minOf(atIndex, msgs.size - 4)`), summarizing
  only messages `0..idx`.
- The transcript (each message truncated to 2000 chars) is sent to the `ModelTask.SUMMARY`
  model with a fixed prompt that asks for sections **CONTEXT, DECISIONS, FACTS, TASKS,
  PREFERENCES, OPEN**, requires exact strings (paths/commands/URLs/model names/numbers) to be
  preserved verbatim, and caps the summary at **under 800 words**.
- The result replaces the compacted range with two `SYSTEM` messages: the summary itself and an
  indicator message **"⟳ Context compacted — earlier messages summarized"**. It then rewrites
  the DB: deletes all messages in the conversation, then re-inserts summary + indicator +
  remaining tail.

Exposed as **"Compact"** in the message menu.

**Auto-compact** — `maybeAutoCompact(mc)`:
- Runs in the `finally` block of every `send()`, but **only when `mc.autoCompact` is true**
  (the model config's auto-compact toggle). If disabled it returns immediately.
- It sums approximate tokens across all messages (`TokenCounter.count`) and triggers when the
  total exceeds **95% of `mc.contextTokens`** *and* the thread has more than 4 messages.
- When triggered it calls `compact(msgs.size - 4)` — i.e. it compacts everything except the last
  3 messages, matching the manual path's tail-preservation.

Note: auto-compact fires **after** a response completes, not preemptively before a send; a
single very large turn is still sent as-is. Separately, `send()` also **trims history to fit**
`mc.contextTokens` at build time (dropping oldest messages that don't fit) — this trimming is
independent of compaction and does not rewrite the stored thread.

## Attachments (images, PDF, text)

Attachments are added from the composer in `ChatScreen`. A single `GetContent()` picker
branches on MIME type:

- **Images** (`mime.startsWith("image/")`) — the URI is added to `pendingImages` and shown as a
  48dp thumbnail row ("N image(s)"). On send, images are saved to disk by `ImageProcessor` and,
  for the generation path, encoded to base64. Image understanding is routed through the
  `ModelTask.IMAGE_RECOGNITION` task model when images are attached.
- **PDF** (`mime == "application/pdf"`) — read off the main thread and extracted with **PDFBox
  for Android**: `PDFBoxResourceLoader.init`, `PDDocument.load(bytes)`, then `PDFTextStripper`.
  The extracted text is capped at **100,000 characters** and prepended with a
  `[<name> - <pageCount> pages]` header; empty PDFs yield `[No extractable text]`. The text is
  appended into the composer text field (not stored as a separate file).
- **Any other file** (treated as text) — read via `bufferedReader().readText()`, capped at
  **10,000 characters**, and appended as `[<name>]\n<content>`. Read failures fall back to
  `[Attached: <uri>]`.

There is also a camera capture path (`photoLauncher` → a `photo_<ts>.jpg` in the cache dir via
`FileProvider`) that feeds the same image-attachment flow.

## Speech-to-text (dictation)

The composer's mic button uses AIOPE's **fully on-device** speech-to-text (`VoiceInputManager` →
`SherpaSttEngine`, a sherpa-onnx streaming zipformer). It streams interim (partial) text into the
composer as you speak and commits a final transcript when you stop — no audio leaves the device.
If the offline STT model isn't downloaded yet, the mic button offers to fetch it
(`SherpaSttBootstrap`). See [Offline Speech-to-Text](speech-to-text.md) for the full picture,
including the system-wide recognizer.

This is distinct from the **realtime voice call** button (phone-call icon), which starts a live
bidirectional voice session through `VoiceSessionController` — see the Realtime Voice manual
page.

## Text-to-speech (Speak)

Every assistant bubble has a **Speak** action (`MessageBubble.kt`). It lazily creates an
Android `TextToSpeech` engine, sets the language to `Locale.getDefault()`, and speaks the
message content with `QUEUE_FLUSH`. While speaking, the icon toggles to **Stop**
(`VolumeOff`) and `tts.stop()` halts playback; an `UtteranceProgressListener` resets the state
when the utterance finishes or errors. This uses the device's TTS engine and the **default
locale** — there is no per-message voice-language selector in this path.

## Inline translation (18 languages)

The **Translate** action on a message opens a dropdown of **exactly 18 languages**, defined in
`MessageBubble.kt`:

**English, Spanish, French, German, Chinese, Japanese, Korean, Portuguese, Russian, Hindi,
Italian, Dutch, Polish, Czech, Romanian, Swedish, Urdu, Arabic.**

Selecting one calls `ChatViewModel.translateMessage(messageId, language)`, which prompts the
`ModelTask.TRANSLATION` model with *"Translate the following text to `<language>`. Reply with
ONLY the translation…"* and **streams the result into the message's `translation` field**. The
translation renders inline beneath the original as a labelled "Translation" surface (selectable
text) — the original message is preserved, not replaced. As with titling, the "try local model
first" branch is currently a stub that returns `null`, so translation goes through the
cloud/task-model path.

## Export and share

Exporting is offered in two places, both building on the same content generator.

### The export sheet (whole conversation)

`ShareFormatSheet` is a modal bottom sheet titled **"Export Conversation"** with **four
formats**:

1. **Plain Text** — "Share as a readable .txt file" → format `"txt"`.
2. **Markdown** — "Share as a formatted .md file" → format `"md"`.
3. **PDF** — "Generate a PDF document (with math support)" → format `"pdf"`.
4. **JSON** — "Raw structured data format" → format `"json"`.

The selected format is passed to `ChatViewModel.shareConversation(format, context)`.
`getConversationContent(format)` builds the payload:

- **`json`** — a JSON object `{ title, exported, messages[] }`, each message carrying `role`
  and `content` (plus `tool_calls` when present). (Note: the JSON `title` field is set to the
  `conversationId`, not the human title.)
- **`txt`** — plain lines: `User: …`, `Assistant: …`, `System: <first 200 chars>`.
- **`md` / `pdf`** — the same Markdown base: a `# AIOPE Conversation` heading, `## User` /
  `## Assistant` sections, system notes as blockquotes, and any tool calls folded into a
  `<details><summary>Tools used</summary>` block.

For `txt`, `md`, and `json`, the content is written to a file in `cacheDir/share`
(`aiope_export_<ts>.<format>`) and shared via an `ACTION_SEND` chooser ("Share conversation")
with MIME `application/json`, `text/markdown`, or `text/plain` respectively.

### PDF export with LaTeX/math (KaTeX)

For `pdf`, `shareConversation` hands the Markdown/LaTeX content to
**`LatexPdfExporter.export`**. This renders the document in an off-screen `WebView` using
**KaTeX** (loaded from `cdn.jsdelivr.net`) and opens the **system print dialog** (the user picks
"Save as PDF" or a printer). Key behavior from source:

- `transpileToHtml` converts a LaTeX-ish document to HTML: it strips the preamble, **preserves
  math environments** (`equation`, `align`, `gather`, `multline`, `flalign`, `alignat`) as
  `$$…$$` for KaTeX, and maps common LaTeX constructs — headings (`\chapter`/`\section`/…),
  inline formatting (`\textbf`, `\textit`, `\texttt`, `\underline`, `\textsc`), lists
  (`itemize`/`enumerate`), code (`verbatim`/`lstlisting`/`minted`), quotes, `tabular` tables,
  `\href`/`\url` links, and a `thebibliography` → References list.
- `renderMathInElement` renders `$$…$$` (display) and `$…$` (inline) math.
- The WebView is attached off-screen with real US-Letter dimensions (blank PDFs result from a
  zero-size WebView), prints after a short render delay, and has a **12-second safety timeout**
  that forces the print dialog open even if the CDN is slow/offline (in which case math may not
  render but the dialog still opens).
- Media size is fixed to `NA_LETTER`; the print job name is "AIOPE Conversation".
- `LatexPdfExporter.shareFile` can additionally share a produced PDF file via `ACTION_SEND`
  with MIME `application/pdf`.

Because PDF uses the Android print pipeline, it **requires an active screen/Activity**; if none
is found it toasts "PDF export requires an active screen".

### Per-message "Export PDF"

Individual assistant bubbles also expose an **"Export PDF"** menu item (and a code/LaTeX block
export callback), both calling `LatexPdfExporter.export(ctx, message.content)` directly — so a
single message can be turned into a math-rendering PDF without exporting the whole thread.

## Persistence and crash safety

Streaming responses are persisted incrementally. `collectStream` throttles partial writes
(roughly every 1.2s) into the `messages` table under the assistant message's id, and the
`finally` blocks upsert the final content inside `NonCancellable` scope. This means an
interrupted or backgrounded stream still leaves the latest streamed text in the DB, and it
reappears on reload. A stream-level `CoroutineExceptionHandler` also appends an
`_(interrupted: …)_` note to the last assistant message rather than crashing.
