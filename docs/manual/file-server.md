# File Server

keywords: file server, fileserver, FileServerService, FileServerScreen, foreground service, http server, https, self-signed cert, RSA-2048, SHA256withRSA, BouncyCastle, ServerSocket, SSLServerSocket, port 8080, DEFAULT_PORT, EXTRA_ROOT_PATH, path traversal, multipart upload, MAX_UPLOAD_BYTES, 2GB, spool, 413 payload too large, PIN, Bearer, cookie, X-Pin, login page, directory listing, getWifiIp, WiFi lock, notification, LAN sharing, share files, OpenDocumentTree, uriToPath

AIOPE can turn the device into a **local file server** so other machines on the same WiFi/LAN can browse, download, and upload files through a plain web browser. It runs as an Android **foreground `Service`** with its own hand-rolled HTTP/1.1 server on top of a raw `ServerSocket` (optionally wrapped in TLS with a self-signed certificate). No third-party web framework is used — request parsing, directory listings, file serving, and multipart upload handling are all implemented directly in Kotlin.

This page is written from the actual source. Primary files:

- `feature-chat/.../fileserver/FileServerService.kt` — the foreground service and the entire HTTP(S) server.
- `feature-chat/.../fileserver/FileServerScreen.kt` — the Compose Settings UI that configures and starts/stops it.

## Two files, two roles

- **`FileServerService`** — a `Service` (not bound; `onBind` returns `null`) that owns the socket, accepts connections, and serves/receives files. It is a foreground service with an ongoing notification.
- **`FileServerScreen`** — a `@Composable` screen that lets the user pick a directory, port, HTTPS toggle, and optional PIN, then start or stop the service.

## Configuration extras and defaults

`FileServerService` is launched via its companion `start(context, rootPath, port, useHttps, pin)`, which packs an `Intent` with these extras (constants defined on the companion):

- `EXTRA_ROOT_PATH` = `"root_path"` — the directory to serve (required).
- `EXTRA_PORT` = `"port"` — the TCP port; defaults to `DEFAULT_PORT`.
- `EXTRA_USE_HTTPS` = `"use_https"` — boolean, HTTP vs HTTPS.
- `EXTRA_PIN` = `"pin"` — optional access PIN (blank is treated as `null`/no auth via `ifBlank { null }`).

Other companion constants:

- `DEFAULT_PORT` = **8080**.
- `MAX_UPLOAD_BYTES` = **2_000_000_000L** (a 2 GB upload cap; note this is 2 × 10⁹ bytes, not a power-of-two 2 GiB).
- `SPOOL_BLOCK` = `1 shl 18` = **262144 bytes (256 KB)** streaming chunk size.
- `HEADER_MAX` = `1 shl 14` = **16384 bytes (16 KB)** per-part header scan limit.
- `CHANNEL_ID` = `"aiope_fileserver"`, `NOTIFICATION_ID` = **42**.

