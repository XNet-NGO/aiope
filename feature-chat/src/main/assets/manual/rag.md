# RAG Knowledge Base

keywords: rag, knowledge base, retrieval augmented generation, embeddings, vector store, sqlite, cosine similarity, semantic search, chunking, overlap, pdf, pdfbox, rag_search, rag_index, RagEngine, CloudEmbeddingEngine, gemini embedding, on-device, privacy

AIOPE keeps a private, **on-device knowledge base**. Documents are chunked, embedded once through a cloud embeddings endpoint, and the resulting vectors are stored locally in SQLite. At query time the search runs entirely on the phone using cosine similarity — nothing about the query or the stored text leaves the device except the short text sent to the embedding model to turn it into a vector.

This page is written from the actual source. Primary files:
- `core-inference/.../inference/RagEngine.kt` — SQLite vector store, chunking, cosine search
- `core-inference/.../inference/CloudEmbeddingEngine.kt` — OpenAI-compatible `/embeddings` client
- `feature-chat/.../engine/ToolExecutor.kt` — the `rag_search` and `rag_index` tools
- `feature-chat/.../settings/RagScreen.kt` — the RAG Documents UI (upload, PDFBox, search, re-index)

## On-device vector store (SQLite)

`RagEngine` opens a SQLite database named **`aiope_rag.db`** (version 1) via an inner `RagDatabase : SQLiteOpenHelper`. `onCreate` builds three tables plus one index:

- **`documents`** — `id TEXT PRIMARY KEY`, `title TEXT NOT NULL`, `source TEXT`, `chunk_count INTEGER`, `created_at DATETIME DEFAULT CURRENT_TIMESTAMP`.
- **`chunks`** — `id TEXT PRIMARY KEY`, `doc_id TEXT NOT NULL REFERENCES documents(id)`, `chunk_index INTEGER NOT NULL`, `text TEXT NOT NULL`.
- **`embeddings`** — `chunk_id TEXT PRIMARY KEY REFERENCES chunks(id)`, `embedding BLOB NOT NULL`, `dims INTEGER NOT NULL`.
- **`idx_chunks_doc`** — index on `chunks(doc_id)`.

`onUpgrade` drops all three tables and recreates them.

Embedding vectors are stored as raw `BLOB`s. `encodeFloats` serializes a `FloatArray` to little-endian bytes (4 bytes per float) and `decodeFloats` reverses it. The `dims` column records the vector length so a later model change can be detected.

## Indexing a document

`indexDocument(title, content, source = "")` returns a document id and does the following:

1. Generates a `docId` with `UUID.randomUUID()`.
2. Splits `content` into chunks via `chunk(content, chunkSize = 1024, overlap = 128)`.
3. Inserts one `documents` row (recording `chunk_count = chunks.size`).
4. For each chunk (index `i`): inserts a `chunks` row with id `"$docId-$i"`, calls `embedFn(text)`, and — only if the embedding is non-null — inserts an `embeddings` row with the encoded vector and its `dims`.

Related maintenance methods:
- `deleteDocument(docId)` — deletes the document's embeddings, chunks, and the document row.
- `deleteAllDocuments()` — clears all three tables.
- `reindexAll()` — clears the `embeddings` table and re-embeds every stored chunk with the current model (used after switching embedding models).
- `listDocuments()` — returns `List<DocumentInfo>` ordered by `created_at DESC`; `DocumentInfo(id, title, source, chunkCount, createdAt)`.

## Sentence-aware chunking with overlap

`chunk(text, chunkSize, overlap)` is sentence-aware rather than a blind character split:

- It first splits text into sentences with the regex `(?<=[.!?])\s+` (splits on whitespace that follows `.`, `!`, or `?`).
- It accumulates sentences into the current chunk, estimating length in rough tokens as `sentence.length / 4`.
- When adding a sentence would push the running length past `chunkSize` (default **1024**) and the current chunk is non-empty, it flushes the chunk, then seeds the next chunk with an **overlap** tail — `current.takeLast(overlap * 4)` characters (default `overlap` = **128**) — so context carries across chunk boundaries.
- Any remaining non-blank buffer is flushed as a final chunk.

Because splitting is done on sentence boundaries, chunks avoid cutting sentences in half, and the overlap keeps continuity between adjacent chunks.

## Cosine-similarity search (on device)

`search(query, topK = 5)` runs the retrieval locally:

1. Embeds the query with `embedFn(query)`; returns an empty list if embedding fails.
2. **Dimension guard** — reads one row's `dims` from `embeddings`. If the stored dimension does not match the current query embedding size, it logs a warning ("Embedding dimension mismatch … Re-index required."), **deletes all embeddings**, and returns empty. This prevents comparing vectors from different models.
3. Joins `embeddings` → `chunks` → `documents`, decodes each stored vector, and scores it against the query with `cosineSimilarity`.
4. Sorts results by score descending and returns the top `topK` as `SearchResult(chunkId, docId, text, score, title)`.

`cosineSimilarity(a, b)` computes the dot product divided by the product of the two L2 norms, returning `0f` when sizes differ, the array is empty, or the denominator is zero.

## PDF ingestion via PDFBox

Document upload lives in `RagScreen` (Settings → RAG Documents). The `+` action launches a file picker (`GetContent("*/*")`). On selection, off the main thread:

- If the MIME type is `application/pdf` **or** the name ends with `.pdf` (case-insensitive), the file bytes are read and text is extracted with **PDFBox for Android**: `PDFBoxResourceLoader.init(context)`, `PDDocument.load(bytes)`, then `PDFTextStripper().getText(doc)`.
- Otherwise the stream is read as plain UTF-8 text.
- If the extracted text is blank, the UI reports "File was empty or unreadable"; otherwise it calls `ragEngine.indexDocument(title = name, content = text, source = "upload")` and shows `"Indexed: $name (<n> chars)"`.

## RagScreen UI — complete element reference

`RagScreen(onBack)` is a single `@Composable` (`@OptIn(ExperimentalMaterial3Api::class)`). This section catalogs every element it declares, exactly as written in source.

### Screen-scoped state (`remember { mutableStateOf(...) }`)

1. `ragEngine: RagEngine?` — the lazily-initialized engine (starts `null`).
2. `documents: List<RagEngine.DocumentInfo>` — indexed documents list (starts empty).
3. `loading: Boolean` — engine still initializing (starts `true`).
4. `indexing: Boolean` — an upload or re-index is running (starts `false`).
5. `status: String` — the status-bar message (starts `""`).
6. `showDeleteAllDialog: Boolean` — controls the confirmation dialog (starts `false`).

### Content-scoped state (declared inside the body `Column`)

7. `searchQuery: String` — the search field text (starts `""`).
8. `searchResults: List<RagEngine.SearchResult>` — current search hits (starts empty).
9. `searching: Boolean` — a search is in flight (starts `false`).

### Ambient / derived values

- `theme = LocalThemeState.current`; `scaffoldColor` is `Color.Transparent` when `theme.useBackground` else `MaterialTheme.colorScheme.background`.
- `context = LocalContext.current`; `scope = rememberCoroutineScope()`.
- `refresh()` — local helper that reloads `documents` from `ragEngine.listDocuments()`.

### Initialization effect — `LaunchedEffect(Unit)`

Runs on `Dispatchers.IO`: reads `TaskModelStore.getTaskConfig(ModelTask.RAG)`, takes `tc.modelId ?: "google-ai-studio/models-gemini-embedding-2"`, constructs `CloudEmbeddingEngine(baseUrl = "https://inf.xnet.ngo/v1", apiKey = BuildConfig.GATEWAY_KEY, model = modelId)`, wraps it as `embedFn`, creates `RagEngine(context, embedFn)`, and loads `documents`. On failure it sets `status = "Error: <first 60 chars>"`. It always ends by setting `loading = false`.

### File picker — `fileLauncher`

`rememberLauncherForActivityResult(ActivityResultContracts.GetContent())`. On a non-null `uri`: sets `indexing = true`, `status = "Indexing..."`, then on IO derives `name` from `uri.lastPathSegment` (fallback `"uploaded_file"`), reads `contentResolver.getType(uri)`, and branches PDF vs. plain text (see PDFBox section). Outcomes: blank text → `status = "File was empty or unreadable"`; success → `indexDocument(title = name, content = text, source = "upload")` then `status = "Indexed: $name (<text.length> chars)"` and `refresh()`; exception → `status = "Error: <first 60 chars>"`. Finally sets `indexing = false`.

### Delete-All confirmation — `AlertDialog` (only when `showDeleteAllDialog`)

