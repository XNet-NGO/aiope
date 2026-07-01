# AIOPE Development Loop

## Environment

### Build server (192.168.1.2)
- Hostname: `build`
- User: `xnet-admin`
- Linux x86_64, 8 cores, 15GB RAM
- Working directory: `/home/xnet-admin/projects/`
- Android SDK: `~/android-sdk` (platforms 34-37, NDK 28, build-tools 33-36)
- Java: OpenJDK 21
- Go: 1.25 at `/usr/local/go/bin/go`
- Reachable from phone via LAN (192.168.1.x) or WireGuard

### Repos
| Repo | Path | Remote |
|------|------|--------|
| aiope | `~/projects/aiope` | github.com/XNet-NGO/aiope |
| pulse-ai | `~/projects/pulse-ai` | github.com/XNet-NGO/pulse-ai |
| aiope-inf | `~/projects/aiope-inf` | github.com/xnet-admin-1/aiope-inf |
| aiope-gateway | `~/projects/aiope-gateway` | github.com/XNet-NGO/aiope-gateway |
| xnet-keystore | `~/projects/xnet-keystore` | private |

### Production gateway (inf.xnet.ngo)
- SSH: `ssh ubuntu@inf.xnet.ngo`
- Blue/green deploy: `~/aiope-gateway/deploy.sh`
- Caddy reverse proxy, Docker containers

### Device
- ADB over WiFi: `adb connect 192.168.1.182:5555`
- Pixel (Android 16, API 36)

---

## Android App Build & Install

```bash
# Build AIOPE release
cd ~/projects/aiope
./gradlew :app:assembleRelease -x spotlessCheck -x spotlessKotlinCheck

# Install on device
adb install -r app/build/outputs/apk/release/app-release.apk
```

Build time: ~2 minutes (cached), ~4 minutes (clean).

### Signing
- AIOPE: `../xnet-keystore/xnet.keystore` (alias: `xnet-key`, pass: configured in `keystore.properties`)
- Pulse-AI: `../xnet-keystore/xnet-upload.keystore` (alias: `xnet-upload`)
- `keystore.properties` is gitignored, must exist locally

### Secrets
- `secrets.properties` in each Android repo root (gitignored)
- Contains `GATEWAY_KEY=<api-key>` compiled into BuildConfig

---

## Gateway Deploy

```bash
ssh ubuntu@inf.xnet.ngo "~/aiope-gateway/deploy.sh"
```

Zero-downtime blue-green swap. Builds jar, swaps Caddy upstream, verifies health.

For hot-patching a single file:
```bash
scp src/main/kotlin/.../File.kt ubuntu@inf.xnet.ngo:~/aiope-gateway/src/main/kotlin/.../File.kt
ssh ubuntu@inf.xnet.ngo "~/aiope-gateway/deploy.sh"
```

---

## aiope-remote (daemon)

Go SSH daemon deployed from AIOPE app to remote servers.

### Build installer
```bash
cd ~/projects/aiope/daemon
GOBIN=/usr/local/go/bin/go bash scripts/build-installer.sh 0.1.0
# Output: daemon/dist/aiope-remote-installer.sh (7.3MB, multi-arch)
```

### Update APK asset
```bash
cp daemon/dist/aiope-remote-installer.sh feature-remote/src/main/assets/
```

### Manual service management
```bash
systemctl status aiope-remote
sudo systemctl restart aiope-remote
journalctl -u aiope-remote -f
```

Listens on port 2222. Config at `~/.aiope/`. Host key at `~/.aiope/host_key`.

---

## Spotless

```bash
# Check formatting
./gradlew spotlessCheck

# Auto-fix
./gradlew spotlessApply

# Skip during build
./gradlew :app:assembleRelease -x spotlessCheck -x spotlessKotlinCheck
```

---

## Workflow

```
Edit code → ./gradlew assembleRelease → adb install → test on device
                                                          ↓
                                                   git add -A && git commit && git push
```

No Docker, no containers for builds. Direct Gradle on the build server.
