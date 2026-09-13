# Remote Servers (SSH + Remote Browser Driving)

keywords: remote servers, ssh, ssh_start, ssh_exec, ssh_exit, remote browser, remote_browser_start, remote_browser_navigate, remote_browser_click, remote_browser_fill, remote_browser_eval, remote_browser_screenshot, remote_browser_detect, remote_browser_stop, aiope-remote, daemon, deploy, DeployUseCase, SshSessionManager, sshj, BouncyCastle, Ed25519, RSA, KeyGen, TOFU, per-server public key, RemoteToolProvider, RemoteDatabase, ServerListScreen, Firefox BiDi, Chrome CDP, headed, headless, share_session, refresh, reseed, action firewall, Tranco, permission_required, profile copy, VACUUM INTO, port 2222

AIOPE can connect to remote Linux (and Windows) servers over SSH and then drive a
**real browser** running on that server — Firefox via WebDriver BiDi or Chrome via
the Chrome DevTools Protocol. The device side is the optional `feature-remote`
module; the server side is a small Go daemon called **aiope-remote**. This page is
written from the actual source. Primary files:

- `feature-remote/.../ssh/SshSessionManager.kt` — SSH client, key loading, per-server public-key derivation, TOFU host verification, `exec`/`scp`.
- `feature-remote/.../ssh/DeployUseCase.kt` — pushes and runs the installer, installs the public key, health-checks, seeds the browser registry.
- `feature-remote/.../ssh/KeyGen.kt` — on-device keypair generation (Ed25519, RSA fallback).
- `feature-remote/.../tools/RemoteToolProvider.kt` — the 16 remote tools (`RemoteToolBridge`).
- `feature-remote/.../ui/ServerListScreen.kt` + `ServerListViewModel.kt` — the "Remote Servers" screen.
- `feature-remote/.../db/RemoteDatabase.kt` — the Room server registry (`browsers` column).
- `daemon/` — the `aiope-remote` Go daemon (dual-engine browser, action firewall, profile copy).

## Where the tools come from

The 16 remote tools are **not** built-in. They are contributed by
`RemoteToolProvider`, which implements the `RemoteToolBridge` interface from
`core-model`, and are only exposed when `feature-remote` is initialized. If the
bridge is absent, calling any of them returns "Remote tools not available." (see the
tools manual). `RemoteToolProvider.buildToolDefs()` returns exactly **16** defs:
**3 SSH tools** + **13 `remote_browser_*` tools**.

