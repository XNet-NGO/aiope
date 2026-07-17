
<p align="center"><img src="logo.png" width="200" alt="AIOPE"></p>

# AIOPE

[![Android CI](https://github.com/XNet-NGO/AIOPE/actions/workflows/android.yml/badge.svg)](https://github.com/XNet-NGO/AIOPE/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/XNet-NGO/AIOPE)](https://github.com/XNet-NGO/AIOPE/releases/latest)
[![License: BSL 1.1](https://img.shields.io/badge/license-BSL%201.1-blue.svg)](LICENSE)
[![Built with Pollinations.ai](https://img.shields.io/badge/Built_with-Pollinations.ai-blue)](https://pollinations.ai)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)

**An AI that doesn't just talk. It acts.**

AIOPE is an Android AI assistant with 48 tools, realtime voice with full tool access, on-device RAG with local embeddings, a full Linux terminal, browser automation, location awareness, live data feeds, remote server management, and the ability to build native interactive UI on the fly. It connects to any OpenAI-compatible API and runs the entire tool loop on-device.

It ships with the [AIOPE Gateway](https://github.com/XNet-NGO/aiope-gateway) -- a self-hosted inference proxy that routes to Google AI Studio, Pollinations, and other providers with a single API key. The gateway handles model routing, rate limiting, and API key management so the client stays clean. Due to costs we might not provide free models. We will always support those that BYOK.

---

## What It Does

AIOPE operates in four modes:

- **Chat** -- conversational AI with full tool access
- **Voice** -- realtime bidirectional voice with full tool access (Google Gemini Live API)
- **Plan** -- read-only analysis mode; the AI explores context and produces a structured plan without executing anything
- **Build** -- autonomous execution mode; the AI chains tools without asking for confirmation until the task is complete

Plus a persistent **Agent System** -- spawn named agents, run multi-agent pipelines (orchestrate), and schedule recurring tasks with full tool access.

The AI runs a tool loop: reason, call a tool, read the result, decide what to do next. Up to 140 rounds per turn. It handles multi-step tasks -- research a topic, write code, save it to a file, run it in the terminal, fix errors, and report back -- all in one conversation turn.

**Auto-run**: a toggle next to the send button that keeps the AI working autonomously. When enabled, after any tool use the AI automatically continues without waiting for user input. Configurable continuation prompt in Agent settings. Max 20 auto-continue rounds per chain.

### Models Per Task

Different tasks route to different models automatically:

| Task | Default Model |
|---|---|
| Chat (primary) | Gemma 4 31B IT (256K context) |
| Realtime Voice | Gemini 3.1 Flash Live Preview |
| Subagent | Gemma 4 26B A4B (MoE, 4B active) |
| Summary | Llama 4 Scout (Pollinations) |
| Title generation | Nova Fast (Pollinations) |
| Translation | Nova Fast (Pollinations) |
| Image recognition | Gemma 4 26B A4B |
| Image generation | Flux (Pollinations) |

All configurable. Any model on any provider for any task.

---

## Tools (48)

### System
| Tool | Description |
|---|---|
| `run_sh` | Android shell commands |
| `run_proot` | Full Alpine Linux (apt, python, gcc, node) |
| `read_file` / `write_file` | File I/O |
| `list_directory` | Directory listing |
| `device_info` | Battery, storage, network, display |
| `clipboard_copy` / `clipboard_read` | Clipboard access |
| `media_control` | Play, pause, skip, volume |

### Communication
| Tool | Description |
|---|---|
| `read_sms` / `send_sms` / `delete_sms` | SMS access |
| `read_contacts` | Contact lookup |
| `send_notification` | Push notifications |
| `read_calendar` / `create_event` / `delete_event` | Calendar management |
| `set_alarm` / `dismiss_alarm` | Alarm control |
| `open_intent` | Open URLs, maps, navigation, dialer, email |

### Web and Search
| Tool | Description |
|---|---|
| `search_web` / `search_images` | Web and image search |
| `fetch_url` | Fetch and extract web content |
| `query_data` | Live feeds: weather, earthquakes, NASA APOD, wildfires, UV index, air quality, ISS, solar flares, asteroids |

### Browser Automation
| Tool | Description |
|---|---|
| `browser_navigate` / `browser_back` | Navigation |
| `browser_content` / `browser_elements` | Read page content and DOM |
| `browser_click` / `browser_fill` | Interact with elements |
| `browser_eval` | Execute JavaScript |
| `browser_scroll` | Scroll control |
| `browser_open` / `browser_close` / `browser_maximize` | Window management |

### Location
| Tool | Description |
|---|---|
| `get_location` | GPS coordinates |
| `search_location` | Places, addresses, businesses (Geoapify via gateway) |

### AI
| Tool | Description |
|---|---|
| `orchestrate` | Execute multi-agent DAG pipelines with parallel stages |
| `image_generate` | Text-to-image generation |
| `analyze_image` | Vision/image analysis |
| `memory_store` / `memory_recall` / `memory_forget` | Persistent cross-conversation memory |
| `rag_search` | Semantic search over locally indexed documents |
| `rag_index` | Index a document into the on-device knowledge base |

### Remote Servers (SSH)
| Tool | Description |
|---|---|
| `ssh_start` | Connect to a configured remote server |
| `ssh_exec` | Execute commands on a connected server |
| `ssh_exit` | Disconnect from a server |

---

## Dynamic UI

The AI can render native Android UI components directly in chat. Not images. Not web views. Real Compose components.

30+ component types: text, buttons, cards, tabs, accordions, tables, forms, alerts, badges, stats, code blocks, quotes, images, icons, progress bars, countdowns, avatars, inputs, checkboxes, switches, sliders, radio groups, chip groups, select dropdowns.

Forms collect data and submit it back to the AI. Buttons trigger callbacks that continue multi-step workflows. The AI builds the UI, the user interacts with it, and the AI responds to those interactions.

Toggleable per-profile for models that don't handle structured output well.

---

## Remote Servers

Manage and connect to remote Linux servers over SSH directly from the app. Add servers in Settings with host, port, user, and an Ed25519 private key. The AI sees available servers in its system prompt and can connect, run commands, and disconnect through tool calls.

Supports Ed25519 and RSA keys via SSHJ with BouncyCastle. The companion [aiope-remote daemon](daemon/) (Go) can be deployed to servers for health monitoring and managed execution.

---

## Agent System

A full multi-agent orchestration system accessible via the toolbar (SmartToy icon). Four tabs:

### Spawn
Pick an agent from the roster and assign a task. The agent runs in the background with its configured tools, model, and system prompt. Results appear in the Monitor tab.

### Monitor
Live dashboard showing all running and completed agent tasks (30 entry history). Tap any task to see:
- Full streaming output (markdown rendered)
- The original prompt
- Steer input to redirect a running agent
- Cancel (while running) or Rerun (after completion)

### Timers
Scheduled agent tasks with configurable tools. Set a prompt, select tools (search, fetch, shell, SMS, notification, alarm, SSH, memory), and choose a schedule (once, hourly, daily, weekly, monthly) with H:M:S time rollers. Runs via WorkManager in the background — even when the app is closed.

### Builder
Agent roster management. 8 builtin agents (Architect, Coder, Researcher, QA, DevOps, Security, Writer, Reviewer) plus custom agents. Full editor: name, system prompt, model picker, grouped tool selector, temperature, topP, topK, max context.

### Orchestrate Tool
The primary AI can call `orchestrate` to run multi-agent DAG pipelines:
- Define stages with agent name, prompt, and dependencies
- Stages without dependencies run in parallel (wavefront execution)
- Results from completed stages flow as context to dependent stages
- 5-minute timeout per stage, deadlock detection
- Agents from the roster get their configured tools and system prompts

Example: Researcher → Architect → Coder → QA (parallel with Reviewer)

---

## Realtime Voice

Tap the mic button to start a live voice conversation. AIOPE connects to Google's Gemini Live API via the gateway and streams bidirectional audio in real time.

- **Full tool access** -- all 48 tools work during voice, executed natively on-device
- **Acoustic echo cancellation** -- speak while the AI is talking to interrupt
- **Live transcription** -- both user and model speech rendered in chat as it happens
- **System prompt** -- your full agent persona and instructions apply to voice sessions
- **Speakerphone mode** -- auto-enables speaker and boosts volume during voice
- **Graceful hangup** -- tap mic again to end cleanly

The AI can browse the web, run shell commands, check your calendar, send messages, and perform any action -- all by voice command.

---

## Browser

A shared WebView that both the user and AI can control simultaneously. The AI navigates pages, reads content, clicks elements, fills forms, runs JavaScript, and scrolls -- all through tool calls. Split view alongside chat or full screen.

---

## On-Device RAG

A fully local Retrieval-Augmented Generation system running entirely on-device with no network required.

- **Embedding model**: MiniLM-L6-v2 Q4 (21MB) bundled as an APK asset
- **Inference**: llama.cpp compiled for ARM64 via NDK/CMake (JNI bridge)
- **Vector store**: SQLite with cosine similarity search
- **Chunking**: Sentence-aware with configurable overlap
- **PDF support**: Text extraction via PDFBox for uploaded documents

### How it works

1. Upload documents through **Settings > RAG Documents** (text files, PDFs)
2. Documents are chunked, embedded with MiniLM, and stored locally
3. The AI uses `rag_search` to find relevant chunks by semantic similarity
4. The AI uses `rag_index` to store new knowledge from conversations

All processing happens on the device CPU -- no data leaves the phone. The embedding model loads on first use and stays in memory for the session.

---

## Terminal

Full terminal emulator backed by a proot Alpine Linux environment. Install packages with `apk add`, run Python scripts, compile C code, use git -- on your phone. The AI uses it through `run_proot` for anything that needs a real shell.

---

## Markdown

Powered by [UniversalMarkdown](https://github.com/XNet-NGO/UniversalMarkdown), a custom Compose renderer built on commonmark-java and Markwon:

- Syntax-highlighted code blocks with copy button
- GFM tables, task lists, strikethrough
- LaTeX math (inline and block) with PDF export
- Block quotes, headings, horizontal rules
- Native text selection across all rendered content
- Streaming animation during token-by-token display

---

## Themes

Four modes: Dark, Light, System (Material You dynamic colors from Android 12+), and Custom.

Custom mode exposes: accent color, UI surface color, primary/secondary text colors, user/AI bubble colors with opacity, background image or video with opacity. Every surface in the app respects the theme -- toolbars, pills, bubbles, tool panels, reasoning blocks.

WCAG 2.1 Level AA contrast targets in both light and dark modes.

---

## Streaming and Reasoning

Real-time SSE streaming with token-by-token display. Supports reasoning/thinking blocks from DeepSeek R1, OpenAI o-series, and any model that uses `<think>` tags. Thinking content renders in collapsible panels with shimmer animation during streaming and a fade mask for partial display.

---

## Providers

Works with any OpenAI-compatible API. Ships pre-configured for the AIOPE Gateway, which proxies to:

- Google AI Studio (Gemma 3, Gemma 4, Gemma 3n)
- Pollinations (Klein image generation, free inference)
- Any additional backend you configure

Also supports direct connections to OpenAI, Anthropic, DeepSeek, OpenRouter, Groq, Ollama, and any custom endpoint.

MCP (Model Context Protocol) support for extending the AI with external tool servers. HTTP and SSE transports.

---

## Conversations

- Multiple conversations with auto-generated titles
- Edit and resend from any point in the conversation
- Retry, fork, and compact conversations
- Auto-compact when approaching context window limits
- Image and file attachments (images, PDFs, text files)
- Speech-to-text input
- Text-to-speech output
- Inline translation to 12 languages
- Share conversations as text

---

## Setup

1. Clone and build with Android Studio (or `./gradlew :app:assembleRelease`)
2. Install on any Android 8.0+ device
3. The AIOPE Gateway is pre-configured -- works out of the box
4. For the Linux terminal: Settings > install proot environment

Or download the latest APK from [Releases](https://github.com/XNet-NGO/AIOPE/releases).

### Requirements

- Android 8.0+ (API 26)
- ARM64 device (for on-device RAG inference)
- Internet connection for API calls
- GPS for location features (optional)
- ~100MB for proot Linux environment (optional)
- ~21MB additional for MiniLM embedding model (bundled in APK)

---

## Architecture

```
app/                          Main Android module (ngo.xnet.aiope)
core-designsystem/            Theme, colors, typography
core-network/                 LLM provider, SSE streaming, task model routing
core-model/                   Shared interfaces (RemoteToolBridge)
core-preferences/             DataStore preferences
core-data/                    Data layer
core-terminal/                Terminal emulator, proot bootstrap
core-inference/               On-device llama.cpp (NDK/JNI), LlamaEngine, RagEngine
daemon/                       Go daemon for remote servers (aiope-remote)
feature-chat/
  engine/                     StreamingOrchestrator, ToolExecutor, AgentExecutor, PipelineExecutor, AgentSchedulerWorker, AgentMode, RealtimeStreaming
  dynamicui/                  aiope-ui parser, renderer, 30+ node types
  browser/                    WebBrowser, BrowserPanel, BrowserServer
  location/                   GPS provider, map cards, geocoding
  settings/                   Provider config, model-per-task, MCP, themes, RAG documents
  theme/                      ThemeProvider, ThemeState, ChatBackground
  db/                         Room DB (conversations, messages, agents, tasks, schedules, memories)
feature-remote/
  ssh/                        SshSessionManager, DeployUseCase
  tools/                      RemoteToolProvider (ssh_start, ssh_exec, ssh_exit)
  ui/                         ServerListScreen, ServerListViewModel
  db/                         RemoteDatabase (Room)
```

---

## Built By

AIOPE was built by one developer and an AI pair in under a month. No team. No funding. No office. Just a server in a house and a terminal.

~1000 commits. 46 tools. 10 languages across 26 repositories. The entire XNet software stack -- from low-level ZeroTier networking forks and TCP/IP stacks to MCP servers, a self-hosted LLM gateway, a custom markdown renderer, and the most feature-complete AI agent app on Android -- is maintained by the same person.

The developer is disabled. AI-assisted development is the accessibility tool that closed the gap between vision and execution. AIOPE exists because the same paradigm it demonstrates -- a human directing an AI to build at a pace that shouldn't be possible -- is the paradigm that built it.

No other Android app ships a Linux terminal, browser automation, SSH remote management, 48 tools with a 140-round autonomous loop, on-device RAG with local embeddings, dynamic native UI generation, provider-agnostic model routing, and MCP support in a single package. The apps that come closest are backed by teams of hundreds.

This one was built by two.

---

## License

AIOPE original code is licensed under the **Business Source License 1.1** (BSL 1.1).
Free to use, study, and self-host. Not to modify or redistribute. Converts to Apache 2.0 on 2030-04-10.

Copyright 2026 XNet Inc. -- Joshua S. Doucette
Contact: joshuadoucette@xnet.ngo | pr@xnet.ngo

---

## Attributions

AIOPE builds on the following open-source projects, each under their original licenses:

| Component | Source | License |
|---|---|---|
| Dynamic UI | Inspired by [nicholasgasior/kai](https://github.com/nicholasgasior/kai) | Apache 2.0 |
| App scaffold | [skydoves/chatgpt-android](https://github.com/skydoves/chatgpt-android) | Apache 2.0 |
| Markdown | [XNet-NGO/UniversalMarkdown](https://github.com/XNet-NGO/UniversalMarkdown) | BSL 1.1 |
| Markdown base | [antgroup/FluidMarkdown](https://github.com/antgroup/FluidMarkdown) | Apache 2.0 |
| Markwon | [noties/markwon](https://github.com/noties/markwon) | Apache 2.0 |
| Terminal | [termux/termux-app](https://github.com/termux/termux-app) | GPL 3.0 |
| llama.cpp | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT |
| PDFBox Android | [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Apache 2.0 |
| MapLibre | [maplibre/maplibre-native](https://github.com/maplibre/maplibre-native) | BSD 2-Clause |
| CommonMark | [commonmark/commonmark-java](https://github.com/commonmark/commonmark-java) | BSD 2-Clause |
| Prism4j | [noties/Prism4j](https://github.com/noties/Prism4j) | Apache 2.0 |
| JLatexMath | [opencollab/jlatexmath](https://github.com/opencollab/jlatexmath) | GPL 2.0+ |

The BSL 1.1 applies only to XNet's original code. All third-party components retain their original licenses.

---

## Powered By

- [Pollinations.ai](https://pollinations.ai) — Open-source generative AI platform

## Contributing

Contributions welcome. Open an issue first to discuss. PRs target `main`.
