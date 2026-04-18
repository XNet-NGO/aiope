# AIOPE — Artificial Intelligence Operations

**AI-powered terminal and assistant for Android** — a chat interface with tool use, dynamic UI rendering, a full Linux terminal, live data, location awareness, and native markdown. Runs entirely on-device with any OpenAI-compatible API.

The AI doesn't just answer questions. It acts — and now it can build interactive interfaces on the fly.

## Features

### Dynamic UI (aiope-ui)
The AI can render rich, interactive native UI components directly in chat responses. Inspired by [Kai](https://github.com/nicholasgasior/kai)'s dynamic UI system.

- **30+ component types**: text, buttons, cards, tabs, accordions, tables, forms, alerts, badges, stats, code blocks, quotes, images, icons, progress bars, countdowns, avatars
- **Interactive forms**: text inputs, checkboxes, switches, sliders, radio groups, chip groups, select dropdowns — with `collectFrom` to gather and submit form data
- **Callback actions**: button presses send structured data back to the AI to continue multi-step workflows
- **Toggle actions**: show/hide elements, open URLs, copy to clipboard
- **Streaming shimmer**: shows "Building UI…" animation while the AI generates the UI block
- **Button pulse**: pressed buttons animate while waiting for the AI response
- **Text selection**: long-press to select and copy text from rendered UI
- **Dark theme**: cyan accents, dark cards, color-coded alerts, pill-style tabs
- **Toggleable**: Settings → Tools → Dynamic UI (off for legacy models)

### Tool Use (28 tools)
The AI autonomously calls tools in a loop — reasoning, executing, reading results, and continuing until done. Up to 100 tool rounds per turn.

| Tool | What it does |
|---|---|
| `run_sh` | Execute Android shell commands |
| `run_proot` | Full Alpine Linux environment (apt, python, gcc) |
| `read_file` / `write_file` | Read and write files |
| `list_directory` | List directory contents |
| `get_location` | GPS coordinates |
| `search_location` | Search places, addresses, businesses |
| `open_intent` | Open URLs, maps, navigation, dialer, email |
| `fetch_url` | Fetch and extract web content |
| `query_data` | Live data: weather, earthquakes, NASA, fires, UV, air quality |
| `search_web` / `search_images` | Web and image search |
| `browser_*` | Full browser automation (navigate, click, fill, eval, scroll) |
| `memory_store` / `memory_recall` / `memory_forget` | Persistent memory |
| `image_generate` / `analyze_image` | Image generation and analysis |

### In-App Browser
A controllable WebView that both user and AI share in real time. AI can navigate, click, fill forms, run JS, scroll, and read page content. Split view alongside chat or full screen.

### Embedded Linux Terminal
Full terminal emulator with proot-based Alpine environment. Install packages, run scripts, compile code — all on your phone.

### Native Markdown Rendering
Powered by [UniversalMarkdown](https://github.com/XNet-NGO/UniversalMarkdown):
- Syntax-highlighted code blocks with copy button
- GFM tables, LaTeX math (inline + block), block quotes
- Task lists, headings (H1–H6), horizontal rules
- Native text selection across all content

### Streaming & Reasoning
- Real-time SSE streaming with token-by-token display
- Reasoning/thinking block support (DeepSeek R1, OpenAI o-series, `<think>` tags)
- Collapsible thinking preview with shimmer animation

### Location & Live Data
- GPS location with interactive map cards (MapLibre)
- Nearby place search, turn-by-turn navigation
- Weather, forecasts, earthquakes, wildfires, NASA APOD, ISS position, solar flares, asteroid approaches, and more

### Multi-Provider Support
Works with any OpenAI-compatible API:

| Provider | Notes |
|---|---|
| Pollinations | Free, no API key |
| OpenAI | GPT-4o, o4-mini |
| Anthropic | Claude Sonnet 4, Claude 3.5 Haiku |
| Google AI Studio | Gemini 2.0 Flash (1M context) |
| DeepSeek | V3, R1 (reasoning) |
| OpenRouter | Free tier available |
| Groq | Llama 3.3 70B (fast) |
| Ollama | Local models |
| Custom | Any OpenAI-compatible endpoint |

### MCP Support
Connect external MCP (Model Context Protocol) servers to extend the AI's capabilities with additional tools. HTTP and SSE transports supported.

### Conversation Management
- Multiple conversations with auto-generated titles
- Edit & resend, retry from any point, fork conversations
- Auto-compact when approaching context limits
- Image/file attachments, speech-to-text, LaTeX PDF export

## Setup

1. Clone and open in Android Studio
2. Build and install (minSdk 26, targetSdk 34)
3. Settings → add a provider (Pollinations works with no API key)
4. For Linux terminal: Settings → install proot environment

## Requirements

- Android 8.0+ (API 26)
- Internet connection for LLM API calls
- GPS for location features (optional)

## Architecture

```
app/                          # Android app module
core-designsystem/            # Theme, colors, typography
core-network/                 # LLM provider, SSE streaming, API client
core-terminal/                # Terminal emulator, proot bootstrap
feature-chat/                 # Chat UI, ViewModel, tools, settings
  dynamicui/                  # aiope-ui parser, renderer, node types
  engine/                     # StreamingOrchestrator, tool execution loop
  browser/                    # WebBrowser, BrowserPanel, BrowserServer
  location/                   # GPS provider, map card, geocoding
  settings/                   # Provider config, model config, MCP
```

## License

AIOPE original code is licensed under the **Business Source License 1.1** (BSL 1.1).
Free to use, not to modify or distribute. Converts to Apache 2.0 on 2030-04-10.

© 2026 XNet Inc. — Joshua S. Doucette
Contact: joshuadoucette@xnet.ngo | pr@xnet.ngo

## Attributions & Third-Party Code

AIOPE builds on the following open-source projects, each under their original licenses:

| Component | Source | License |
|---|---|---|
| Dynamic UI system | Inspired by [nicholasgasior/kai](https://github.com/nicholasgasior/kai) | Apache 2.0 |
| App scaffold | [skydoves/chatgpt-android](https://github.com/skydoves/chatgpt-android) | Apache 2.0 |
| Markdown renderer | [XNet-NGO/UniversalMarkdown](https://github.com/XNet-NGO/UniversalMarkdown) | Apache 2.0 |
| Markdown base | [antgroup/FluidMarkdown](https://github.com/antgroup/FluidMarkdown) | Apache 2.0 |
| Markwon | [noties/markwon](https://github.com/noties/markwon) | Apache 2.0 |
| Terminal | [termux/termux-app](https://github.com/termux/termux-app) | GPL 3.0 |
| MapLibre | [maplibre/maplibre-native](https://github.com/maplibre/maplibre-native) | BSD 2-Clause |
| CommonMark | [commonmark/commonmark-java](https://github.com/commonmark/commonmark-java) | BSD 2-Clause |
| Prism4j | [noties/Prism4j](https://github.com/noties/Prism4j) | Apache 2.0 |
| JLatexMath | [opencollab/jlatexmath](https://github.com/opencollab/jlatexmath) | GPL 2.0+ |

The BSL 1.1 applies only to XNet's original code. All forked and third-party components retain their original licenses.

## Contributing

Contributions welcome. Please open an issue first to discuss changes. PRs should target the `main` branch.