`RemoteToolProvider.buildSystemContext()` also injects a `## Remote Servers` block
into the agent's context listing every configured server (name, `host:port`, status,
OS info, and a parsed one-line browser summary), so plain-speech requests ("open the
browser on serv-2") can resolve to the right server and engine.

## Adding and connecting servers (the UI)

`ServerListScreen` ("Remote Servers") lists servers as cards. Each card shows a
status dot, `user@host:port` (monospace), OS info, a browser summary line (parsed
from the stored registry, e.g. `browsers: firefox 156, chrome 153 · desktop`), and
either "Key configured" or "No key set". Tapping a card **connects** it (or
disconnects if already connected); long-press opens the edit sheet; the trash icon
deletes it.

The add/edit sheet (`ServerEditSheet`) collects:

- **Name**, **Host**, **User**, **Port** (port defaults to `22` and is stored as
  `bootstrapPort`).
- **OS Type** — a chip pair, **Linux** or **Windows** (`osType`, default `linux`).
- **Authentication** — a **Password** field (used for initial setup) and an **SSH
  Keys** section. You can paste a keypair, **upload** key files from device storage,
  or **Generate Keypair**.
- **Browsers** (edit mode only) — shows the last detected summary and a **Refresh
  Browsers** button that re-runs detection over an active SSH session.

Two actions at the bottom:

- **Save** — persists the server (`addServer`/`updateServer`). Enabled when name,
  host, and user are non-blank.
- **Deploy** / **Redeploy** — persists and then runs `DeployUseCase.deploy(...)`.
  Enabled only when name/host/user are set **and** a private key or password is
  present.

Servers are stored in Room (`RemoteServerEntity` in `RemoteDatabase`, DB `version =
4`). Notable columns: `port` (default **2222**, the daemon port), `bootstrapPort`
(default **22**, the pre-deploy sshd port), `privateKey`, `publicKey`, `password`,
`osType`, `status`, `osInfo`, `daemonVersion`, and `browsers` (a JSON snapshot of
`__aiope_browser__detect` output). Credentials are stored in the entity as-is; the
source does **not** show field-level encryption of `privateKey`/`password` in this
module — treat the on-device database's security as whatever the app's storage
provides, not as an in-module guarantee.

### Key generation

`KeyGen.generate()` tries **Ed25519** first (`KeyPairGenerator.getInstance("Ed25519")`,
API 33+) and falls back to **RSA 4096** if that throws. The private key is emitted as
a PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`); the public key is hand-encoded into
OpenSSH `authorized_keys` format (`ssh-ed25519 …` or `ssh-rsa …`) with the comment
`aiope@device`.

## SSH under the hood (`SshSessionManager`)

SSH is provided by **sshj**. Because Android ships a stripped BouncyCastle that lacks
X25519/Ed25519, the manager's static initializer **removes the platform "BC"
provider and inserts the full BouncyCastle** from the app's dependency at position 1,
then points sshj's `SecurityUtils` at it. Without this, Ed25519 keys would not load.

- **Sessions** are pooled in a `ConcurrentHashMap<serverId, SSHClient>`. `connect()`
  reuses a live client or cleans up a stale one before reconnecting. Connect timeout
  is **15 s**.
- **Auth** prefers the private key (`authPublickey`) and falls back to the password
  (`authPassword`). If neither is configured it throws.
- **Host verification is TOFU** (trust-on-first-use): `addTofuVerifier` records the
  first-seen host fingerprint per `host:port` and thereafter rejects a **changed**
  key. This is real MITM-on-change protection, but the first connection is trusted
  blindly (no out-of-band fingerprint pinning), and the known-hosts map is
  **in-memory only** (`ConcurrentHashMap`) — it is **not** persisted across app
  restarts, so "first use" resets each run.
- **`exec(serverId, command, timeout = 30)`** opens a session, runs the command,
  reads stdout/stderr, and returns `ExecResult(stdout, stderr, exitCode)` (exit `-1`
  if unknown). `scpTo` uploads a file over SCP.

### Per-server public key derivation

There is **no shared/universal public key**. When you leave the public-key field
empty, `SshSessionManager.derivePublicKey(privateKey, comment = "aiope@device")`
computes the OpenSSH public-key line **from the private key** using sshj's loader
(`KeyType.putPubKeyIntoBuffer` → Base64). Each server therefore gets a public key
derived from whatever private key it holds. `DeployUseCase` uses the stored public
key if present, otherwise derives it and **persists the derived key back** to the
server record.

## Deploying the daemon (`DeployUseCase`)

`deploy(server)` runs on the **bootstrap port** (`bootstrapPort`, normally 22) and:

1. Sets status `deploying`; resolves the effective public key (stored or derived).
2. Connects with key or password (`connectWithKey` / `connectWithPassword`).
3. **Cleans** any existing install. Linux: `systemctl stop aiope-remote`,
   `pkill -f aiope-remote`, remove `~/.local/bin/aiope-remote`. Windows: stop the
   process and delete the `AIOPE Remote` scheduled task.
4. **Runs the installer.**
   - **Linux** (`deployLinux`): copies the bundled asset
     `aiope-remote-installer.sh` from the app to `/tmp`, `chmod +x`, and runs it
     (120 s timeout). A non-zero exit throws with the captured stderr.
   - **Windows** (`deployWindows`): the same self-extracting installer is opened
     on-device; the code finds the `__ARCHIVE_BELOW__` marker, writes the trailing
     `payload.tar.gz`, uploads it via **SFTP** to `%USERPROFILE%\.aiope`, extracts it
     remotely, and runs `installer-windows.ps1`.
5. **Installs the public key** into `~/.aiope/authorized_keys` (`chmod 600` on Linux;
   `Add-Content` on Windows). This is what the daemon later authenticates against.
6. Updates the server to `port = 2222`, status `online`, then **connects to the
   daemon** and runs two built-in commands:
   - `__aiope_health__` → stores `osInfo` (`os arch - hostname`) and `daemonVersion`.
   - `__aiope_browser__detect` → stores the browser registry JSON in the `browsers`
     column (this is the "runs installer + detect" step).

`ServerListViewModel` wraps this: `addAndDeploy` / `redeployServer` run deploy on an
IO dispatcher, surface `deployError` on failure, and set status `error`.
`refreshBrowsers` re-runs `__aiope_browser__detect` on demand and re-stores the JSON.

## The aiope-remote daemon (server side)

The daemon (`daemon/main.go`) is a Go SSH server built on **wish** (`charmbracelet`):

- Listens on `AIOPE_PORT` (default **2222**); host key at `~/.aiope/host_key`; config
  dir `AIOPE_CONFIG_DIR` (default `~/.aiope`).
- **Public-key auth only** (`auth.go`): it loads `~/.aiope/authorized_keys` and
  accepts a connection only if the presented key equals an authorized key
  (`ssh.KeysEqual`). There is **no** password auth on the daemon — password auth is
  used only against the pre-existing sshd during bootstrap/deploy.
- An **exec middleware** (`exec.go`) routes each command:
  - empty command → interactive PTY shell (`ssh -t`),
  - `__aiope_health__` → JSON health report,
  - `__aiope_browser__<verb> [json]` → the browser subsystem,
  - anything else → a normal shell exec (stdout/stderr/exit code streamed back).
- A `ProcessTracker` owns spawned process lifecycle and `KillAll()` runs on shutdown
  (SIGINT/SIGTERM, 5 s graceful).
- `Version` defaults to `0.1.0-dev` (overridden via ldflags at build time).

Each `ssh_exec` from the device is a **separate** SSH connection, so browser state
cannot live per-connection. The daemon keeps a **process-global** `browserManager`
singleton holding the one active browser session.

## The 16 remote tools

### SSH (3)

| Tool | Effect |
| --- | --- |
| `ssh_start` | Open a persistent SSH session to a server (by **name or ID**); marks it `online`. |
| `ssh_exec` | Run a shell command on the active session; returns `{stdout, stderr, exit_code}`. `timeout` default **30 s**. Marked `parallel = true`. |
| `ssh_exit` | Disconnect the session and mark it `offline`. |

All three resolve the server by **name first, then ID** (`getByName` is
`COLLATE NOCASE`, then `getById`). `ssh_exec` requires an active session or it
returns `"No active session … Use ssh_start first."`.

### Remote browser (13)

| Tool | Daemon verb | Notes |
| --- | --- | --- |
| `remote_browser_start` | `start` | Launch Firefox/Chrome. See flags below. |
| `remote_browser_navigate` | `navigate` | Go to `url` (waits for load). |
| `remote_browser_content` | `content` | Visible page text; `offset`/`limit` paginate. |
| `remote_browser_elements` | `elements` | Interactive elements with synthesized selectors. |
| `remote_browser_click` | `click` | Click by CSS selector. **Firewall-gated.** |
| `remote_browser_fill` | `fill` | Fill an input by selector + `value`. **Firewall-gated.** |
| `remote_browser_status` | `status` | Current `{engine, url, title}`. |
| `remote_browser_eval` | `eval` | JS **out of page context (CSP-immune)**; returns the JSON result. |
| `remote_browser_screenshot` | `screenshot` | JPEG, returned base64. |
| `remote_browser_back` | `back` | Back one history entry. |
| `remote_browser_scroll` | `scroll` | `dir` up/down, `px` default **500**. |
| `remote_browser_detect` | `detect` | List engines/versions + display capability. |
| `remote_browser_stop` | `stop` | Stop the session (closes the connection, kills the process). |

On the device side, `RemoteToolProvider` sends each verb as
`__aiope_browser__<verb> <json>` over the SSH session and returns the daemon's JSON
envelope (`{"status":"ok|error|permission_required", ...}`) verbatim. Every browser
tool (except `start`, which auto-refreshes the registry) requires an active SSH
session first.

## The remote browser: 1:1 Firefox / Chrome

The daemon defines a single engine-agnostic `Browser` interface (`browser/browser.go`)
implemented by both a **Firefox/BiDi** backend and a **Chrome/CDP** backend:

- `EngineFirefox` → WebDriver BiDi (Firefox launched with
  `--remote-debugging-port`, `--remote-allow-system-access`, `--profile`,
  `--no-remote`; the daemon reads the `ws://…` BiDi endpoint from stderr or
  `WebDriverBiDiServer.json`).
