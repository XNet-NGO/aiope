# MCP Servers (Model Context Protocol Connectors)

keywords: MCP, Model Context Protocol, MCP servers, connectors, McpServerScreen, McpManager, McpOAuth2, ToolStore, McpServerConfig, McpTransport, McpAuthType, McpStatus, Streamable HTTP, SSE, transport, headers, authentication, NONE, HEADER, OAUTH2, client_credentials, refresh_token, Bearer token, tool prefix, tools/list, tools/call, JSON-RPC 2.0, protocol 2024-11-05, initialize, notifications/initialized, Mcp-Session-Id, heartbeat, JSON import, mcpServers, Vinkius Cloud, edge.vinkius.com, discover tools, enable toggle, per-mode tool toggle

AIOPE can connect to remote **Model Context Protocol (MCP)** servers over HTTP and
expose their tools to the agent. An MCP server is just a URL that speaks JSON-RPC
2.0; AIOPE discovers its tools, prefixes their names, and merges them into the
agent's tool set alongside the built-in tools. There is no local/stdio transport —
AIOPE only talks to MCP servers over the network.

This page is written from the actual source. Primary files:

- `feature-chat/.../settings/McpServerScreen.kt` — the "MCP Servers" UI (list, detail/edit page, JSON import sheet).
- `feature-chat/.../settings/McpManager.kt` — discovery, JSON-RPC 2.0 transport, protocol handshake, tool prefixing, heartbeat.
- `feature-chat/.../settings/McpOAuth2.kt` — the OAuth2 token fetch (`client_credentials` / `refresh_token`).
- `feature-chat/.../settings/ToolStore.kt` — the `McpServerConfig`, `McpTransport`, `McpAuthType`, `McpStatus` types, persistence, JSON import, and the seeded Vinkius template.
- `feature-chat/.../engine/ToolExecutor.kt` — where enabled servers' tools are merged into the agent's tool list.

## The data shape (`McpServerConfig`)

Servers are stored as `McpServerConfig` (`ToolStore.kt`). Every field, with its
default from code:

- `id: String` — defaults to a random 8-char UUID slice (`UUID.randomUUID().toString().take(8)`). Seeded/imported servers use fixed ids (e.g. `"vinkius"`).
- `name: String` — required (no default).
- `url: String` — required (no default).
- `transport: McpTransport` — default **`HTTP`**.
- `headers: Map<String, String>` — default empty.
- `enabled: Boolean` — default **`true`** (but the seeded Vinkius template is created with `enabled = false`).
- `toolCount: Int` — default `0`; updated after a successful discovery.
- `status: McpStatus` — default **`IDLE`**.
- `error: String?` — default `null`.
- `authType: McpAuthType` — default **`NONE`**.
- OAuth2 fields (all default to `""` / `0L`): `oauthClientId`, `oauthClientSecret`, `oauthTokenUrl`, `oauthAuthUrl`, `oauthScopes`, `oauthAccessToken`, `oauthRefreshToken`, `oauthTokenExpiry: Long`.

Helper methods on the config:

- `isTokenExpired()` — `oauthTokenExpiry > 0 && now >= oauthTokenExpiry`.
- `hasValidToken()` — access token non-blank and not expired.
- `effectiveHeaders()` — the user headers, plus, when `authType == OAUTH2` and an access token is present, an injected `Authorization: Bearer <oauthAccessToken>` header. Note: this overwrites any user-set `Authorization` header only in the OAuth2 case.

Enums:

- `McpTransport { HTTP, SSE }`.
- `McpAuthType { NONE, HEADER, OAUTH2 }`.
- `McpStatus { IDLE, CONNECTING, CONNECTED, ERROR }`.

Persistence is via Room (`McpServerEntity`, keyed by `id`, storing the config as
JSON). `getMcpServers()`, `addMcpServer()`, `updateMcpServer()`,
`removeMcpServer(id)`, `toggleMcpServer(id, enabled)`, and `saveMcpServers(list)` are
the store operations. Config is serialized by `toJson()` and read back by
`fromJson()`; the OAuth2 tokens **are** written into that JSON, so credentials live
in the app's on-device database in plaintext — the source shows no field-level
encryption of `oauthAccessToken`/`oauthClientSecret` in this module.

## The server list screen

`McpServerScreen` shows a top bar titled **"MCP Servers"** with two actions:

- **Edit icon (pencil)** — opens the **JSON import** sheet (labeled "JSON").
- **Add icon (+)** — creates a blank `McpServerConfig(name = "", url = "")`, saves it immediately, and opens its detail page for editing.

At the top of the list is a persistent **"Add connectors with Vinkius Cloud"** info
card with a **"Sign up for Vinkius"** button (opens the referral URL
`https://cloud.vinkius.com/?referral=vk_fftiwfz6` in a browser). This card is always
shown; it is informational, not a server.

When there are no servers, the list shows "No MCP servers" / "Tap + to add a server
or paste JSON config".

Each server is a tappable card showing:

- A **status dot** colored by `status`: green (`0xFF4CAF50`) when `CONNECTED`, primary color when `CONNECTING`, error color when `ERROR`, and a muted outline when `IDLE` (dimmer still if disabled).
- The **name** (or "(unnamed)" when blank) and the **URL** (single line, ellipsized).
- **Pills**: the status label ("Connected"/"Connecting"/"Error"/"Idle"), the transport ("HTTP" or "SSE"), a "`<n> tools`" pill when `toolCount > 0`, and a "Disabled" pill when `enabled == false`.
- The `error` text (up to 2 lines) when `status == ERROR`.

Tapping a card opens the detail/edit page. There is **no** per-row enable switch or
delete button on the list itself — enabling and deleting happen inside the detail
page.

## Adding and configuring a server (detail page)

The detail page (`McpServerDetailPage`) is titled "Add MCP Server" for a new server,
otherwise the server's name. The back arrow **saves** on the way out. When editing an
existing server, a **trash icon** in the top bar opens a "Delete server?" confirm
dialog ("This cannot be undone."); confirming calls `removeMcpServer(id)` and
`mcpManager.clearSession(id)`.

Controls, top to bottom:

1. **Status bar** — a live dot + status pill + transport pill + "`<n> tools`" pill, and the error text when in error.
2. **Name** — text field (`OutlinedTextField`).
3. **Transport** — a two-way segmented control: **"Streamable HTTP"** (`McpTransport.HTTP`) or **"SSE"** (`McpTransport.SSE`).
4. **URL** — text field. This is the full JSON-RPC endpoint URL.
5. **Headers** — a repeatable list of key/value rows. A new server pre-fills one row keyed `Authorization` with an empty value. Each row has a delete (trash) button; **"Add header"** appends a blank row. On save, only rows where **both** key and value are non-blank are kept (trimmed).
6. **Authentication** — a three-way segmented control: **None** / **Header** / **OAuth2** (`McpAuthType.NONE/HEADER/OAUTH2`).
7. **OAuth2 fields** — only shown when Authentication is OAuth2 (see below).
8. **Enabled** — a `Switch`. Only enabled servers contribute tools to the agent (see "How MCP tools reach the agent").
9. **Connect / Reconnect** — a button that saves, sets status to `CONNECTING`, and runs discovery on an IO thread. Disabled while connecting or when the URL is blank. Label is "Connect", "Connecting…", or "Reconnect" (when already `CONNECTED`).
10. **Tools** — after a successful connect, the discovered tools are listed, each with a per-tool `Switch` (see "Per-tool toggles"). Before connecting it shows "Connect to discover available tools"; if connected with none, "No tools available on this server".

Honesty note on **"Header" auth**: selecting `HEADER` does **not** add a dedicated
credential field or its own logic. Header-based auth is implemented entirely through
the **Headers** section you fill in yourself (e.g. an `Authorization` row).
`effectiveHeaders()` only special-cases `OAUTH2`; for `NONE` and `HEADER` the raw
headers map is sent as-is. In other words, `NONE` vs `HEADER` has no functional
difference in the current code beyond the label — what matters is whether you added
an auth header.

## Both transports (how the request is sent)

`McpManager.sendRequest()` sends every JSON-RPC call as an HTTP **POST** to the
server's `url` with `Content-Type: application/json`. The difference between the two
transports is only the `Accept` header and how the response body is parsed:

- **Streamable HTTP** (`McpTransport.HTTP`) — `Accept: application/json, text/event-stream`.
- **SSE** (`McpTransport.SSE`) — `Accept: text/event-stream`.

Either way, if the response `Content-Type` contains `text/event-stream`, the manager
reads the stream and takes the first parseable `data:` line as the JSON-RPC result
(`parseSse`); otherwise it parses the body as JSON directly. So AIOPE handles a
server that answers with SSE even when HTTP transport was selected. Timeouts:
`connectTimeout = 10s`, `readTimeout = 60s` for requests; notifications use 5s/5s.