The companion also exposes `isRunning(): Boolean` (backed by a static `instance` reference) and `currentUrl(): String?` (the running server's URL), plus `start(...)` (calls `startForegroundService`) and `stop(...)` (calls `stopService`).

## Service lifecycle

`onCreate()`:
- Records the singleton `instance = this`.
- Creates the notification channel (`createChannel()`) with `IMPORTANCE_LOW`, no badge, no sound.
- Acquires a **WiFi lock** — `WIFI_MODE_FULL_HIGH_PERF`, tag `"aiope:fileserver"` — so WiFi does not sleep while the screen is off. This is explicitly done to keep the server reachable with the screen off.

`onStartCommand()`:
- Reads the extras. If `EXTRA_ROOT_PATH` is missing it returns `START_NOT_STICKY` (does nothing).
- Builds `rootDir = File(rootPath)`. If the path does not exist or is not a directory, it calls `stopSelf()` and returns `START_NOT_STICKY`.
- Computes the IP via `getWifiIp()`, builds `serverUrl = "$scheme://$ip:$port"` (scheme `https` if `useHttps` else `http`), calls `startForeground(...)` with the notification, starts the server thread, and returns `START_STICKY`.

`onDestroy()`:
- Clears `instance` and `serverUrl`, closes the `serverSocket`, interrupts the server thread, and releases the WiFi lock if held.

## The HTTP server loop

`startServer()` spawns a thread named `"FileServer"`. It creates the socket:

- **HTTP:** `ServerSocket(port)`.
- **HTTPS:** `createSslServerSocket(port)` (see the TLS section).

It then loops `while (!Thread.interrupted())`, calling `accept()` and handing each connection to a **new thread** (`thread { handleClient(socket) }`) — so connections are handled concurrently, one thread per socket. There is **no explicit connection/thread cap** in this code (unlike the browser control server elsewhere in the app).

### Request parsing (`handleClient`)

Per connection: sets `keepAlive`, `tcpNoDelay`, and a 256 KB send buffer, then:

- Reads the request **byte-by-byte** until the `\r\n\r\n` header terminator, deliberately avoiding a buffered reader so the request body is not accidentally consumed. If headers never terminate, it returns.
- Splits the header block into lines; the first line is the request line (`METHOD PATH VERSION`). If it has fewer than 2 space-separated parts, it returns.
- `method` = parts[0]; the path is `URLDecoder.decode(parts[1], "UTF-8")`.
- Parses remaining lines into a case-insensitive (`lowercase()` keys) header map, split on the first `:`.

Responses are written to a `BufferedOutputStream` with a 256 KB buffer. All responses use `Connection: close`.

### Path-traversal protection

The requested path is sanitized into `safePath`:

```
rawPath.substringBefore("?").removePrefix("/").split("/")
  .filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
```

This strips the query string, removes the leading `/`, and **filters out every `.` and `..` segment** before rejoining. The served file is then `File(rootDir, safePath)`. Because `..` segments are removed rather than resolved, a request cannot climb above `rootDir` through the URL path — this is the enforced traversal defense. (Note: it is a segment filter, not a canonical-path/`getCanonicalPath` containment check; symlinks inside the served tree that point outside `rootDir` are not separately guarded against in this code. If that matters for your threat model, treat it as a limitation.)

### Routing

After the (optional) PIN gate:

- `POST` with a `content-type` containing `multipart/form-data` → `handleUpload(...)`.
- Otherwise, resolve `File(rootDir, safePath)`:
  - does not exist → `sendError(404, "Not Found")`.
  - is a directory → `sendDirectoryListing(...)`.
  - is a file → `sendFile(...)`.

## PIN authentication (Bearer / cookie / X-Pin)

If a `pin` is configured, every request is gated in `handleClient` before routing. A request is considered **authenticated** when either:

- the `Authorization` header equals `"Bearer $pin"`, **or**
- a `pin=<value>` cookie (parsed from the `Cookie` header via regex `pin=([^;]+)`) equals the PIN.

If not authenticated:

- If the request carries an `X-Pin` header equal to the PIN (the login form's submission), the server responds `302 Found` with `Set-Cookie: pin=<pin>; Path=/; HttpOnly` (plus `; Secure` only when HTTPS is on) and `Location: /`. The cookie is then used for subsequent requests.
- Otherwise it serves the **login page** via `sendPinPrompt(...)` with status `401 Unauthorized`.

The login page (`sendPinPrompt`) is a small dark-themed HTML form. Its JavaScript `fetch`es `POST /` with an `X-Pin` header carrying the entered value; on redirect it follows `r.url`, otherwise it `alert('Wrong PIN')`. The input is `type='password'`, `maxlength='8'`.

Honest caveats about this PIN scheme (as implemented):

- The PIN is a single shared secret compared with `==` (no hashing, no constant-time comparison, no rate limiting or lockout in this code).
- Over plain **HTTP** the PIN, Bearer token, and cookie travel in cleartext and the cookie lacks the `Secure` flag; the `Secure` flag is only added under HTTPS. `HttpOnly` is always set.
- It is a lightweight access gate for a LAN sharing tool, not a hardened authentication system. Do not treat it as strong auth.

## Directory listing

`sendDirectoryListing` returns `200 OK` HTML (`text/html; charset=utf-8`). It:

- Lists `dir.listFiles()` sorted directories-first, then case-insensitively by name.
- Renders a dark-themed page titled `AIOPE Files - <dir name>`, with a heading, a `⬆️ ..` parent link (when not at `/`), one row per entry (`📁`/`📄` icon, name, and human size for files via `formatSize`), links URL-encoded (`+` → `%20`).
- Includes an upload `<form method='POST' enctype='multipart/form-data'>` with a `multiple` file input and an Upload button.
- Footer shows the item count: `AIOPE File Server • <n> items`.

`formatSize` formats bytes as `B` / `KB` / `MB` (1 decimal) / `GB` (1 decimal).

## Serving files

`sendFile` writes `200 OK` with `Content-Type` from `guessMime(name)`, a correct `Content-Length`, and `Content-Disposition: inline; filename="<name>"`, then streams the file with a 256 KB copy buffer. Note it serves **inline** (browser renders when it can) rather than forcing download, and there is **no HTTP `Range`/partial-content support** in this code — every GET returns the whole file starting at byte 0.

`guessMime` maps by extension: `html/htm`→text/html, `css`→text/css, `js`→application/javascript, `json`→application/json, `txt/log/md`→text/plain, `png`→image/png, `jpg/jpeg`→image/jpeg, `gif`→image/gif, `webp`→image/webp, `svg`→image/svg+xml, `pdf`→application/pdf, `mp4`→video/mp4, `mp3`→audio/mpeg, `ogg`→audio/ogg, `zip`→application/zip, `apk`→application/vnd.android.package-archive, `tar/gz/tgz`→application/gzip, else `application/octet-stream`.

## Multipart upload (spooled to disk, 2 GB cap)

`handleUpload` handles `POST multipart/form-data`:

1. Derives the boundary as `"--" + <boundary from content-type>`.
2. Reads `Content-Length`. If it is `<= 0` **or** `> MAX_UPLOAD_BYTES` (2 GB), it returns `413 Payload Too Large` and stops. (The cap is checked against the declared `Content-Length`, not by counting bytes as they stream.)
3. Ensures the target upload dir (`File(rootDir, path)`) exists (`mkdirs()`).
4. **Spools** the raw request body to a temp file (`File.createTempFile("aiope-upload", ".tmp", cacheDir)`) in 256 KB blocks, reading exactly `Content-Length` bytes. The body is never held in memory as one blob, which is what allows uploads up to 2 GB without OOM.
5. Parses the spooled file with `parseMultipartFromFile(...)` and, on success, replies `303 See Other` with `Location` back to the upload path and a body like `"<n> file(s) uploaded"`.
6. The temp spool file is always deleted in a `finally` block. On any exception it returns `500 Upload failed: <message>`.

**Multipart parsing** is stream-based over the spooled file:

- `findBoundaryOffsets` scans the file in 256 KB chunks for boundary occurrences, keeping a tail window of `boundaryLen - 1` bytes across chunk reads so a boundary split across chunk boundaries is still found.
- `parseMultipartFromFile` walks consecutive boundary offsets. For each part it finds the part-header terminator (`\r\n\r\n`) via `findHeaderEnd` (bounded to `HEADER_MAX` = 16 KB), extracts `filename="..."` (skipping parts with no filename), and derives a **safe filename** by taking the last segment after splitting on `/` and `\` and rejecting `.`/`..`. Content bytes (between header end + 4 and the next boundary − 2, stripping the trailing `\r\n`) are streamed to `File(uploadDir, safeFilename)`. It returns the count of files saved.

The uploaded filename is sanitized (last path segment only; `.`/`..` rejected), so a malicious `filename` cannot itself escape the upload directory via path components.

## HTTPS: self-signed RSA-2048 / SHA256withRSA

When HTTPS is enabled, `createSslServerSocket(port)` generates a certificate at runtime:

- Generates an **RSA 2048-bit** key pair (`KeyPairGenerator.getInstance("RSA")`, `initialize(2048)`).
- Builds a **self-signed X.509 v3** certificate using **BouncyCastle** (`X509v3CertificateBuilder`), issuer/subject `CN=AIOPE File Server`, serial = `System.currentTimeMillis()`, validity from now to now + **365 days**, signed with **`SHA256withRSA`** (`JcaContentSignerBuilder`).
- Loads it into an in-memory `KeyStore` (alias `"aiope"`, empty char-array password), initializes a `KeyManagerFactory` and an `SSLContext.getInstance("TLS")`, and returns `sslContext.serverSocketFactory.createServerSocket(port)`.

Because the certificate is self-signed and regenerated per start (a fresh serial and key pair each time the service starts), browsers will show a security warning and users must accept the untrusted certificate. There is no client-certificate auth and no pinning here.

## IP address selection (`getWifiIp`)

`getWifiIp()` chooses the address shown in the URL:

- Enumerates network interfaces, **skipping** interfaces whose lowercase name starts with `tun`, `wg`, or `lo` (i.e. VPN/WireGuard/loopback), and skips loopback and IPv6 addresses.
- Prefers a private-LAN IPv4 address (`192.168.`, `10.`, or `172.` prefix) and returns the first match.
- **Fallback:** if none found, reads `WifiManager.connectionInfo.ipAddress` and formats the little-endian int into dotted-quad.

This is a best-effort heuristic to prefer the WiFi/LAN IP over a VPN or global address. It does not validate reachability.

## Notification

`buildNotification(url)` shows an **ongoing, silent** `IMPORTANCE_LOW` notification titled **"File Server Running"** with the **server URL** (`serverUrl` / `currentUrl()`) as the content text and the share icon (`android.R.drawable.ic_menu_share`). Tapping it launches the app (`FLAG_ACTIVITY_SINGLE_TOP`, `PendingIntent.FLAG_IMMUTABLE`).

## The Settings screen (`FileServerScreen`)

`FileServerScreen(onBack)` is the configuration UI. State is persisted in `SharedPreferences` named **`"file_server"`**:

- `root_path` (String), `port` (Int, default `DEFAULT_PORT`), `use_https` (Boolean), `pin` (String). Each field writes back to prefs on change.

Controls, top to bottom:

- **Top bar** titled "File Server" with a back arrow.
- **Status card** — shown only while running and a URL exists: displays "Server Running", the URL, and a **Copy URL** button (copies to clipboard, toasts "URL copied").
- **Shared Directory** — an editable `Path` text field (placeholder `/storage/emulated/0/Download`) plus a **Browse…** button that launches the `OpenDocumentTree` picker. On pick it takes a **persistable read URI permission** and converts the tree URI to a filesystem path via `uriToPath`.
- **Port** — numeric-only text field (non-digits filtered), width 120dp.
- **HTTPS (self-signed)** — a `Switch` bound to `use_https`.
- **PIN (optional)** — numeric-only field capped at **8 digits** (`take(8)`), placeholder "Leave empty for no auth".
- **Start/Stop button** — full width. When stopped: validates that `rootPath` is not blank (else toasts "Select a directory first"), parses the port (falling back to `DEFAULT_PORT`), calls `FileServerService.start(...)`, and after a 500 ms delay reads `currentUrl()` into the status card. When running: calls `FileServerService.stop(...)`. The button is error-colored while running.
- **Help text**: "Serves files from the selected directory over your local network. Other devices on the same WiFi can access files by opening the URL in a browser."

`uriToPath(uri)` converts a Storage Access Framework tree URI to a real path: it reads the tree document id and, for `primary:<sub>`, returns `/storage/emulated/0/<sub>`; for `<volume>:<sub>` returns `/storage/<volume>/<sub>`; otherwise returns `null`. Because the served path must be a real `File` the service can `listFiles()`/read/write, directories the app cannot access as a plain path (e.g. some SAF-only locations) will not work even if picked.

## What is and isn't guaranteed (honest summary)

- **Enforced in code:** foreground service + WiFi lock; HTTP/1.1 over raw `ServerSocket`; optional TLS with a self-signed RSA-2048 / SHA256withRSA cert regenerated per start; path-traversal defense by filtering `.`/`..` URL segments; uploaded-filename sanitization to the last path segment; a 2 GB upload cap enforced against the declared `Content-Length` returning `413`; disk-spooled multipart parsing that avoids buffering the whole body in memory; a shared-PIN gate via `Bearer`/cookie/`X-Pin` with a login page and `HttpOnly` (and `Secure` only under HTTPS) cookie.
- **Not enforced / limitations:** no canonical-path (symlink-escape) containment check beyond segment filtering; no HTTP `Range`/resume support (full-file GET only); no per-connection or thread limit; PIN uses plain `==` comparison with no hashing, rate limiting, or lockout; over HTTP all credentials are cleartext; the self-signed certificate will trigger browser warnings and is not pinned. No accessibility (e.g. WCAG) guarantees are made by this code — the served pages are minimal inline HTML.
