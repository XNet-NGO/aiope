# Streaming & Reasoning

keywords: streaming, stream, SSE, server-sent events, EventSource, okhttp, token streaming, reasoning, reasoning_content, reasoning field, think tag, thinking, chain of thought, collapsible thinking, reasoning tabs, cancel stream, stop generating, retries, backoff, tool loop, maxRounds, tool call parsing, markdown, UniversalMarkdown, LaTeX, code blocks, tables, streaming animation, StreamingOrchestrator, ChatStreamChunk, ReasoningTabStrip, ToolTabStrip, collectStream

AIOPE streams model output token-by-token over Server-Sent Events (SSE), surfacing three interleaved kinds of output as they arrive: assistant text (rendered as live Markdown), reasoning / "thinking", and tool activity. One engine — `StreamingOrchestrator` — drives all of it, and the same engine is reused for interactive chat, agents, multi-agent pipelines, background scheduled runs, and utility calls (titles, translation). This page is written from the actual source under `feature-chat/src/main/kotlin/ngo/xnet/aiope/feature/chat/`.

Primary files:

- `engine/StreamingOrchestrator.kt` — the SSE client, tool loop, reasoning/tag parsing, and retries.
- `engine/ChatStreamChunk.kt` — the per-chunk data type emitted to the UI layer.
- `ChatViewModel.kt` — `collectStream(...)` consumes chunks, accumulates reasoning blocks and tool activity, throttles UI updates, and owns cancellation via `streamingJob`.
- `MessageBubble.kt` — renders the assistant bubble: `UniversalMarkdown` for content, plus the reasoning and tool strips.
- `ReasoningTabStrip.kt` / `ToolTabStrip.kt` — the collapsible "thinking" tabs and tool-activity tabs.

## Overview

The public entry point is `StreamingOrchestrator.stream(messages, imageBase64s)`, which returns a `Flow<ChatStreamChunk>` built with `callbackFlow` and pinned to `Dispatchers.IO` via `.flowOn(Dispatchers.IO)`. Each emitted `ChatStreamChunk` carries any combination of:

- `content: String` — a text delta.
- `reasoning: String?` — a reasoning/thinking delta.
- `toolCalls: List<ToolCallInfo>?` — tool calls the model requested this round.
- `toolResults: List<ToolResultInfo>?` — results after those tools ran.
- `contentReplace: String?` — replace the whole accumulated display text (used to strip tool-call markup).
- `error: String?`, `isDone: Boolean`, `usage: UsageInfo?` (prompt/completion token counts).

The orchestrator is constructed per request with `baseUrl`, `apiKey`, `model`, an optional `tools` list, an `onToolCall` suspend callback, `temperature` (default `0.7f`), and an optional `reasoningEffort` string.

## SSE token streaming

The request is a standard OpenAI-compatible `POST {baseUrl}/chat/completions` with `"stream": true`. Streaming is implemented with okhttp's SSE support: `EventSources.createFactory(client).newEventSource(request, listener)` with an `EventSourceListener`. Text deltas arrive in `onEvent(...)`, parsed from `choices[0].delta.content`; the terminal `data: [DONE]` sets a done flag.

The shared okhttp client (`SafeOkHttp.builder()`) is tuned specifically for long-lived streams on mobile networks. Verbatim from the source:

- `connectTimeout` 15s, `readTimeout` **3 minutes** (deliberately shorter than 5m so dead connections trigger a retry sooner), `writeTimeout` 30s, `callTimeout` 0 (unlimited).
- `retryOnConnectionFailure(true)`, forced `HTTP/1.1`.
- `ConnectionPool(0, 1, SECONDS)` — **no pooling**; a fresh connection is used for every request (comment: "cellular NAT kills idle").
- Each request also sends header `Connection: close` to force a fresh TCP connection.
- Custom `KeepAliveSocketFactory` and an `LlmEventListener` are attached.

