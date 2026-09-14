# AIOPE Manual

The complete, code-grounded manual for AIOPE — one page per feature and configurable surface. Every page is written from the actual source (not marketing copy) and is the source material behind the in-app `introspect` capability, which lets the AIOPE agent answer questions about itself and the app.

These same pages ship inside the app as bundled assets (`feature-chat/src/main/assets/manual/`).

## Core experience

- [Personas & Operating Modes](personas-and-modes.md) — Chat / Plan / Build / Media, the built-in per-mode personas, and the "About You" context
- [Conversations](conversations.md) — auto-titles, edit/resend, retry, fork, compact, attachments, translation, export
- [Streaming & Reasoning](streaming-and-reasoning.md) — SSE token streaming, reasoning/`<think>` handling, the tool loop
- [Dynamic UI](dynamic-ui.md) — native interactive components rendered in chat

## Agent & tools

- [Tools System](tools.md) — the full tool registry and per-mode tool availability
- [Agent System](agent-system.md) — multi-agent spawn/monitor/timers/builder and the orchestrate pipeline
- [Realtime Voice](voice.md) — Gemini Live voice with a curated tool set

## Browsing, terminal & knowledge

- [Browser](browser.md) — the on-device shared WebView the user and AI both drive
- [Terminal](terminal.md) — the proot Alpine Linux environment
- [RAG Knowledge Base](rag.md) — on-device SQLite vector store over your documents
- [Introspect](introspect.md) — the self-knowledge tool: answers questions about AIOPE from its built-in manual
- [On-Device Vision](vision.md) — object detection and facial identity / presence, all on-device

## Connectivity

- [Remote Servers](remote-servers.md) — SSH management and remote browser driving (Firefox/Chrome)
- [Network Scanner](network-scanner.md) — LAN host/port discovery
- [File Server](file-server.md) — built-in HTTP/HTTPS file server

## Configuration

- [Settings](settings.md) — the settings hub, navigation, and import/export
- [Profiles](profiles.md) — provider/model profiles and every editor field
- [Providers](providers.md) — provider setup, categories, BYOK, and MCP
- [Model-Per-Task](model-per-task.md) — routing each task to a chosen model
- [MCP Servers](mcp-servers.md) — connecting external Model Context Protocol tool servers
- [Themes](themes.md) — the theming engine, custom colors, and image/video backgrounds
- [Authentication](authentication.md) — biometric, hardware key, and TOTP app-lock
