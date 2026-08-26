# AIOPE-Remote Handoff Document

## Project Overview

Building a remote development extension for AIOPE2 (Android AI agent app). The extension deploys lightweight SSH daemons to remote Linux servers and controls them via three new tools: `ssh_start`, `ssh_exec`, `ssh_exit`.

## Architecture

```
AIOPE2 (Android) ←── SSH ──→ aiope-remote (Go daemon on Linux servers)
```

- **Go daemon** (`aiope-remote`): Built on Charmbracelet Wish (SSH server framework). Single static binary, ~6MB. Handles command execution, SFTP, process tracking, health reporting.
- **Android module** (`feature-remote`): SSHJ-based SSH client, Room database for server registry, Compose UI for server management, Hilt DI.
- **Bridge pattern**: `RemoteToolBridge` interface in `core-model`, implemented by `RemoteToolProvider` in `feature-remote`, injected into `ChatViewModel` and `ToolExecutor` in `feature-chat`.

## Current State

### What's Done
- Go daemon built, compiled (6.1MB), running on serv-2:2222, health endpoint verified
- Self-extracting installer (7MB, cross-compiled for amd64/arm64/armv7)
- Full `feature-remote` Android module with Room, Hilt, Compose UI
- Tools registered in ToolExecutor (ssh_start, ssh_exec, ssh_exit)
- System prompt injection for active SSH sessions
- Settings → Remote Servers card in ProfileListScreen
- Long-press to edit server cards
- Private/public key paste fields in server edit sheet
- BouncyCastle provider swap for x25519 key exchange

### What's Broken — CRITICAL BUG
**SSHJ on Android cannot authenticate with ed25519 OpenSSH keys.**

Error: `Exhausted available authentication methods`

The key works perfectly from command-line SSH (verified from proot on the same device). The issue is SSHJ's key loading on Android.

#### What's Been Tried
1. `OpenSSHKeyFile.init(StringReader(key), null as PasswordFinder?)` — fails
2. Writing key to temp file, using `client.authPublickey(user, keyFilePath)` — fails
3. Replacing Android's BouncyCastle with full `bcprov-jdk18on:1.80` via `Security.removeProvider("BC")` + `Security.insertProviderAt(BouncyCastleProvider(), 1)` — fixes x25519 key exchange but NOT key loading
4. Using `OpenSSHKeyFile` directly with `init(File)` — fails same way

#### Root Cause Analysis
SSHJ 0.39.0 uses `com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile` internally for the new OpenSSH key format (`openssh-key-v1`). On Android, even with the full BouncyCastle provider, the ed25519 key decoding fails silently and SSHJ falls through to "exhausted authentication methods."

The key format is:
```
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACDQJEUaTRHsJYWEyc5yzFo7HtWvNv1HujE75hXkJG5m/QAAAKDEB2TmxAdk
...
-----END OPENSSH PRIVATE KEY-----
```