- `title` **Text**: "Delete All Documents".
- `text` **Text**: "This will remove all indexed documents and their embeddings. This cannot be undone."
- `confirmButton` **TextButton** → **Text** "Delete All" (colored `colorScheme.error`): dismisses the dialog, then on IO calls `deleteAllDocuments()`, `refresh()`, and `status = "All documents deleted"`.
- `dismissButton` **TextButton** → **Text** "Cancel": dismisses the dialog.
- `onDismissRequest`: dismisses the dialog.

### `Scaffold`

`containerColor = scaffoldColor`, `contentColor = colorScheme.onSurface`.

**`TopAppBar`:**
- `title` **Text**: "RAG Documents".
- `colors`: container is `Color.Transparent` when `theme.useBackground` else `colorScheme.surface`.
- `navigationIcon` — **IconButton** (`onClick = onBack`) with **Icon** `Icons.AutoMirrored.Filled.ArrowBack`, contentDescription "Back".
- `actions`:
  1. **IconButton** — **Icon** `Icons.Default.Add`, "Upload document"; `onClick = fileLauncher.launch("*/*")`; `enabled = !indexing && !loading`.
  2. The next two actions render **only when `documents.isNotEmpty()`**:
     - **IconButton** — **Icon** `Icons.Default.Refresh`, "Re-index all"; sets `indexing = true`, `status = "Re-indexing all documents..."`, runs `reindexAll()` on IO → success `status = "Re-indexed ${documents.size} documents"`, error `status = "Re-index error: <first 40 chars>"`; both paths reset `indexing = false`. `enabled = !indexing`.
     - **IconButton** — **Icon** `Icons.Default.Delete`, "Delete all"; `onClick` sets `showDeleteAllDialog = true`.

**Body** — a `Column` (`fillMaxSize().padding(pad)`) containing:

**Search bar `Row`** (`fillMaxWidth`, padding h16/v8, centered):
- **OutlinedTextField** — `value = searchQuery`, label **Text** "Search knowledge base", `Modifier.weight(1f)`, `singleLine = true`.
- **Spacer** width 8.dp.
- **IconButton** — **Icon** `Icons.Default.Search`, "Search"; on click (guarded by `searchQuery.isNotBlank() && ragEngine != null`) sets `searching = true`, runs `search(searchQuery, topK = 5)` on IO, then sets `searchResults`, `searching = false`, and `status = if (results.isEmpty()) "No results" else "${results.size} results"`. `enabled = !searching && searchQuery.isNotBlank()`.

**Then the body branches on `searchResults`:**

**Branch A — `searchResults.isNotEmpty()`:** a `LazyColumn` (`fillMaxWidth().weight(1f)`):
- header `item` — **Text** "Search Results" (`titleSmall`, padding h16/v4).
- `items(searchResults.size)` — one **Card** per result (`fillMaxWidth`, padding h16/v4, container `surfaceVariant` at alpha 0.5), inner `Column` (padding 12.dp) with:
  - **Text** `"[${"%.2f".format(r.score)}] ${r.title}"` (`labelMedium`, bold).
  - **Spacer** height 4.dp.
  - **SelectionContainer** wrapping **Text** `r.text` (`bodySmall`, `onSurfaceVariant`) — selectable body text.
- trailing `item` — **TextButton** "Clear results" (padding h16) → sets `searchResults = emptyList()`.

**Branch B — no search results (the `else`):**
- **Status bar** — only when `status.isNotBlank()`: a **Surface** (`surfaceVariant`, `fillMaxWidth`) wrapping **Text** `status` (`bodySmall`, padding h16/v8).
- Then a four-way state branch:
  - `loading` — centered **Box** → **Column** with **CircularProgressIndicator**, **Spacer** 8.dp, **Text** "Loading RAG engine..." (`bodySmall`).
  - else `indexing` — centered **Box** → **Column** with **CircularProgressIndicator**, **Spacer** 8.dp, **Text** "Indexing document..." (`bodySmall`).
  - else `documents.isEmpty()` — centered **Box** → **Column** with **Text** "No documents indexed" (`bodyLarge`), **Spacer** 8.dp, **Text** "Tap + to upload a text file for indexing" (`bodySmall`, `onSurfaceVariant`).
  - else — a `LazyColumn` (`fillMaxSize`) of `items(documents, key = { it.id })`, each a **ListItem** + **HorizontalDivider**:
    - `headlineContent` **Text**: `doc.title`.
    - `supportingContent` **Text**: `"${doc.chunkCount} chunks • ${doc.createdAt.take(10)}"` (`bodySmall`, `onSurfaceVariant`).
    - `trailingContent` **IconButton** — **Icon** `Icons.Default.Delete` (tint `colorScheme.error`), "Delete"; on IO calls `deleteDocument(doc.id)`, `refresh()`, `status = "Deleted: ${doc.title}"`.

