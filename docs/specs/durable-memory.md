# AIOPE Durable Memory Spec

Status: Draft
Owner: XNet
Related: issue #137 (declined external MemCode integration in favor of in-house on-device memory)

## 1. Goal

Give the AIOPE agent durable, on-device memory so it stays useful across restarts,
sessions, and channels (chat, scheduled tasks, voice). Memory must remain fully
on-device by default, honor BYOK/privacy guarantees, and never cross a third-party
trust boundary.

Non-goals (v1): cloud sync, cross-device replication, multi-user servers, any
external SaaS memory backend.

## 2. What already exists (do not rebuild)

Verified in the current `main` branch:

| Component | Location | Purpose |
|-----------|----------|---------|
| `memories` table + `MemoryEntity(key, content, category, createdAt, updatedAt)` | `feature-chat/.../db/ChatDatabase.kt` | Durable KV memory store |
| DAO: `upsertMemory`, `getAllMemories`, `searchMemories` (LIKE), `deleteMemory` | `ChatDatabase.kt` | CRUD over memories |
| Agent tools `memory_store` / `memory_recall` / `memory_forget` | `engine/ToolExecutor.kt`, `engine/AgentRunWorker.kt` | Model-initiated memory ops |
| Tool toggles for the three memory tools | `settings/ToolToggleScreen.kt` | User can enable/disable |
| Memory backup/restore | `settings/SettingsPorter.kt` | Export/import in profile backup |
| `RagEngine.indexDocument()` / `search(query, topK)` over SQLite embeddings | `core-inference/.../RagEngine.kt` | On-device semantic retrieval (local or cloud embed fn) |
| System-prompt assembly point | `AgentRunWorker.kt:154`, chat engine equivalents | Where memory context would be injected |

## 3. Gaps this spec closes

1. **Recall is model-initiated only.** The agent must explicitly call `memory_recall`;
   there is no automatic scoped injection of relevant memories before a run.
2. **Keyword-only matching.** `searchMemories` is a substring `LIKE` query. The
   semantic `RagEngine` and the `memories` KV store are two separate silos.
3. **No write-back policy.** The agent can store arbitrary memories at any time;
   nothing enforces "save only confirmed preferences/decisions."
4. **No lifecycle.** No TTL, no scoping (global vs conversation vs device), no
   dedup/merge, no size caps.

## 4. Design

### 4.1 Memory model

Extend `MemoryEntity` (additive, migration required):

```
MemoryEntity(
  key: String            // stable identifier, PK
  content: String        // the remembered fact/preference/decision
  category: String       // "preference" | "instruction" | "workflow" | "outcome" | "fact" | "task_run" | "general"
  scope: String          // "global" | "conversation:<id>" | "device:<id>"   (chat memories may be either global or conversation-scoped; default global)
  confidence: Float      // 0..1, how strongly to trust/inject (default 1.0 for confirmed)
  source: String         // "user_confirmed" | "agent_inferred" | "imported"
  pinned: Boolean        // never auto-evicted (default false)
  expiresAt: Long?       // optional TTL; null = no expiry
  createdAt / updatedAt / lastUsedAt: Long
)
```

Semantic index: reuse `RagEngine`. On `upsertMemory`, also index the memory text
into a dedicated RAG collection/source tag (`source = "memory:<key>"`) so retrieval
can be semantic, not just LIKE. Deletion mirrors into `RagEngine.deleteDocument`.

**Scope of chat-originated memories (DECIDED):** chat memories may be **either global
or conversation-scoped** — this is a per-memory property, not a single forced default.
- Default is `global` for durable user traits (preferences, standing instructions) so
  they carry across threads and channels — the cross-session usefulness #137 asked for.
- Thread-specific facts/decisions are stored as `conversation:<id>` so they don't leak
  into unrelated conversations.
- The write path chooses scope from the memory's nature: preferences/instructions ->
  global; conversation-local facts/decisions -> conversation-scoped. The user can
  re-scope any memory (promote conversation -> global, or narrow global -> conversation)
  from the management UI.
- Retrieval always includes matching `global` memories plus memories scoped to the
  current `conversation:<id>`.

