# Introspect (Self-Knowledge)

keywords: introspect, self knowledge, manual, help, what can you do, how does, documentation, aiope_manual.db, EmbeddingBackend, ensureManualIndexed, manual db

`introspect` lets the AIOPE agent answer questions **about AIOPE itself** — its features, tools, settings, and how to use the app — from a built-in manual that ships inside the app. It is wired in `ToolExecutor.kt` (the `introspect` tool), backed by `EmbeddingBackend` and a dedicated manual database.

## What it does

When the user asks "what can you do?", "how does X work?", or "where do I change Y?", the agent calls `introspect(query)`. The tool:

1. Runs a **semantic search** over the bundled manual to rank the most relevant page.
2. Returns that page's **complete markdown** (not a chunk excerpt) so the agent has full, coherent context.
3. Lists other related page names so the agent can call `introspect` again for a different page if needed.

The result is prefixed with an instruction to **answer only from the manual** and to say so if the manual doesn't cover the question — the agent should not invent undocumented behavior.

## Separate, isolated database

The manual lives in its **own** SQLite database, `aiope_manual.db`, completely separate from the user's RAG knowledge base (`aiope_rag.db`). This isolation means:

- `introspect` never returns the user's personal documents.
- `rag_search` never returns manual pages.

The store is built by the same `RagEngine` code as user RAG, just pointed at a different DB file (`RagEngine(..., dbName = "aiope_manual.db")`).

## Same embedding backend as RAG

The manual is embedded with the **same backend** the user selected for RAG (on-device Bekko ONNX when enabled and installed, otherwise cloud) via `EmbeddingBackend.embedFn` — with transparent cloud fallback. Because the two stores share a backend, toggling the embedding backend re-embeds **both**: `reindexWithCurrentBackend` reindexes the user store *and* the manual store so their vector dimensions stay consistent. See [RAG Knowledge Base](rag.md) for backend details.

## Indexed at startup, refreshed per version

`EmbeddingBackend.ensureManualIndexed` runs at app startup (`AiopeApp.onCreate`, off the main thread):

- It indexes the bundled manual pages (the `assets/manual` markdown files) into `aiope_manual.db`.
- It records the app version; on a version change it **re-indexes from scratch** (so renamed/removed pages don't linger).
- If already indexed for the current version and documents exist, it's a no-op.

## Availability

`introspect` is available in **Chat, Plan, and Build** modes (it's part of the default Chat tool set, which Plan and Build inherit/extend); Media mode exposes no tools. It can be toggled per mode in **Settings → Tools** like any other tool.

## Notes / limitations

- Answers are only as good and current as the bundled manual pages.
- On first launch the index builds in the background; a query made before indexing finishes returns a "still building — retry shortly" message.
- The tool returns one full page per call by design; broad questions may need a second `introspect` call for a related page.