### Icons used (5)

`Icons.AutoMirrored.Filled.ArrowBack` (back), `Icons.Default.Add` (upload), `Icons.Default.Refresh` (re-index all), `Icons.Default.Delete` (delete all + per-row delete), `Icons.Default.Search` (search).

### Every user-visible string

"RAG Documents", "Back", "Upload document", "Re-index all", "Delete all", "Search knowledge base", "Search", "Search Results", "Clear results", "Loading RAG engine...", "Indexing document...", "No documents indexed", "Tap + to upload a text file for indexing", "Delete", "Delete All Documents", "This will remove all indexed documents and their embeddings. This cannot be undone.", "Delete All", "Cancel"; and dynamic statuses: "Indexing...", "File was empty or unreadable", "Indexed: &lt;name&gt; (&lt;n&gt; chars)", "Error: …", "Re-indexing all documents...", "Re-indexed &lt;n&gt; documents", "Re-index error: …", "No results", "&lt;n&gt; results", "All documents deleted", "Deleted: &lt;title&gt;".

## Embeddings: OpenAI-compatible client

`CloudEmbeddingEngine(baseUrl, apiKey, model = "models/gemini-embedding-2")` talks to an **OpenAI-compatible `/embeddings` endpoint**:

- `embed(text)` — POSTs `{"model": <model>, "input": <text>}` to `"<baseUrl>/embeddings"` with `Content-Type: application/json` and, when `apiKey` is non-blank, `Authorization: Bearer <apiKey>`. Timeouts: 15s connect, 30s read. It parses `data[0].embedding` into a `FloatArray`, or returns `null` on any non-2xx response or exception.
- `embedBatch(texts)` — same endpoint with an array `input`; 60s read timeout; returns a per-input list of `FloatArray?`. (`RagEngine` currently embeds chunks one at a time through `embed`.)

### Default embedding model and wiring

`ToolExecutor.getRagEngine()` builds the engine lazily:
- It resolves the RAG task model via `resolveTaskModel(ModelTask.RAG)`; if the configured model id is blank it falls back to **`google-ai-studio/models-gemini-embedding-2`**.
- It constructs `CloudEmbeddingEngine(baseUrl = profile.effectiveApiBase(), apiKey = profile.apiKey, model = effectiveModel)` and wires `embedFn = { text -> cloudEmbed.embed(text) }` into a single `RagEngine(app, embedFn)`.

`RagScreen` mirrors this, defaulting the model to `google-ai-studio/models-gemini-embedding-2` and pointing `baseUrl` at the gateway `https://inf.xnet.ngo/v1`.

## The `rag_search` and `rag_index` tools

Both are registered in `ToolExecutor.buildToolDefs()` and dispatched in `execute()`:

- **`rag_search`** — "Search the local on-device knowledge base using semantic similarity. Returns relevant document chunks with scores." Params: `query` (required), `top_k` (integer, default 5). It calls `getRagEngine().search(query, topK)`; with no hits it returns "No results found in knowledge base.", otherwise each result is formatted as `[<score>] <title>\n<text>`.
- **`rag_index`** — "Index a document into the local knowledge base for semantic search." Params: `title` (required), `content` (required). It calls `getRagEngine().indexDocument(title, content)` and returns `"Indexed document '<title>' (id: <docId>)"`.

By agent mode: `rag_search` is part of the everyday Chat set, while `rag_index` is added in Plan mode (and, like all tools, available in Build). See the Tools System page for full mode gating.

## Privacy: only embedding requests leave the device

The knowledge base itself — the document text, chunks, and vectors in `aiope_rag.db` — stays entirely on the device, and cosine-similarity ranking runs locally in `RagEngine`. The **only** network traffic is the embedding request: the text of a chunk (during indexing) or the search query (during search) is sent to the OpenAI-compatible `/embeddings` endpoint to be converted into a vector. No stored documents, no scores, and no ranking data are transmitted. If the embedding call fails, indexing simply skips that chunk's vector and search returns no results — the app degrades gracefully rather than sending more data.