**Scheduled-task memories (DECIDED):** memories produced by scheduled/background runs
(`AgentRunWorker`) use the dedicated `category = "task_run"` and get a **short default
TTL** (see 4.4) so recurring-task outcomes don't accumulate and pollute retrieval.
They are scoped to the task (`scope = "conversation:<taskId>"` semantics) and are
excluded from global chat retrieval unless pinned.

### 4.2 Retrieval (automatic scoped injection)

Before an agent/tool run, assemble a bounded "Relevant Memory" block and inject it
into the system prompt at the existing assembly point (`AgentRunWorker.kt:154` and
the chat engine equivalent).

Algorithm:
1. Build a retrieval query from the user prompt + active tool set + scope
   (current conversation/device).
2. Candidate set = pinned memories (always) UNION semantic `RagEngine.search(query, topK)`
   over the memory collection UNION keyword `searchMemories` fallback.
3. Filter by scope match and `confidence >= minConfidence` (configurable, default 0.4).
4. Rank by `score = w1*semanticSim + w2*confidence + w3*recency(lastUsedAt) + w4*pinned`.
5. Truncate to a hard budget (default: max 12 memories or ~1500 tokens, whichever first).
6. Inject as a clearly delimited, read-only section:

```
## Relevant Memory (retrieved, may be stale — verify before acting on specifics)
- [preference] <content>
- [instruction] <content>
...
```

7. Update `lastUsedAt` on injected memories.

Retrieval must be fast and non-blocking; if embedding is unavailable, degrade to
pinned + keyword only.

### 4.3 Write-back (confirmed-only policy)

Two tiers:

- **Confirmed writes** (`source = "user_confirmed"`, `confidence = 1.0`): persisted
  immediately. Triggered when the user explicitly states a durable preference/instruction,
  or confirms a proposed memory.
- **Inferred writes** (`source = "agent_inferred"`, `confidence < 1.0`): NOT persisted
  silently. The agent proposes them; they are staged and require user confirmation
  before promotion to confirmed (see 4.3.1). Unconfirmed staged items expire quickly
  (default 24h) or on session end.

Enforcement lives in the memory-write path (wrap `memory_store`), not left to prompt
discipline. `memory_store` gains an explicit `confirmed: Boolean` and `category`/`scope`
arguments; inferred stores route to the staging queue.

#### 4.3.1 Confirmation UX — non-blocking, post-run (DECIDED)

Inferred memories are surfaced only **after** the agent finishes its turn/workflow,
and confirmation must never block or interrupt the conversation or a scheduled task.

Rules:
- **Timing:** staging happens during the run, but the "Remember this?" affordance is
  rendered only once the run/turn completes and output is delivered. It never gates,
  pauses, or delays the response, tool execution, or the next user turn.
- **Placement:** a dismissible, non-modal chip/card attached below the completed
  message (not a dialog, not a blocking prompt). Example: `Remember: "prefers metric
  units"?  [Save] [Dismiss]`. Multiple proposals from one run collapse into a single
  compact card ("Remember 2 things? [Review] [Dismiss all]").
- **Non-blocking guarantees:**
  - The user can start typing / send the next message immediately; the card stays
    until acted on or auto-expired, and does not steal focus.
  - No confirmation is ever required to continue. Ignoring the card is a valid path.
  - For **scheduled/background tasks** (`AgentRunWorker`), there is no interactive UI
    to block; inferred memories are staged silently and shown in the settings review
    queue for later confirmation. The task run itself is never delayed.
- **Default action = do nothing:** if the user ignores the card, the staged memory is
  NOT persisted and expires per the staging TTL (default 24h) or on session end.
  Silence means "don't remember," not "remember."
- **Overflow / missed cards:** anything staged but not acted on before it scrolls away
  or the session ends is collected in a **settings review queue** where the user can
  batch-confirm or discard later. This is the safety net, not the primary path.
- **Rate limiting:** cap inferred proposals per run/turn (default 3) and suppress
  near-duplicates of existing memories to avoid nagging.

### 4.4 Lifecycle & hygiene

- **Dedup/merge:** on upsert, if a near-duplicate exists (high semantic sim + same
  category/scope), merge/update rather than create a new row.
