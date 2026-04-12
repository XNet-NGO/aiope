# AIOPE — Artificial Intelligence Operations

**AI-powered terminal and assistant for Android** — a chat interface with tool use, a full Linux terminal, live data, location awareness, and native markdown rendering. Runs entirely on-device with any OpenAI-compatible API.

## What it does

AIOPE gives an LLM direct access to your Android device. It can run shell commands, manage files, fetch web pages, query live data (weather, earthquakes, space events), find nearby places, open apps and URLs, and render its responses with full markdown — code blocks, tables, LaTeX, and more.

The AI doesn't just answer questions. It acts.

## Features

### Tool Use (19 tools)
The AI can autonomously call tools in a loop — reasoning, executing, reading results, and continuing until the task is done. Up to 100 tool rounds per conversation turn.

| Tool | What it does |
|---|---|
| `run_sh` | Execute Android shell commands |
| `run_proot` | Run commands in a full Ubuntu proot environment (apt, python, gcc, etc.) |
| `read_file` | Read any file on the device |
| `write_file` | Create or overwrite files |
| `list_directory` | List directory contents |
| `get_location` | Get current GPS coordinates |
| `search_location` | Search for places, addresses, businesses |
| `open_intent` | Open URLs, maps, navigation, phone dialer, email |
| `fetch_url` | Fetch and extract content from any URL |
| `query_data` | Live data: weather, air quality, UV, earthquakes, NASA APOD, ISS position, solar flares, fires, and more |
| `search_web` | Web search via SearXNG |
| `browser_navigate` | Open a URL in the in-app browser |
| `browser_content` | Get page text, title, and URL from the browser |
| `browser_elements` | List interactive elements with CSS selectors |
| `browser_click` | Click an element by CSS selector |
| `browser_fill` | Fill an input field by CSS selector |
| `browser_eval` | Execute JavaScript in the browser |
| `browser_scroll` | Scroll the browser page up/down |
| `browser_back` | Go back in browser history |
| `browser_open` | Open the browser panel |
| `browser_close` | Close the browser panel |
| `browser_maximize` | Maximize/restore the browser panel |

### In-App Browser
A controllable WebView browser that both the user and AI share in real time:
- **Navigation bar** with back, forward, refresh, editable URL bar, and maximize button
- **AI-controlled** — the agent can navigate, click, fill forms, run JS, scroll, and read page content
- **Localhost API** — HTTP server on port 8735 exposes browser control for external tools
- **Split view** — browser panel sits alongside chat, or maximizes to full screen
- **Loading indicator** — cyan progress bar during page loads
- **Scroll position preserved** across maximize/restore cycles

### Embedded Linux Terminal
A full terminal emulator with proot-based Ubuntu environment. Install packages with `apt`, run Python scripts, compile C code — all on your phone. The terminal panel sits alongside the chat in split view.

