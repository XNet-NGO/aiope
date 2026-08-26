# AIOPE2 Agent Harness: Architectural Analysis & Roadmap

## 1. The Orchestration Engine
- **Reasoning Loop**: The harness uses a `while` loop in `StreamingOrchestrator` allowing multiple tool calls per turn.
- **Parallel Execution**: Tools in the `PARALLEL_SAFE` set are executed concurrently using Kotlin coroutines.
- **Proposal**: Expand the safe set to include read-only device APIs (e.g., `read_calendar`, `read_contacts`).
- **Proposal**: Implement semantic context trimming instead of the current "drop-last-3" approach to preserve critical information.

## 2. Visual & Interactive Pipeline
- **Aiope-UI**: JSON-based dynamic UI elements rendered as native components.
- **Custom Markdown**: A pipeline extending Markwon with LaTeX, structured tables, and smart-link resolution.
- **Proposal**: Integrate executable code blocks directly into the markdown renderer, linking to the `TerminalRenderer`.
- **Proposal**: Implement interactive data-tables with sorting and filtering.

## 3. Terminal & Session State
- **TerminalRenderer**: High-performance canvas-based emulator with JetBrains Mono support.
- **TerminalSessionHolder**: Manages persistent `TerminalSession` objects, allowing shells to survive across user turns.
- **Proposal**: Build a bridge between `StreamingOrchestrator` and `TerminalSessionHolder` to allow the agent to inject commands into and monitor active user shells.

## 4. The PRoot Local-Loop
- **Native Integration**: The client bundles `libproot.so` and `libproot-xed.so`.
- **ProotExecutor**: A native bridge allowing the app to run virtualized Linux rootfs locally on the device.
- **Proposal**: Implement a `run_local_linux` tool to shift heavy computation and system tooling from remote servers to the local device hardware.

## 5. The Unified Tool Matrix
- **Broad Capability**: 40+ tools across Shell/PRoot, File System, Device Integration, Web Intelligence, Personal Assistant, and Live Data (`query_data`).
- **MCP Support**: Integration with `McpManager` allows for the dynamic discovery and use of external Model Context Protocol servers.
- **Proposal**: Design "Complex Workflows" where the agent chains disparate tools (e.g., Weather -> Calendar -> Location -> Event -> SMS) in one autonomous turn.