Request headers include `Accept: text/event-stream` and, when `apiKey` is non-blank, `Authorization: Bearer <apiKey>`. The request body is built by `buildRequestBody`: it always sets `model`, `stream: true`, `temperature`, and `messages`; it adds `tools` only when the tool list is non-empty; and it adds `reasoning_effort` **only when `reasoningEffort` is non-null and not `"auto"`**.

Usage/token counts are read from a `usage` object when the provider includes one (typically in the final chunk) and forwarded on the closing `isDone` chunk as `UsageInfo(inputTokens, outputTokens)`.

## Reasoning support

AIOPE supports two distinct reasoning mechanisms and picks between them per stream.

### 1. Native structured reasoning (`reasoning_content` / `reasoning`)

For models that return reasoning as a dedicated delta field, the orchestrator reads it directly:

```
var reasoning = delta.optString("reasoning_content", "")...ifBlank {
  delta.optString("reasoning", "")...
}
```

So it accepts **both** `reasoning_content` and `reasoning` (the former taking priority). The string literal `"null"` is treated as empty. As soon as any structured reasoning is seen, the flag `hasStructuredReasoning` is set to `true`.

### 2. `<think>` tag parsing (for models that inline reasoning in content)

When `hasStructuredReasoning` is `false`, the orchestrator scans the content stream for inline thinking tags. It recognizes three opening tags — `<thinking>`, `<think>`, and `<thought>` — and tracks state with `inThinkTag` and `thinkTagName` (the matched tag name, so the correct closing tag `</name>` is expected). Text inside the tag is emitted as `reasoning`; text outside is emitted as `content`.

Because deltas can split a tag across chunks, a `pendingTagBuf` holds back trailing partial tags (it withholds emission when the buffer ends near a `<` that could be the start of an open/close tag) and flushes once the tag resolves. Any leftover buffer is appended back into the content stream when the round ends.

Honesty note: this tag handling is heuristic string matching, not a real parser. The "partial tag" guard uses fixed length windows (checks whether the last `<` is within ~12–13 characters of the buffer end). Unusual nesting, or tags longer than that window, may not be split perfectly. The three recognized tag names above are the only ones handled.

### Reasoning effort

`reasoningEffort` is passed through as the OpenAI-style `reasoning_effort` request field, but only when it is set and not `"auto"` (see above). AIOPE does not otherwise interpret or enforce a reasoning budget in this file.

## Collapsible thinking panels

In `ChatViewModel.collectStream(...)`, reasoning deltas are accumulated into a `currentReasoning` buffer. When the model transitions from reasoning to emitting content (or to a tool call, or the stream is done), the current buffer is pushed as a completed block into a `reasoningBlocks` list. The result is a **list** of reasoning segments per message, plus an `isReasoningDone` flag. UI updates are throttled (roughly per line — `charsPerLine = 55` — or on newlines, tool events, reasoning, or done).

`ReasoningTabStrip(reasoning: List<String>, isReasoningDone: Boolean)` renders these as a horizontally scrollable row of numbered tabs (one per block). Behavior verified in `ReasoningTabStrip.kt`:

- While streaming, the last tab is the "active" one and auto-selects; its number shimmers (`ShimmerText`).
- Tapping a tab expands its content in a scrollable panel capped at 120dp height; tapping again collapses it.
- While a block is still streaming and has more than 4 lines, only the last 4 lines are shown with a fade gradient at the top.
- The strip renders nothing when the reasoning list is empty.

Thinking panels are gated by a theme/user setting: in `MessageBubble.kt` the strip is only shown when `theme.showThinking` is true and the message has reasoning. Reasoning content itself is rendered through `UniversalMarkdown` with a dimmed theme.

## Tool activity rendering

`ToolTabStrip(calls, results, errors)` renders tool calls as tabs alongside the message (see `ToolTabStrip.kt` and `MessageBubble.kt`). In `collectStream`, each tool call becomes a label like `name(k=v, k2=v2)`, each result is stored (truncated to 2000 chars for display), and results starting with `Error:` or `FAILED` are recorded as errors. Per the source:

- A tab is "done" once a matching result exists; done tabs get a green status color (`0xFF4CAF50`), pending tabs shimmer with the primary color, and errored tabs show a `⚠` and use the error color.
- Tapping a tab expands a monospace view of the call, plus the result (or error) beneath it when available.

## The tool loop (agentic rounds)

`stream(...)` runs a bounded loop: `var maxRounds = 40` and the loop continues `while (maxRounds-- > 0)`. Each iteration sends one request, streams the response, and — if the model requested tools — executes them and loops again with the results appended. Details verified in source:

- **Tool call accumulation:** streamed `delta.tool_calls` fragments are accumulated by index (or by matching `id`, or the next free slot) into name + concatenated `arguments`. Google `extra_content` (thought_signature) is captured when present.
- **Parallel-safe execution:** when there is more than one tool call and **all** of them are in the `PARALLEL_SAFE` set, they run concurrently via `async(Dispatchers.IO)`; otherwise they run sequentially. `PARALLEL_SAFE` lists 25 read-only/idempotent tool names (e.g. `read_file`, `list_directory`, `search_web`, `fetch_url`, `rag_search`, `ssh_exec`, `image_generate`, `http_request`, …).
- **Follow-up requests:** after tools run, an `assistant` message with `tool_calls` plus one `tool` message per result are appended to the conversation and the loop continues.
- **Tool-result trimming:** older tool results are shrunk to control context. If more than 3 `tool` messages exist, every tool message except the **last 3** is truncated to 500 chars + `...(truncated)`. Additionally, each individual tool result is capped at `take(16000)` chars when appended to the request.
- **Multimodal flattening:** images are attached to the last user message on the first request; on follow-up requests any multimodal content arrays are flattened back to plain text.
- **Gemini sanitization:** assistant `tool_calls` lacking a Google `thought_signature` (and their orphaned `tool` results) are removed from history before re-sending, to satisfy Gemini's requirements.

### Text-based tool-call fallback

