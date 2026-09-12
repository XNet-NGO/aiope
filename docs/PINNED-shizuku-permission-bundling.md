# PINNED: Permission bundling via Shizuku

Follow-up parked for later. Not started.

## Findings (verified on device)

- Android has **no single "super permission"/role** that bundles dangerous runtime
  permissions into one grant. Same-group perms prompt together (`READ_SMS`+`SEND_SMS` = one
  SMS dialog; `READ_CALENDAR`+`WRITE_CALENDAR` = one dialog). `MANAGE_EXTERNAL_STORAGE`
  already bundles file I/O (special-access appop). AIOPE holds the **ASSISTANT role**
  (`VoiceInteractionService`, `mBound=true`) — the biggest role bundle a normal app can get.
- `INTERACT_ACROSS_USERS_FULL`: **shell uid 2000 has it** (`granted=true`, verified). AIOPE
  declares it but it is **not granted** (AIOPE is `/data/app`, not privileged/platform-signed),
  so it is **inert for AIOPE's own UID**.
- `run_sh` currently executes `/system/bin/sh` **in-process as AIOPE's UID**
  (`core-terminal/.../ShellDiscovery.kt`), so cross-user ops (`am`/`pm`/`cmd --user`) are
  **denied today**. Shizuku `rish` shell is **stubbed** with a TODO at
  `ShellDiscovery.kt` (~lines 85-86: "deferred until Shizuku dependency is added").

## Decision

- **Keep the `INTERACT_ACROSS_USERS_FULL` manifest declaration** — harmless when ungranted,
  prerequisite for the Shizuku path.

## The real "bundle" = Shizuku

Routing `run_sh` through Shizuku executes as **shell identity (uid 2000)**, lending AIOPE
shell's whole privileged set (`INTERACT_ACROSS_USERS_FULL`, `PACKAGE_USAGE_STATS`,
`WRITE_SECURE_SETTINGS`, `GRANT_RUNTIME_PERMISSIONS`, ...).

## Future work

1. **Wire the Shizuku `rish` shell** (finish the `ShellDiscovery` TODO) so `run_sh` can route
   via shell identity → unlocks cross-user ops + the privileged set.
2. **One-tap "Grant all via Shizuku" flow**: when Shizuku is connected, run
   `pm grant ngo.xnet.aiope <perm>` for each dangerous permission (SMS/contacts/calendar/
   location) in one shot — eliminates per-permission dialogs (the "bundled grant" UX).
3. **Store-review note**: privileged-permission declarations get flagged by Amazon/Samsung;
   justify as "used only via user-authorized Shizuku/ADB shell." Non-Google distribution, so
   Play's SMS/Contacts policy does not gate.

Verify each on-device before committing.

_Pinned at: main @ d89ea28f, v4.11.0 released._