- **TTL:** honor `expiresAt`; a periodic sweep (reuse existing scheduler/worker) purges
  expired, non-pinned memories. Defaults by category: `preference`/`instruction`/`fact`
  = no expiry (null); `outcome` = 30 days; **`task_run` = short, default 7 days**
  (configurable). A pinned `task_run` memory is exempt from expiry.
- **Caps:** global soft cap (default 500 memories); when exceeded, evict lowest-ranked
  non-pinned by `score` with age tiebreak.
- **User control:** memory management UI (extend the `RagScreen` pattern) to list,
  search, edit, pin, set scope, and delete memories; plus a global "clear all memory."

### 4.5 Privacy & security

- All memory stays in the local Room/SQLite DB; no network egress for memory in v1.
- Memories included in existing encrypted-at-rest / backup path (`SettingsPorter`)
  unchanged, but backup must round-trip the new fields.
- Never inject secrets: redact obvious credential patterns from memory content on write.
- Respect tool toggles: if `memory_store`/`memory_recall` are disabled, auto-injection
  and write-back are disabled too.
- If cloud sync is ever added, it must be behind an explicit opt-in and a *self-defined*
  pluggable adapter interface — never a hard dependency, never on by default.

## 5. Public interfaces (sketch)

```kotlin
interface MemoryStore {
  suspend fun remember(m: MemoryRecord, confirmed: Boolean): MemoryRecord
  suspend fun forget(key: String)
  suspend fun retrieve(query: MemoryQuery): List<MemoryRecord>   // scoped + ranked + budgeted
  suspend fun stageInferred(m: MemoryRecord)                     // requires later confirmation
  suspend fun promoteStaged(key: String)
  suspend fun all(scope: String? = null): List<MemoryRecord>
}

data class MemoryQuery(
  val text: String,
  val scope: String = "global",
  val topK: Int = 12,
  val minConfidence: Float = 0.4f,
  val tokenBudget: Int = 1500,
)
```

Retrieval is backed by `RagEngine` (semantic) + DAO (keyword/pinned). The chat engine
and `AgentRunWorker` call `retrieve()` and inject the result into the system prompt.

## 6. Rollout

1. **M1 — Schema + semantic backing.** Migrate `MemoryEntity` (additive fields);
   dual-write memories into `RagEngine` on upsert/delete. No behavior change yet.
2. **M2 — Retrieval + injection.** Implement `retrieve()` and inject a bounded memory
   block into system prompts (chat + scheduled). Behind a settings flag, default on.
3. **M3 — Write-back policy.** Add `confirmed`/staging path to `memory_store`; add the
   "Remember this?" confirmation affordance.
4. **M4 — Lifecycle + UI.** Dedup/merge, TTL sweep, caps/eviction, and the memory
   management screen (pin/scope/edit/delete/clear-all).

## 7. Testing

- Unit: ranking, budget truncation, dedup/merge, TTL sweep, eviction ordering,
  scope filtering, secret redaction on write.
- Integration: end-to-end "user states preference -> confirmed store -> later run
  retrieves and injects it -> agent acts consistently"; "inferred memory is NOT
  persisted without confirmation"; backup/restore round-trips new fields.
- Perf: retrieval latency under budget on-device; graceful degrade when embeddings
  unavailable.

## 8. Open questions

- ~~Confirmation UX for inferred memories~~ **DECIDED (see 4.3.1):** non-blocking,
  post-run. A dismissible non-modal chip/card below the completed message; never gates
  the conversation or a scheduled task; ignoring it = don't remember; missed items land
  in a settings review queue. Background tasks stage silently to that queue.
- ~~Default scope for chat-originated memories~~ **DECIDED (see 4.1):** both supported
  per-memory. Global default for durable traits (preferences/instructions);
  conversation-scoped for thread-specific facts/decisions; user can re-scope. Retrieval
  = global + current conversation.
- ~~Scheduled-task outcome memories: distinct category + shorter TTL?~~ **DECIDED
  (see 4.1, 4.4):** yes. Dedicated `task_run` category with a short default TTL (7 days),
  task-scoped, excluded from global chat retrieval unless pinned.

All open questions resolved.