### Native Markdown Rendering
Powered by [UniversalMarkdown](https://github.com/XNet-NGO/UniversalMarkdown) — a native Compose renderer built on CommonMark + GFM:
- Syntax-highlighted code blocks with copy button
- GFM tables with header styling
- LaTeX math (inline and block)
- Block quotes, task lists, horizontal rules
- Headings (H1–H6) with proper typography
- Inline code, bold, italic, strikethrough, links
- Native text selection across all content

### Streaming & Reasoning
- Real-time SSE streaming with token-by-token display
- Reasoning/thinking block support (DeepSeek R1, OpenAI o-series, `<think>` tags)
- Thinking streams with last-3-lines preview, auto-collapses on completion, toggleable
- Shimmer animation on "Thinking…" label during reasoning

### Location Awareness
- GPS-based location with interactive map cards (Ramani/MapLibre)
- Nearby place search ("closest coffee shop")
- Turn-by-turn navigation via intent

### Live Data Queries
Weather, hourly forecasts, severe alerts, air quality, UV index, sunrise/sunset, earthquakes, significant quakes, wildfires, asteroid close approaches, solar flares, coronal mass ejections, geomagnetic storms, ISS position, astronauts in space, NASA Astronomy Picture of the Day, NASA media search, NASA tech patents, EPIC daily Earth photos, impact risk assessments, and more.

### Multi-Provider Support
Works with any OpenAI-compatible API. Built-in templates for:

| Provider | Notes |
|---|---|
| Pollinations | Free, no API key required |
| AIOPE Gateway | Custom gateway |
| OpenAI | GPT-4o, o4-mini |
| Anthropic | Claude Sonnet 4, Claude 3.5 Haiku |
| Google AI Studio | Gemini 2.0 Flash (1M context) |
| DeepSeek | V3, R1 (reasoning) |
| OpenRouter | Free tier models available |
| GitHub Models | GPT-4o, DeepSeek R1 |
| Groq | Llama 3.3 70B (fast inference) |
| Ollama | Local models |
| Custom | Any OpenAI-compatible endpoint |

Model fetching from provider APIs. Per-model configuration for context window, temperature, reasoning effort, vision, and tool use.

### Conversation Management
- Multiple conversations with auto-generated titles
- Edit & resend user messages
- Retry from any point (deletes subsequent messages)
- Fork conversations
- Auto-compact when approaching context limits
- Image and file attachments (camera, gallery, file picker)
- Speech-to-text input
- LaTeX document export to PDF

## Architecture

```
app/                          # Android app module
core-designsystem/            # Theme, colors, typography
core-network/                 # LLM provider, SSE streaming, API client
core-terminal/                # Terminal emulator, proot bootstrap, shell
feature-chat/                 # Chat UI, ViewModel, tools, settings
  engine/                     # StreamingOrchestrator, tool execution loop
  browser/                    # WebBrowser, BrowserPanel, BrowserServer
  location/                   # GPS provider, map card, geocoding
  settings/                   # Provider config, model config, proot setup
```

**Key components:**
- `StreamingOrchestrator` — SSE parser with parallel tool call accumulation, reasoning extraction (`<think>`/`<thought>` tags + `reasoning_content` field), and automatic tool loop (up to 100 rounds)
- `WebBrowser` — controllable WebView with navigate, click, fill, eval, scroll, and content extraction
- `BrowserServer` — localhost HTTP server (port 8735) exposing browser control endpoints
- `ChatViewModel` — conversation state, message persistence (Room), tool execution, auto-compact
- `UniversalMarkdown` — native Compose markdown renderer (AnnotatedString-based, no WebView)
- `TerminalSession` — proot-backed terminal with PTY, keyboard handling, and shell discovery

## UI Design

Dark green background (`#132E1F`) with black containers (`#0A0A0A`) for high contrast. Inspired by [Kelivo](https://github.com/Yubico/kelivo)'s chat design patterns:

- User bubbles: right-aligned, 75% width, `primary.alpha(0.15)`, borderRadius 16
- Assistant messages: full-width, no background
- Text: 15.5sp with 1.5 line height
- Code blocks: black background, syntax highlighting, copy button
- Reasoning: collapsible with shimmer animation, last-3-lines streaming preview
- Tool calls: card-style with expandable results
- Action row: copy, retry, menu below each assistant message

## Setup

1. Clone and open in Android Studio
2. Build and install on device (minSdk 26, targetSdk 34)
3. Open Settings → add a provider (Pollinations works out of the box with no API key)
4. For the Linux terminal: Settings → install proot environment

## Requirements

- Android 8.0+ (API 26)
- Internet connection for LLM API calls
- GPS for location features (optional)
- Camera for photo input (optional)

## License

AIOPE original code is licensed under the **Business Source License 1.1** (BSL 1.1).
Free to use, not to modify or distribute. Converts to Apache 2.0 on 2030-04-10.

(c) 2026 XNet Inc. — Joshua S. Doucette
Contact: joshuadoucette@xnet.ngo | pr@xnet.ngo

### Forked & Third-Party Code

AIOPE builds on the following open-source projects, each under their original licenses:

| Component | Source | License |
|---|---|---|
| App scaffold | [skydoves/chatgpt-android](https://github.com/skydoves/chatgpt-android) | Apache 2.0 |
| Markdown base | [antgroup/FluidMarkdown](https://github.com/antgroup/FluidMarkdown) | Apache 2.0 |
| Markwon | [noties/markwon](https://github.com/noties/markwon) | Apache 2.0 |
| Terminal | [termux/termux-app](https://github.com/termux/termux-app) | GPL 3.0 |
| MapLibre | [maplibre/maplibre-native](https://github.com/maplibre/maplibre-native) | BSD 2-Clause |
| CommonMark | [commonmark/commonmark-java](https://github.com/commonmark/commonmark-java) | BSD 2-Clause |
| Prism4j | [noties/Prism4j](https://github.com/noties/Prism4j) | Apache 2.0 |
| JLatexMath | [opencollab/jlatexmath](https://github.com/opencollab/jlatexmath) | GPL 2.0+ |

The BSL 1.1 applies only to XNet's original code. All forked and third-party components retain their original licenses.
