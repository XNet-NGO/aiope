# AIOPE

**An AI that doesn't just talk. It acts.**

AIOPE is an Android AI assistant with 46 tools, a full Linux terminal, browser automation, location awareness, live data feeds, remote server management, and the ability to build native interactive UI on the fly. It connects to any OpenAI-compatible API and runs the entire tool loop on-device.

It ships with the [AIOPE Gateway](https://github.com/XNet-NGO/aiope-gateway) -- a self-hosted inference proxy that routes to Google AI Studio, Pollinations, and other providers with a single API key. The gateway handles model routing, rate limiting, and API key management so the client stays clean.

---

## What It Does

AIOPE operates in three modes:

- **Chat** -- conversational AI with full tool access
- **Plan** -- read-only analysis mode; the AI explores context and produces a structured plan without executing anything
- **Build** -- autonomous execution mode; the AI chains tools without asking for confirmation until the task is complete

The AI runs a tool loop: reason, call a tool, read the result, decide what to do next. Up to 140 rounds per turn. It handles multi-step tasks -- research a topic, write code, save it to a file, run it in the terminal, fix errors, and report back -- all in one conversation turn.

**Auto-run**: a toggle next to the send button that keeps the AI working autonomously. When enabled, after any tool use the AI automatically continues without waiting for user input. Configurable continuation prompt in Agent settings. Max 20 auto-continue rounds per chain.

### Models Per Task

Different tasks route to different models automatically:

| Task | Default Model |
|---|---|
| Chat (primary) | Gemma 4 31B IT (256K context) |
| Subagent | Gemma 4 26B A4B (MoE, 4B active) |
| Summary | Gemma 3 27B IT |
| Title generation | Gemma 3 1B IT |
| Translation | Gemma 3 12B IT |
| Image recognition | Gemma 3 27B IT |
| Image generation | Klein (Pollinations) |

All configurable. Any model on any provider for any task.

---

## Tools (46)

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
| `image_generate` | Text-to-image generation |
| `analyze_image` | Vision/image analysis |
| `task` | Spawn a subagent for parallel work |
| `memory_store` / `memory_recall` / `memory_forget` | Persistent cross-conversation memory |

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

## Browser

A shared WebView that both the user and AI can control simultaneously. The AI navigates pages, reads content, clicks elements, fills forms, runs JavaScript, and scrolls -- all through tool calls. Split view alongside chat or full screen.

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
- Internet connection for API calls
- GPS for location features (optional)
- ~100MB for proot Linux environment (optional)

---

## Architecture

```
app/                          Main Android module
core-designsystem/            Theme, colors, typography
core-network/                 LLM provider, SSE streaming, task model routing
core-model/                   Shared interfaces (RemoteToolBridge)
core-preferences/             DataStore preferences
core-data/                    Data layer
core-terminal/                Terminal emulator, proot bootstrap
daemon/                       Go daemon for remote servers (aiope-remote)
feature-chat/
  engine/                     StreamingOrchestrator, ToolExecutor, AgentMode
  dynamicui/                  aiope-ui parser, renderer, 30+ node types
  browser/                    WebBrowser, BrowserPanel, BrowserServer
  location/                   GPS provider, map cards, geocoding
  settings/                   Provider config, model-per-task, MCP, themes
  theme/                      ThemeProvider, ThemeState, ChatBackground
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

No other Android app ships a Linux terminal, browser automation, SSH remote management, 46 tools with a 140-round autonomous loop, dynamic native UI generation, provider-agnostic model routing, and MCP support in a single package. The apps that come closest are backed by teams of hundreds.

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
| MapLibre | [maplibre/maplibre-native](https://github.com/maplibre/maplibre-native) | BSD 2-Clause |
| CommonMark | [commonmark/commonmark-java](https://github.com/commonmark/commonmark-java) | BSD 2-Clause |
| Prism4j | [noties/Prism4j](https://github.com/noties/Prism4j) | Apache 2.0 |
| JLatexMath | [opencollab/jlatexmath](https://github.com/opencollab/jlatexmath) | GPL 2.0+ |

The BSL 1.1 applies only to XNet's original code. All third-party components retain their original licenses.

---

## Contributing

Contributions welcome. Open an issue first to discuss. PRs target `main`.
