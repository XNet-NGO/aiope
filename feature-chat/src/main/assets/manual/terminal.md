# Terminal (proot Alpine)

keywords: terminal, shell, proot, alpine, alpine linux, rootfs, userland, run_sh, run_proot, ShellExecutor, ProotExecutor, ProotBootstrap, ShellDiscovery, TerminalPanel, TerminalSession, arm64-v8a, aarch64, apk, python, gcc, git, bootstrap, install, deploy, redeploy, persistence, bind mount, link2symlink, busybox, bsdtar, talloc, extra keys, agent build-run-fix

AIOPE ships two ways to run commands on the device: the **Android shell** (`run_sh`) and a full **proot Alpine Linux userland** (`run_proot`). The Alpine environment gives the agent an unprivileged Linux distribution — `apk`, `python`, `gcc`, `git` and the rest of the Alpine package universe — running inside the app's private storage with no root required. This page is written from the actual Kotlin source. Primary files:

- `core-terminal/.../shell/ShellExecutor.kt` — the plain Android `sh` executor (`run_sh`).
- `core-terminal/.../shell/ProotExecutor.kt` — runs commands inside the Alpine proot rootfs (`run_proot`), with cancellation.
- `core-terminal/.../shell/ProotBootstrap.kt` — downloads, extracts, and patches the Alpine rootfs.
- `core-terminal/.../ShellDiscovery.kt` — enumerates the interactive shells for the Terminal panel.
- `feature-chat/.../TerminalPanel.kt` — the interactive on-screen terminal UI.
- `feature-chat/.../engine/ToolExecutor.kt` — the `run_sh` / `run_proot` tool dispatch.
- `feature-chat/.../settings/ProfileListScreen.kt` — the Settings "Alpine (proot)" Deploy/Redeploy control.

## Two execution paths: run_sh vs run_proot

AIOPE exposes exactly two shell tools, both registered in `ToolExecutor.buildToolDefs()`:

- **`run_sh`** — "Execute an Android shell command. timeout seconds: 10-30 for quick commands, 60-120 for network, 300-600 for builds." Params: `command` (required), `timeout` (integer seconds, default 300). Dispatches to `ShellExecutor.exec(command, timeoutMs)`.
- **`run_proot`** — "Run a command in the Alpine Linux proot environment (apk, python, gcc, etc). Set timeout for the command." Params: `command` (required) plus a timeout. Dispatches to `ProotExecutor.exec(app, command, timeoutMs)`.

The difference:

| | `run_sh` | `run_proot` |
| --- | --- | --- |
| Runtime | Android's own `/system/bin/sh` (toybox/mksh) | Alpine Linux 3.21 musl userland |
| Toolset | Whatever Android ships (busybox-ish subset) | Full Alpine: `apk`, `python`, `gcc`, `git`, etc. |
| Package manager | none | `apk` (Alpine package keeper) |
| Requires install | no | yes — the ~100 MB rootfs must be deployed first |
| Requires arm64-v8a | no | yes (native binaries are arm64 only) |
| Executor | `ShellExecutor` | `ProotExecutor` |

Both executors truncate output. `ShellExecutor`/`ProotExecutor` cap raw output at **8000 chars** (`out.take(8000) + "\n[truncated]"`), and the tool layer further caps at `shellOutputLimit` (default **12000**, configurable per model config in `ChatViewModel`) appending `"\n...(truncated)"`. Empty output is reported as `"(no output, exit <code>)"`.

## run_proot preconditions (in dispatch order)

`ToolExecutor` guards `run_proot` with two checks before executing:

1. **arm64-v8a requirement** — if `android.os.Build.SUPPORTED_ABIS` contains no `arm64-v8a` entry, the tool returns:
   > "The proot/Alpine environment ships arm64-v8a native binaries only; this device is `<abis>`. Use run_sh instead."
2. **Installed check** — if `ProotBootstrap.isInstalled(app)` is false, it returns:
   > "Alpine not installed. Set up proot in Settings first."

Only when both pass does it call `ProotExecutor.exec()`. (The `ShellDiscovery`/`ProotExecutor` paths use `System.getProperty("os.arch")` to select `aarch64` vs `x86_64` download URLs, but the tool gate itself is strictly `arm64-v8a`.)

## The proot Alpine userland (unprivileged)

proot is a userspace `chroot`/bind-mount emulator — no root, no `su`, no kernel namespaces. `ProotExecutor.exec()` launches the `proot-xed` binary (`libproot-xed.so` / `libroot-xed.so`, located by `ProotBootstrap.findProotXed()`) against the extracted rootfs at `filesDir/env/alpine`, and runs the command through `/bin/bash` if present, else `/bin/sh`, as `-c "<command>"`.

Key proot arguments assembled by `buildProotArgs()`:

- `--kill-on-exit` — tear down child processes when proot exits.
- `-r <rootfs>` — the Alpine root.
- `-0` — fake root (uid/gid 0 inside the guest).
- `--link2symlink` — emulate hard links via symlinks (Android FS lacks hardlink support in app storage).
- `-L` — correct handling of symlinked binaries.
- `-w /root` — working directory.
- A large set of `-b` bind mounts of the host into the guest: `/apex`, `/odm`, `/product`, `/system`, `/system_ext`, `/vendor`, the two `linkerconfig` `ld.config.txt` paths (when present), `/dev`, `/dev/urandom → /dev/random`, `/proc`, `/sys`, `/proc/self/fd → /dev/fd`, the app `filesDir`, `filesDir/home → /root`, `rootfs/tmp → /dev/shm`, `/proc/self/fd/{0,1,2} → /dev/std{in,out,err}`, and a fake `fips_enabled` file bound to `/proc/sys/crypto/fips_enabled`.
- A clean environment via `/usr/bin/env`: `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, `HOME=/root`, `USER=root`, `TERM=xterm-256color`, `TMPDIR=/tmp`, `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`.

The process environment (`buildProotEnv()`) sets `PROOT_TMP_DIR=$filesDir/tmp`, `PROOT_VERBOSE=-1`, `LD_LIBRARY_PATH=$filesDir` (so the copied `libtalloc.so.2` resolves), and, when present, `PROOT_LOADER`/`PROOT_LOADER32` pointing at `libproot.so`/`libproot32.so`. `proot warning:` / `proot info:` lines are filtered out of the returned output.

`ProotExecutor` tracks the current process in a `@Volatile currentProcess` field and exposes `cancel()`, which calls `destroyForcibly()` — this is how an in-flight `run_proot` (or a long agent step) can be interrupted externally.

## Bootstrap / install (~100 MB via Settings)

The Alpine rootfs is not bundled in the APK; it is downloaded on demand. `ProotBootstrap` owns the lifecycle. `ROOTFS_VERSION = "rootfs_alpine_v1"` and `isInstalled()` returns true only when the version marker exists, the `env/alpine` dir exists, and `bin/busybox` (or `bin/sh`) is present.

`setup(ctx, logCb)` (must run on a background thread) performs:

1. **Create dirs** — `env`, `env/alpine`, `filesDir/tmp`, `filesDir/home`.
2. **talloc** — copy the native `libtalloc.so` to `filesDir/libtalloc.so.2` (proot-xed depends on it); aborts if missing.
3. **bsdtar** — verify the native `libbsdtar.so` exists and is executable (used to unpack `.tar.xz`); aborts if missing.
4. **Download rootfs** — from
   `https://github.com/xnet-admin-1/box/releases/download/rootfs-alpine-3.21.3/box-alpine-3.21-<arch>.tar.xz`
   where `<arch>` is `aarch64` (default) or `x86_64` per `os.arch`. Redirects are followed manually (up to 5); progress is logged every ~512 KB.
5. **Extract** — `bsdtar -xf <tarball> -C <rootfs> --no-same-owner` (120 s limit), then delete the tarball and write the version marker.
6. **Patch** — `patchRootfs()` (see below).
7. **distroSetup** — a one-time `apk update` inside proot (60 s timeout, guarded by a `.distro_setup_done` marker; non-fatal on failure).

`patchRootfs()` makes the guest usable and quiet:

- Writes `/etc/resolv.conf` (Google 8.8.8.8/8.8.4.4 + Cloudflare 1.1.1.1).
- Creates standard dirs (`tmp`, `var/tmp`, `home`, `root`, `root/workspace`, `root/.config`) and makes `tmp` world-writable.
- Marks executables in bin dirs and `.so` files (including musl ELF interpreters `ld-musl-aarch64.so.1` / `ld-musl-x86_64.so.1`) executable.
- Replaces `ldconfig` stubs with `#!/bin/sh\nexit 0` (`fixProotStubs()`).
- Writes `/root/.profile` and `/etc/profile.d/box.sh` (PATH, `PS1='box:\w# '`, UTF-8 locale, `ll`/`ls` aliases), plus `.wgetrc`, `.curlrc`, and a `.gitconfig` (`pager = cat`, `sslVerify = false`, `detachedHead = false`).
- Seeds `/etc/apk/repositories` with Alpine v3.21 `main` + `community`.
- Maps Android GIDs into `/etc/group` (`aid_inet` 3003, `aid_net_raw` 3004, `aid_net_admin` 3005, `aid_everybody` 9997, `aid_nobody` 9999, plus the dynamic `u0_aNNN` app UID) so networking works and "cannot find name for group ID" warnings are suppressed.

`ensurePatched()` re-applies patches to an existing rootfs (e.g. after an app update).

### The Settings control

Settings → the **"Alpine (proot)"** list item (in `ProfileListScreen.kt`) drives install. Its supporting line shows `"Installed"` (or `"Not installed"` in the error color). The trailing button reads:

- **Deploy** when not installed,
- **Redeploy** when already installed,
- **Deploying...** while running.

Tapping it launches a coroutine on `Dispatchers.IO`. On **Redeploy** it first deletes the dot-markers and recursively removes the old rootfs, then calls `ProotBootstrap.setup(ctx)`, streaming each log line into the status text ("Downloading...", "Extracting rootfs...", etc.). When done, status becomes `"Installed"` or `"Failed"`. The download is roughly ~100 MB of on-device storage for the Linux environment (optional).

## Persistence across sessions

The Alpine userland is bootstrapped **once** and then persists. Everything lives in the app's private `filesDir`:

- `filesDir/env/alpine` — the rootfs (installed packages, files, config).
- `filesDir/home` — bound to the guest's `/root`, so the home directory (and `/root/workspace`) survives.
- `filesDir/tmp` — proot temp / `/dev/shm`.

Because these directories are not wiped between runs, anything you `apk add`, any repo you `git clone`, and any file you write in `/root` stays available in the next `run_proot` call or the next interactive session. Only an explicit **Redeploy** (which deletes and re-downloads the rootfs) resets it.

## The interactive TerminalPanel

`TerminalPanel` (a `@Composable`) is the on-screen terminal, wired to a real PTY-backed `TerminalSession`/`TerminalView` (from `core-terminal`). Layout:

- A **shell picker** row of `TextButton`s across the top, one per *available* shell; the selected one is highlighted with the theme primary color.
- The **terminal view** (`TerminalViewComposable`) fills the remaining space on a black background, using a monospace font. Pinch-to-zoom scales text size (clamped 6–36 dp); tapping requests focus and pops the soft keyboard.
- An **extra-keys bar** (`ExtraKeysBar`) shown only while the soft keyboard is visible.

Sessions are cached in `TerminalSessionHolder` (a `getOrPut` map keyed by shell id), so switching tabs and returning keeps the same live session rather than restarting the shell — reinforcing persistence at the UI level too.

**ExtraKeysBar** provides 10 keys: `ESC`, `TAB`, `CTRL`, `ALT`, `↑ ↓ ← →`, `HOME`, `END`. `CTRL`/`ALT` are *sticky* modifiers with three states cycled on tap — IDLE → ARMED → LOCKED — rendered in distinct colors (idle dark, armed green, locked red). ARMED modifiers auto-reset after the next keypress; LOCKED ones stay until tapped off. Modifier state feeds back to the terminal via `TerminalSessionHolder.ctrlDown` / `altDown`.

### Which shells appear (ShellDiscovery)

`ShellDiscovery.getShells(ctx)` builds the picker list:

1. **Android Shell** (id `sh`) — always present. Command `/system/bin/sh`, `PATH=/system/bin:/system/xbin`, `HOME=$filesDir/home`, and an `ENV=.mkshrc` that sets a compact `PS1`.
2. **Alpine (proot)** (id `alpine`) — always shown. If `ProotBootstrap.isInstalled()` is true **and** the proot binary is found, it is available and launches proot with the same bind-mount/arg recipe as `ProotExecutor` but ending in an interactive login shell (`/bin/bash --login` or `/bin/sh --login`). Otherwise it is listed as **"Alpine (setup needed)"** with `available = false, needsSetup = true`, and the panel filters it out of the selectable set (`availableShells = shells.filter { it.available }`).
3. A **Shizuku `rish`** shell is stubbed out in the source (TODO — deferred until the Shizuku dependency is added).

## The agent build-run-fix loop

The shell tools are what turn AIOPE into a coding agent. In **Build** mode every tool defaults ON, and the builtin **Coder** agent (`AgentSeeder.kt`) is configured with the tool set:

`read_file, list_directory, write_file, run_sh, run_proot, ssh_start, ssh_exec, search_web, fetch_url`

Its system prompt encodes the loop explicitly:

> "Process: read context → implement → verify (build/test) → report result."
> "- Run tests/build after changes to verify correctness
> - If tests fail, fix them before reporting done"

In practice the agent chains: `read_file`/`list_directory` to understand the project → `write_file`/`edit_file` to implement → `run_proot` to build/compile/test inside Alpine (using `gcc`, `python`, `apk`-installed toolchains) or `run_sh` for Android-side commands → read the (truncated) output → fix and repeat until the build/tests pass. The **Build** mode prompt (`AgentDefaults.kt`) tells the model to "execute autonomously… chain tools to complete the goal end to end. If a step fails, diagnose and adapt rather than stopping," and notes it has "the full tool set including shell, files, SSH…". Long-running `run_sh`/`run_proot` calls report progress via `ToolProgressBus`, and a stuck proot process can be aborted through `ProotExecutor.cancel()`.

This gives the agent a genuine compile-and-test surface on-device: write code, build it in the Alpine userland, observe failures, and iterate — the same read → implement → verify → fix cycle a developer uses at a real terminal.