- `EngineChrome` → Chrome DevTools Protocol (Chrome launched with a dedicated
  `--user-data-dir`).

Because the verbs and the firewall are written against the interface only, the two
engines are **interchangeable**: the same 13 tools and the same `start` flags apply
to both. `remote_browser_start` defaults to `firefox` when `engine` is omitted;
`chrome`/`chromium` selects Chrome. An unknown engine returns an error.

### `remote_browser_start` flags (from source)

- **`engine`** — `firefox` (default) or `chrome`.
- **`mode`** — `headed` or `headless`. **Omit for the default**, which is
  **headed with auto-fallback to headless** when no active display is resolvable
  (`resolveMode`). An explicit `mode` always wins; if you force `headed` on a
  display-less host it honestly falls back and says so
  (`"headless (requested headed, but no active display — fell back)"`).
- **`share_session`** — **default `true`.** When true (or when `refresh`/`reseed` is
  set) the browser drives a **persistent AIOPE profile seeded from the user's real
  logged-in profile**, so cookies/auth/tokens carry over. The real profile is used
  only as a golden master — it is **never driven or modified**. Pass
  `share_session=false` for a **clean throwaway** automation profile with no auth.
- **`refresh`** — auth-only refresh: re-copies auth stores from the real profile into
  the AIOPE profile **while keeping** the agent's accumulated session data (implies
  `share_session`).
