# agent-17.md — AIOPE × Android 17: Agentic Device Access & App Functions

> Status: Research / design doc · Last updated: 2026-08-11
> Scope: What Android 17 (API 37) unlocks for AIOPE, what Device Admin/Device Owner actually provide, how to get sensor/device data, and how to support older devices (minSdk 26) alongside the new agentic platform APIs.

---

## 1. Executive summary

- **Device Admin (legacy DPM) grants almost nothing useful anymore.** On Android 17 it provides exactly two powers: `lockNow()` and `wipeData()`. Everything else (password policy, camera disable, keyguard features, screen-timeout policy) was stripped in API 29–30. AIOPE has **no need for lock+wipe**, so Device Admin is a dead end for us.
- **Device Admin/Device Owner do NOT grant sensor or data access.** Sensors and on-device data live under the runtime permission model, which any app can already request. The blocker AIOPE hit today (no DUMP, no `/proc/net`, no package introspection) is a limitation of the **agent shell sandbox**, not Android — the aiope app itself can read every sensor with normal permissions.
- **The real prize is Android 16/17's App Functions** (`android.app.appfunctions`, API 36+): annotate `suspend fun`s with `@AppFunction`, and system agents (Gemini, OEM assistants) can discover and invoke them without opening the app. Android 17 adds the enterprise policy layer and the "Android MCP" direction — apps acting as local MCP servers.
- **There is no true backport.** App Functions requires API 36+; on older devices the OS has no app-functions machinery at all. The strategy is **version-gated AppFunctions (36+) + a universal local MCP server (all API levels)**, keeping AIOPE's own agent as the core capability layer.

---

## 2. Device Admin on Android 17 (API 37) — the honest status

Legacy Device Admin was deprecated by Google in December 2017 and stripped by successive releases. The API-37 reference still documents the role, but it is a shell:

| Capability | Status on Android 17 | Notes |
|---|---|---|
| `lockNow()` | ✅ **Works** | Requires `USES_POLICY_FORCE_LOCK` |
| `wipeData()` | ✅ **Works** | Requires `USES_POLICY_WIPE_DATA` |
| `resetPassword()` / password complexity | ❌ **Dead** | Throws `SecurityException` since API 29–30 (DO/PO only) |
| Camera disable (`setCameraDisabled`) | ❌ **Dead** | Removed for DA (DO/PO only) |
| Keyguard features | ❌ **Dead** | Removed for DA (DO/PO only) |
| Screen-timeout policy | ❌ **Dead** | DO/PO only |
| Encryption policy | ❌ **Dead** | DO/PO only |
| Auto-wipe after N failed attempts | ❌ **Dead** | Never migrated to DA; DO/PO only |
| Silent installs, `forceStopPackage`, certs, settings | ❌ **Never were DA** | Device Owner / DPC role only |

**Verdict for AIOPE:** not worth it. We explicitly don't need lock or wipe, and everything else DA used to do is gone or DO-only.

---

## 3. Device Owner (full MDM) — what it would actually add

Device Owner requires factory-reset + adb provisioning (not a toggle). It's the only role that adds "more visibility/control" beyond a normal app:

| Capability | What AIOPE gets |
|---|---|
| `setNetworkLoggingEnabled` + `retrieveNetworkLogs` | Device-wide network telemetry (every app's traffic) |
| `setSecurityLoggingEnabled` + `retrieveSecurityLogs` | Kernel/security events across the device |
| `forceStopPackage` | Kill any app — the battery-hog killer |
| Silent APK install | Deploy releases without the 8899 staging server / tunnel dance |
| Grant/revoke permissions | No permission dialogs |
| `getApplicationRestrictions` | Read policy state of other apps |
| `setStayOnWhilePluggedIn` | Keep screen on during long builds |
| Install CA certs silently | Provision WireGuard/SSH certs without manual flows |
| `factoryReset()` | Full remote reset |

**Costs:** irreversible without factory reset (removing DO = factory reset), Play Protect/enterprise scrutiny, and it's still **not** the path to sensor data. Defer until there's a concrete fleet/management need.

---

## 4. Sensor & on-device data access — no admin role needed

Sensors and data live under runtime permissions, not DPM:

| Data | Permission | DPM needed? |
|---|---|---|
| Accelerometer, gyro, magnetometer, light, proximity, barometer | **None** — open to any app | ❌ |
| Heart rate / health sensors | `BODY_SENSORS` (runtime) | ❌ |
| Step counter / activity recognition | `ACTIVITY_RECOGNITION` (runtime) | ❌ |
| Location / GPS | `ACCESS_FINE/COARSE_LOCATION` (runtime) | ❌ |
| Camera / mic | Runtime permissions | ❌ |
| App usage stats | `PACKAGE_USAGE_STATS` (special toggle) | ❌ |
| Other apps' notifications | Notification access (special toggle) | ❌ |
| SMS / contacts / calendar | Runtime permissions | ❌ |

**Key insight:** to get real sensor/device telemetry, the aiope app should request the runtime permissions and poll sensors as a foreground service. No admin role involved. The sandbox limits we hit (`dumpsys`, `/proc/net`, package introspection) are harness limitations — fixable by routing those queries through the app's own privileged context, not by Device Admin.

---

## 5. Android 17 agentic app-functions policy — the real prize

### 5.1 What App Functions is

- Platform API `android.app.appfunctions`, **added in API 36 (Android 16)**.
- Apps annotate `suspend fun`s with `@AppFunction` + KDoc. A KSP compiler plugin reads the KDoc and generates an **XML schema** of the exposed functions.
- The OS indexes the schema (AppSearch). System agents (Gemini, OEM assistants) **discover and invoke** functions from natural-language requests **without the user opening the app**.
- Jetpack backport: `androidx.appfunctions` (1.0.0-**alpha** as of research). Early alphas had a compat manager reaching API 35; newer alphas consolidated on the platform API (API 36+).

### 5.2 What Android 17 (API 37) adds

- **Agentic automation policy + app functions policy** — enterprise/DPC layer letting managed devices control and expose app functions to agents.
- **"Android MCP" direction** — Google is steering toward apps acting as local MCP servers that agents can call (the "Android MCP model" from Google's July 2026 developer blog).
- **VPN app exclusion** — exclude specific apps from a VPN tunnel. This is the *proper* fix for the WireGuard battery fight (instead of the split-tunnel hack that broke APK downloads: exclude browser/media from the tunnel, keep SSH routed through it).
- **Cleartext changes** — apps targeting API 37 default to **blocking cleartext traffic**; HTTP needs a network security configuration.

### 5.3 Sources

- Google Android Developers Blog, July 2026: "Build intelligent Android apps: Integrate... AppFunctions" (android-developers.googleblog.com)
- `android.app.appfunctions` package reference — developer.android.com/reference/android/app/appfunctions/package-summary (Added in API level 36)
- Jetpack AppFunctions release notes — developer.android.com/jetpack/androidx/releases/appfunctions
- Jason Bayton, Android Enterprise FAQ (enterprise features in Android 16/17, DO/PO migration, zero-touch)
- Android Central / Android 17 release coverage (June 2026)

---

## 6. Backward compatibility — older devices (minSdk 26)

**There is no true backport of App Functions.** Below API 36 the platform has no app-functions machinery. The library won't crash older devices if guarded, but nothing will index or invoke functions.

| Layer | Requires | Notes |
|---|---|---|
| Platform `android.app.appfunctions` | **API 36+** | Hard requirement |
| Jetpack `androidx.appfunctions` | 1.0.0-alpha | Built on platform API; newer alphas = API 36+ only |
| Discovery + invocation | **API 36+** | OS-side only on 16+ |
| AIOPE's own agent (shell, sensors, SSH, RAG, orchestrate) | **API 26+** | Already universal — the existing capability layer |

---

## 7. Recommended architecture — both worlds

1. **Version-gate the AppFunctions surface** — `if (Build.VERSION.SDK_INT >= 36)` expose AIOPE core ops via `@AppFunction` (e.g. `get_device_status`, `run_ssh_command`, `query_knowledge_base`). Below 36 the annotations are inert; guard all `AppFunctionManager` calls. minSdk 26 stays intact.

2. **Ship a local MCP server (universal layer)** — implement in-app over a localhost socket / bound service on **any API level**. Every device gets a standard agent protocol; on 36+ additionally register AppFunctions so Gemini/system agents can reach AIOPE the platform way.

3. **Keep AIOPE's own agent as the core** — App Functions lets *other* agents call AIOPE; it does not replace AIOPE's own orchestration. Don't let the platform feature pull focus from the capability layer.

4. **Plan the cleartext migration** — current `targetSdk = 34` protects `http://192.168.1.2:8899`-style staging. The moment AIOPE targets 37, cleartext is blocked by default → add a network security configuration for staging.

---

## 8. AIOPE project config (relevant facts)

| Setting | Value |
|---|---|
| `compileSdk` | 37 |
| `targetSdk` | 34 (protects cleartext staging today) |
| `minSdk` | 26 |
| Version (as of doc) | 4.4.7 / versionCode 36 |
| Build system | Gradle 9.7.0 (both wrappers), AGP 9.3.1, Kotlin 2.4.10 |

---

## 9. Next steps (not started — waiting on go)

- Scaffold `:feature-agentbridge` module:
  - **AppFunctions** (API 36+, gated) — 2–3 pilot `@AppFunction`s: `get_device_status`, `run_ssh_command`, `query_knowledge_base`
  - **Local MCP server** (all versions) — JSON-RPC over localhost, the version-agnostic bridge
- Pin `androidx.appfunctions` alpha in `libs.versions.toml` before building
- Decide DO enrollment only if/when a concrete fleet-management or silent-install need appears