For models that emit tool calls as text rather than structured `tool_calls`, `parseTextToolCalls(...)` recognizes **8 formats**: MiniMax `<minimax:tool_call>` XML, Qwen/Hermes `<tool_call>{…}</tool_call>`, fenced ```json blocks, standalone JSON `{"name","arguments"}`, `[Tools: name → value]` inline brackets, Kimi-K2 `<|tool_call_begin|>…`, DeepSeek `DSML function_calls`, and Gemma `<|tool_call>call:…`. Only calls whose name matches a registered tool are accepted. Matching markup is stripped from the displayed text via `stripToolMarkup(...)` and sent as `contentReplace`. If the model outputs DSML `function_calls` tokens but no valid call parses, the orchestrator treats it as a hallucination loop and ends the turn instead of looping.

Honesty note: `maxRounds = 40` bounds the orchestrator's own tool loop. Interactive chat has a **separate** auto-continue cap in `ChatViewModel` (`autoRunRounds < 20`) and multi-agent stages have their own 5-minute per-stage timeout in `PipelineExecutor` — those are not enforced inside `StreamingOrchestrator`.

## Cancelable streams

Interactive streaming runs inside a coroutine tracked as `streamingJob` in `ChatViewModel`. `cancelStreaming()` calls `streamingJob?.cancel()`, nulls it, and clears the streaming flag. Because `stream(...)` is a `callbackFlow`, cancelling the collecting coroutine tears down the flow; `awaitClose` and `eventSource.cancel()` release the SSE connection.

Partial output is preserved on cancel. `collectStream` persists partial content to the DB (throttled, min ~1200ms between writes) and, in a `finally` block wrapped in `NonCancellable`, flushes the latest buffered content, reasoning, and tool state so an interrupted or process-killed response reappears on reload. The `send(...)` path catches `CancellationException` and keeps whatever was received.

Honesty note: cancellation is coroutine/Job-level, not a dedicated cancel method on `StreamingOrchestrator` — the class exposes no `cancel()`; you stop it by cancelling its collector.

## Retries

Within a single round, the SSE attempt is retried on transient failures. Verified constants and logic:

- `MAX_RETRIES = 3`.
- **Exponential backoff with jitter** (`backoffMs`): base `~1s, 2s, 4s` (`1000L shl (attempt-1)`), capped at 8000ms, plus 0–500ms random jitter. The delay uses a blocking `Thread.sleep` on the IO dispatcher.
- **Transient network errors** (`isTransientReset`): matches substrings like "connection reset", "stream was reset", "broken pipe", "socket closed", "timeout", "network is unreachable", "recvfrom failed", etc.
- **Retryable HTTP statuses** (`isRetryableHttp`): `429, 500, 502, 503, 504`. Everything else (e.g. 4xx auth/validation) is non-retryable and ends the stream with an error chunk.
- A latch guards each attempt with a 180s timeout; a timeout is treated as a transient failure.
- **Mid-stream recovery:** content received so far is tracked (`contentSoFar`, capped at ~2MB to avoid OOM), and on retry already-emitted content is not re-sent to the UI. An SSE reset that arrives *after* `[DONE]` is logged as non-fatal.

## Markdown rendering (UniversalMarkdown)

Assistant content is rendered with `UniversalMarkdown` from the external `com.fluid.compose` library (`MessageBubble.kt`), not a bespoke in-repo renderer. Verified usage:

- **Streaming animation:** `UniversalMarkdown(content = text, theme = mdTheme, animateStreaming = true, …)` — the content path passes `animateStreaming = true` so text animates in as it streams.
- **Code, tables, quotes, checkboxes, rules:** the theme built by `rememberMarkdownTheme(...)` configures colors for code text/background/border and a code label, inline code, block quotes, **tables** (header/body background, borders, text), horizontal rules, and checkboxes. Rendering of these element types is provided by the library; AIOPE supplies the theming.
- **LaTeX:** the renderer exposes an `onExportPdf = { latex -> LatexPdfExporter.export(ctx, latex) }` hook (LaTeX/math is exportable to PDF via `LatexPdfExporter`). Math rendering itself is handled by the `UniversalMarkdown` library.
- **Runnable code:** an `onRunCode` callback is wired so code blocks can be executed.
- **Images:** `onImageContent` renders Markdown image URLs inline via Coil `AsyncImage`, with long-press to save to gallery.
- **Embedded UI blocks:** content is split around ```aiope-ui fenced blocks (rendered by `AiopeUiRenderer`); while such a block is still streaming a "Building UI…" shimmer is shown and only the text before the block is rendered. See the Dynamic UI page.

## One engine, many callers

The same `StreamingOrchestrator` drives every model-facing path in AIOPE. Confirmed call sites:

- **Chat** — `ChatViewModel` (main send loop via `collectStream`, plus utility uses for translation and title generation).
- **Agents** — `engine/AgentExecutor.kt` builds an orchestrator (via a `createOrchestrator` factory) per agent run.
- **Multi-agent pipelines** — `engine/PipelineExecutor.kt` runs agents (each of which uses the orchestrator) as DAG stages.
- **Sub-agents** — `engine/SubagentManager.kt` (via a `createOrchestrator` factory).
- **Background scheduled runs** — `engine/AgentRunWorker.kt` constructs an orchestrator with the timer-limited tool set.
- **Vision utility** — `engine/ToolExecutor.kt` uses it for an image-analysis call.
- **Tool definitions** — MCP tools (`settings/McpManager.kt`) and builtin tools are supplied to the orchestrator as `StreamingOrchestrator.ToolDef`.

Realtime **voice** is the exception: it uses a separate WebSocket engine (`RealtimeStreaming.kt`), not this SSE orchestrator, though it reuses the `StreamingOrchestrator.ToolDef` type for its tool declarations. See the Realtime Voice page.