Session handling: a returned `Mcp-Session-Id` response header is stored per server and
sent back as the `Mcp-Session-Id` request header on subsequent calls.

## The protocol handshake and calls

Discovery (`discoverTools`) does, in order:

1. `clearSession(id)` — drop any cached session/tools for this server.
2. `initialize` — a JSON-RPC `initialize` with `protocolVersion` **`"2024-11-05"`**, empty `capabilities`, and `clientInfo` `{ name: "AIOPE2", version: "1.0" }`.
3. `notifications/initialized` — a fire-and-forget notification.
4. `tools/list` — the tool listing request. Each returned tool becomes an `McpToolMeta(name, description, inputSchema)`; a missing `inputSchema` defaults to `{"type":"object","properties":{}}`.

On success it caches the tools in memory, records `toolCount`, and sets
`status = CONNECTED`, `error = null`. On any JSON-RPC `error` object or exception it
sets `status = ERROR` with the error message.

Tool execution (`executeTool`) sends `tools/call` with `{ name, arguments }`, strips
the server prefix from the tool name first, and returns the joined `text` fields of
the response's `result.content[]` array. On failure it returns
`"MCP error: <message>"`.

All requests are plain JSON-RPC 2.0 (`jsonrpc: "2.0"`, incrementing integer `id`).
The manager is a lightweight hand-rolled client (`HttpURLConnection` + `org.json`),
not a full MCP SDK.

## Authentication options

- **None (`NONE`)** — no auth header injected; only your explicit headers are sent.
- **Header (`HEADER`)** — same wire behavior as None; you supply the auth header yourself in the Headers section (see the honesty note above).
- **OAuth2 (`OAUTH2`)** — AIOPE fetches a bearer token and injects `Authorization: Bearer <token>` via `effectiveHeaders()`.

### OAuth2 fields and flow

When Authentication is OAuth2, these fields appear: **Client ID**, **Client Secret**,
**Token URL**, **Authorization URL (optional)**, and **Scopes (space-separated)**.
There is also a token-status line (green "Valid" / red "Expired", with a formatted
expiry time) when an access token exists, and a button labeled **"Get Token"**,
**"Refresh Token"**, or **"Authorizing…"**. The button is enabled only when Token URL
and Client ID are both non-blank.

The actual grant (`McpOAuth2.fetchToken`) is **not** an interactive/browser
authorization-code flow. It POSTs a form to the Token URL:

- If a refresh token is present → `grant_type=refresh_token` (with `refresh_token`, `client_id`, `client_secret`, and `scope` if set).
- Otherwise → `grant_type=client_credentials` (with `client_id`, `client_secret`, and `scope` if set).

It reads `access_token` (required), `expires_in` (default `3600` seconds if absent →
`expiresAt = now + expires_in*1000`), and `refresh_token` from the JSON response. The
OkHttp client uses 15s connect/read timeouts.

Honesty note: the **"Authorization URL (optional)"** field is stored but never used
by `fetchToken` — there is no authorization-code/PKCE flow in the source. The
`oauthScopes` string is passed through verbatim as the `scope` form parameter. So
OAuth2 here means **client-credentials (machine-to-machine) or refresh-token** auth,
not user-consent login.

Token auto-refresh at call time: in `sendRequest`, if `authType == OAUTH2`, the token
is expired (`isTokenExpired()`), and a refresh token exists, the manager silently
refreshes the token and persists the updated config before sending — so a live
connection keeps working across expiries without you tapping "Refresh Token".

## Per-tool toggles

Each discovered tool row has a `Switch` bound to `toolStore.isToolEnabled(name)` /
`setToolEnabled(name, enabled)`. Tools default to **enabled** (`isToolEnabled` returns
true unless a stored toggle says otherwise; the `defaultOff` set is empty). This is
the **global master** switch for that tool name.

There is also a per-mode layer (`isToolEnabledForMode`): a tool must pass the global
switch first, then the current agent mode's setting (an explicit per-mode override, or
the mode default). The MCP detail page only exposes the global switch; per-mode
toggling lives in the mode/tools UI.

## How MCP tools reach the agent

In `ToolExecutor`, after the built-in tool defs (and any remote-server tools), AIOPE
appends the tools of every server where `enabled == true`:

```
toolStore.getMcpServers().filter { it.enabled }.flatMap { server ->
  var defs = mcpManager.getToolDefs(server.id)
  if (defs.isEmpty()) { mcpManager.discoverTools(server); defs = mcpManager.getToolDefs(server.id) }
  defs
}
```

So a disabled server contributes nothing. Because the tool cache is in-memory (lost on
process restart), the first tool build after launch triggers an **auto-discovery** per
enabled server. The whole merged list is then filtered by
`isToolEnabledForMode(name, mode)`.

### Tool name prefixing

Tools are namespaced by the server's name so multiple servers can't collide.
`getToolDefs` prefixes each tool with `sanitizePrefix(server.name) + "_"`, where
`sanitizePrefix` lowercases the name, replaces every non-`[a-z0-9]` character with
`_`, truncates to **16** characters, and trims a trailing `_`. Example: a server named
"My Server" exposing a `search` tool appears to the agent as `my_server_search`. If a
server has no name, the fallback prefix is `mcp_`. On execution the prefix is stripped
before the `tools/call` is sent, so the server sees its original tool name.

## Heartbeat / liveness

`McpManager.startHeartbeat()` runs a daemon `Timer` ("mcp-heartbeat") every **15
seconds** that pings each **enabled + CONNECTED** server with a `tools/list` request.
If a ping throws, that server is marked `status = ERROR` with `error = "Connection
lost"`. The heartbeat is started once, lazily, when `ChatViewModel` first creates its
`McpManager` (`McpManager(toolStore).also { it.startHeartbeat() }`).

## JSON import

The top-bar pencil opens the **"Import MCP Config"** sheet, which takes a paste of the
standard MCP JSON shape and calls `toolStore.importFromJson(json)`. It expects a root
object with an `mcpServers` key (missing key → error "Missing mcpServers key"); each
entry is keyed by server id. Per entry it reads:

- `type` — `"streamablehttp"` or `"http"` → `McpTransport.HTTP`; anything else → `McpTransport.SSE`.
- `name` — defaults to the entry's id.
- `baseUrl` — the URL (defaults to `""`).
- `headers` — object of string headers.
- `isActive` — the `enabled` flag (defaults to `true`).

Imported servers are merged by id into the existing set (an existing server with the
same id is replaced), and the count of imported entries is reported ("Imported N
server(s)"). Note the import maps only transport, name, `baseUrl`, headers, and
enabled — it does **not** import auth type or OAuth2 fields.

## The seeded "Vinkius Cloud" template

On first run, `ToolStore.seedVinkiusTemplate()` inserts one disabled template server,
guarded by a seed-once flag (`settings_kv` key `seeded_vinkius_mcp`) so that if you
delete it, it is not re-created:

- `id = "vinkius"`, `name = "Vinkius Cloud"`.
- `url = "https://edge.vinkius.com/{token}/mcp"` — a **placeholder**; the `{token}` must be replaced with your own Vinkius Connection Link token. No secret ships in the APK.
- `transport = McpTransport.HTTP` (Vinkius uses Streamable HTTP).
- `enabled = false` — stays off until you supply your token.
- `authType = McpAuthType.NONE` — the token is embedded in the URL path, so no auth header is needed.

To activate it: open the "Vinkius Cloud" server, replace the `{token}` segment of the
URL with your real Connection Link token (`https://edge.vinkius.com/<token>/mcp`),
turn on **Enabled**, and tap **Connect**. The list's info card and the referral link
point to Vinkius Cloud, which hosts a large catalog of hosted MCP connectors; the
referral is optional and unrelated to how the connection itself works.

## Quick answers

- **Where do I add an MCP server?** Settings → MCP Servers → **+** (or the pencil for JSON import).
- **Which transports are supported?** Streamable HTTP and SSE. Both POST JSON-RPC; SSE responses are parsed either way.
- **How do I authenticate?** Add an `Authorization` header (None/Header), or use OAuth2 (client-credentials / refresh-token) which auto-injects a bearer token. There is no browser-based OAuth login.
- **Why don't my tools show up?** The server must be **Enabled** and connected; tools are discovered on connect (and auto-discovered on first use after restart). Check the server isn't in `ERROR`, and that the individual tool's toggle (and mode) is on.
- **What will the tool be named to the agent?** `<sanitized-server-name>_<tool-name>` (server name lowercased, non-alphanumerics → `_`, capped at 16 chars).