- **`reseed`** — full reset: wipe and re-copy the AIOPE profile from the real profile
  (recovery). Implies `share_session`.

`sharesSession()` in the daemon encodes exactly this: `refresh || reseed` → true;
otherwise the omitted flag defaults to **true**.

For Firefox, the daemon picks the master profile with `SelectMasterFirefoxProfile`
(chooses the **actually-used** profile with the most cookies, not merely the first)
and prepares the AIOPE copy via `EnsureAiopeFirefoxProfile`. For Chrome it uses
`EnsureAiopeChromeProfile`. Chrome ≥ 136 hard-blocks remote-debugging on the default
user-data-dir and driving the primary profile is an account-takeover vector, so
`LaunchChrome` **explicitly refuses** the default dir and requires a dedicated one.

### Consistent auth-profile copy

The profile seed/refresh/reseed is a **single shared copy path** for both engines
(`browser/profile_copy.go`). Auth stores are classified and copied by kind:

- **SQLite** stores (Firefox `cookies.sqlite`, `places.sqlite`, `key4.db`,
  `cert9.db`, `storage.sqlite`, `webappsstore.sqlite`; Chrome `Cookies`,
  `Network/Cookies`, `Login Data`, `Web Data`) are copied with **`VACUUM INTO`**,
  which produces a transactionally consistent single-file snapshot **even while the
  browser holds the DB** (WAL folded in). A best-effort byte copy is the fallback.
- **Plain** files (Chrome `Local State`, Firefox `logins.json`,
  `sessionstore.jsonlz4`) are byte-copied (the browser writes them atomically).
- **Directory** stores — the web-storage/IndexedDB dirs where modern SPAs keep
  session tokens (Chrome `Local Storage`, `Session Storage`, `IndexedDB`, `Sessions`;
  Firefox `storage/default`) — are recursively tree-copied, skipping LevelDB `LOCK`
  files so a live refresh doesn't drag a stale lock.

This is why "share my login" works across restarts without corrupting or driving the
real profile.

## The action firewall (prompt-injection defense)

`click`/`fill` are gated by a `Firewall` (`browser/firewall.go`). Its purpose: a
malicious or injected page must not be able to silently make the agent fill and submit
data to an attacker-controlled host.

- A submit is allowed **only when BOTH** the current page host **AND** the
  form-action host are on an allowlist (reduced to registrable eTLD+1 domains via the
  public-suffix list).
- The seed allowlist is the **Tranco top-N**, embedded at build time
  (`//go:embed tranco.txt`). Per-session (`allow:"session"`) and user-added
  (`AllowHost`) entries layer on top.
- When blocked, the daemon returns `status: "permission_required"` with `page_host`
  and `form_host` — a decision handed **to the agent, not a human**. The agent may
  re-issue with `confirm=true` (the tools expose a `confirm` flag; internally
  `allow:"once"`) or persist with `allow:"session"`.
- A global `SetUnsafe(true)` bypass exists but is **off by default**; enabling it
  weakens the guarantee.

Honesty note: this firewall gates **fill/click submits by host**, using the
form-action host the caller declares (`form_action_host`) or, if omitted, the page
host. It is a structural mitigation, not a complete guarantee — it does not sandbox
arbitrary `eval`, and its strength depends on the allowlist and on the declared
form-action host being accurate.

## Server-context summary for the agent

`RemoteToolProvider.buildSystemContext()` renders each server's stored `browsers`
JSON into a readable line, e.g.
`browsers: firefox 156, chrome 153 — desktop/shared-session (mode: head)` or
`… — headless`, derived from the daemon's `display_found` / `suggest_mode` /
`browsers[]` fields (with `(snap)` flagged for snap-confined Firefox, which the daemon
warns against driving). This lets plain-language requests resolve to the right server
and engine without the user knowing IDs.

## Not the same as the on-device browser

These `remote_browser_*` tools drive a **server-side** browser and are entirely
separate from the in-app `WebView` driven by the `browser_*` tools. The two tool sets
never touch each other. See the on-device browser manual for that feature.