#### Possible Fixes to Try
1. **Use EdDSA provider explicitly**: Add `net.i2p.crypto:eddsa:0.3.0` dependency (SSHJ's optional EdDSA support) and register it: `Security.addProvider(new EdDSASecurityProvider())`
2. **Convert key to PEM format**: Convert ed25519 key to PKCS8 PEM format which SSHJ handles differently
3. **Use JSch instead of SSHJ**: JSch (or its fork `com.github.mwiede:jsch:0.2.x`) has better Android ed25519 support
4. **Use Apache MINA SSHD client**: More complex but handles all key types on Android
5. **Shell out to ssh command**: Use `ProcessBuilder` to call the system `ssh` binary (if available) as a fallback
6. **Use the `net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile`** with a converted key

## Server Details

### serv-2
- Host: 192.168.0.12
- User: xnet-admin
- SSH key: ed25519 (`joshuadoucette@xnet.ngo`)
- OS: Ubuntu 24.04.4 LTS, Liquorix kernel 6.19.10
- Go: 1.23.9 (via go1.23.9 wrapper)
- aiope-remote daemon running on port 2222

### Third Phone (test device)
- ADB: 192.168.0.66:34447 (wireless debugging, port may change)
- Has com.aiope2 debug build installed
- Motorola device (HWUI errors are normal)

## File Locations

### Go Daemon (serv-2)
```
~/projects/aiope-remote/
├── main.go          (75 lines)
├── auth.go          (69 lines)
├── exec.go          (139 lines)
├── health.go        (85 lines)
├── process.go       (51 lines)
├── sftp.go          (25 lines)
├── version.go       (4 lines)
├── go.mod
├── go.sum
├── scripts/
│   ├── installer-header.sh
│   └── build-installer.sh
└── dist/
    └── aiope-remote-installer.sh (7MB)
```

### Android Module (serv-2)
```
~/projects/aiope2/feature-remote/
├── build.gradle.kts
├── src/main/AndroidManifest.xml
└── src/main/kotlin/com/aiope2/feature/remote/
    ├── db/
    │   └── RemoteDatabase.kt        (Entity + DAO + DB)
    ├── di/
    │   └── RemoteModule.kt          (Hilt providers + bindings)
    ├── ssh/
    │   ├── SshSessionManager.kt     (SSH client, connection pool)
    │   └── DeployUseCase.kt         (SCP + install flow)
    ├── tools/
    │   └── RemoteToolProvider.kt    (implements RemoteToolBridge)
    └── ui/
        ├── ServerListScreen.kt      (Compose UI)
        └── ServerListViewModel.kt   (ViewModel)
```

### Modified Files in aiope2
- `settings.gradle.kts` — includes `:feature-remote`
- `app/build.gradle.kts` — depends on feature-remote
- `feature-chat/build.gradle.kts` — depends on feature-remote (for bridge)
- `feature-chat/.../ChatViewModel.kt` — injects RemoteToolBridge
- `feature-chat/.../ToolExecutor.kt` — registers ssh tools, dispatches to bridge
- `feature-chat/.../AgentMode.kt` — ssh_exec in PLAN disabled tools
- `feature-chat/.../StreamingOrchestrator.kt` — ssh_exec in PARALLEL_SAFE
- `feature-chat/.../SettingsScreen.kt` — serversContent lambda
- `feature-chat/.../ProfileListScreen.kt` — "Remote Servers" card
- `core-model/.../RemoteToolBridge.kt` — interface
- `core-navigation/.../AiopeScreens.kt` — Servers route (unused, settings handles it)
- `app/.../AiopeNavigation.kt` — passes ServerListScreen to settings

## Key Technical Details

### RemoteToolBridge Interface (core-model)
```kotlin
interface RemoteToolBridge {
  data class ToolDef(val name: String, val description: String, val parameters: String, val parallel: Boolean = false)
  fun buildToolDefs(): List<ToolDef>
  suspend fun execute(name: String, args: Map<String, Any?>): String
  suspend fun buildSystemContext(): String
  suspend fun disconnectAll()
  fun isConnected(serverId: String): Boolean
}
```

### RemoteServerEntity (Room)
```kotlin
@Entity(tableName = "remote_servers")
data class RemoteServerEntity(
  @PrimaryKey val id: String,
  val name: String,
  val host: String,
  val port: Int = 2222,
  val user: String,
  val bootstrapPort: Int = 22,
  val privateKey: String? = null,
  val publicKey: String? = null,
  val status: String = "offline",
  val lastSeen: Long = 0,
  val osInfo: String? = null,
  val daemonVersion: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
)
```

### SshSessionManager Current State
Uses SSHJ 0.39.0 with:
- BouncyCastle provider swap at init
- `OpenSSHKeyFile` for key loading
- `PromiscuousVerifier` for host key verification
- Connection timeout: 10s
- Key loaded from entity's `privateKey` field via StringReader

### Build Commands
```bash
# Build APK (skip bundleDebugAar — pre-existing AGP issue with local .aar files)
cd ~/projects/aiope2
./gradlew assembleDebug -x :feature-chat:bundleDebugAar

# Install on third phone
adb connect 192.168.0.66:PORT
adb -s 192.168.0.66:PORT install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.66:PORT shell am start -n com.aiope2/.MainActivity

# Build Go daemon
cd ~/projects/aiope-remote
go build -ldflags="-s -w" -o aiope-remote .

# Build installer
./scripts/build-installer.sh
```

### Pre-existing Build Issue
`feature-chat` has local `.aar` files (markwon libs) in `libs/` directory loaded via `fileTree`. AGP 9.1 with Gradle 9.3.1 fails on `:feature-chat:bundleDebugAar` because you can't bundle local AARs into a library AAR. The `-x :feature-chat:bundleDebugAar` skip works because the app module only needs compiled classes, not the AAR artifact.

## Dependencies Added
- `com.hierynomus:sshj:0.39.0` — SSH client
- `org.bouncycastle:bcprov-jdk18on:1.80` — Full BouncyCastle provider
- `org.bouncycastle:bcpkix-jdk18on:1.80` — BouncyCastle PKIX (key formats)

## What Needs to Happen Next
1. **Fix the SSHJ ed25519 auth issue** — this is the blocker
2. Test full deploy flow: add server → paste key → deploy → daemon installs → connect on port 2222
3. Test ssh_start/ssh_exec/ssh_exit tools in chat
4. Test system prompt injection (agent should see available servers)
5. Test long-press edit and redeploy
6. CI/CD for the Go daemon (GitHub Actions cross-compile + release)

## AIOPE System Prompt Context
The user is Joshua Doucette (joshuadoucette@xnet.ngo). He's building AIOPE2 — an Android AI agent with 43 tools, 3 modes (Chat/Plan/Build), Jetpack Compose UI, and on-device LLM orchestration via litellm. The app routes through a gateway at inf.xnet.ngo. Default model is Gemma 4 31B IT.

The remote extension is designed so AIOPE can control remote Linux servers for development — edit code, run builds, check logs, deploy — all from the phone without manually SSH-ing.

## Design Decisions
- Servers accessed via Settings → Remote Servers card (not app bar — too crowded)
- Long-press server card to edit/redeploy
- Private key stored per-server in Room entity
- Deploy is one SCP + one command (self-extracting installer)
- Daemon uses Wish (Charmbracelet SSH server framework)
- No Fantasy agent on remote — AIOPE stays the brain
- Three tools only: ssh_start, ssh_exec, ssh_exit
- ssh_exec is PARALLEL_SAFE (can run multiple concurrent commands)
- System prompt auto-injects active server context
- Auto-cleanup on conversation end (ChatViewModel.onCleared)
