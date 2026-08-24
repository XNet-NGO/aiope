# Phase 2 — CuO Rebrand + Navigation Overhaul Plan

Branch: `rework/cuo-agentic-android` · Baseline: ea048f81

## Findings (read-only exploration)

- Branding: single `app/src/main/res/values/strings.xml` (no locale variants), manifest label "AIOPE".
- User-visible "AIOPE" strings in code:
  - `ChatScreen.kt:478` EmptyState hero `Text("AIOPE")`
  - `ProfileListScreen.kt:86` "…controlled by AIOPE"
  - `AiopeForegroundService.kt:63` notification title
- Navigation: app module hosts NavHost (`AiopeNavHost` → `aiopeNavigation`), startDestination = Chat,
  routes Chat + Settings only (`AiopeScreens`). SettingsScreen internally routes via string states.
- All settings subscreens are in feature-chat/settings/ (many `internal`) and take `(…) onBack: () -> Unit`.
  ScannerScreen + FileServerScreen are public in feature-chat; ServerListScreen is in feature-remote —
  hence the `serversContent` lambda passthrough into SettingsScreen.
- Scheduled/recurring tasks have NO standalone screen (TimersTab lives inside AgentPanel).
  The existing "tasks" destination = TaskModelScreen = "Default Models per Task". Home card will be
  titled accordingly ("Tasks / default models per task") — nothing lost, nothing invented.
- JDK: Temurin 21 at /home/bsracc/jdks/jdk-21* (system java is JRE-only).

## Task A — Branding

1. `strings.xml`: app_name → `CuO Agentic Android`
2. `AndroidManifest.xml`: android:label → `CuO Agentic Android`
3. `ChatScreen.kt` EmptyState: "AIOPE" → "CuO"
4. `ProfileListScreen.kt`: "controlled by AIOPE" → "controlled by CuO"
5. `AiopeForegroundService.kt`: notification title "AIOPE" → "CuO"
6. `README.md`: heading + first paragraph mention new name (minimal)
7. Untouched: package names, applicationId, BuildConfig, gateway paths, theme resource names.

## Task B — Navigation

1. `core-navigation/AiopeScreens.kt`: add `data object Home : AiopeScreens("home")`.
2. NEW `feature-chat/settings/CuoDestination.kt`: sealed class router
   (List, Agent, Tools, Mcp, Servers, Voice, Theme, Providers, Rag, Tasks, Pick, Edit(profileId)).
3. REFACTOR `SettingsScreen.kt`: string states → CuoDestination; identical wiring/callbacks,
   incl. serversContent passthrough.
4. NEW `feature-chat/settings/HomeScreen.kt` ("CuO Home"):
   - greeting + active provider/model chip (ProviderStore.getActive)
   - large "New Chat" button → navigate Chat route
   - feature-card grid: Agents, Tools, MCP Servers, Remote Servers, Knowledge/RAG, Voice, Theme,
     Tasks, Network Scanner, File Server, Providers, Settings — each opens its existing screen via
     an internal CuoDestination-style router (reuses internal composables; serversContent lambda param
     like SettingsScreen). Providers card gets its own list/pick/edit sub-flow reusing
     ProviderListScreen/TemplatePicker/ProfileEditor.
   - Material3, ChatBackground + LocalThemeState + uiOpacity like other screens.
5. `AiopeNavHost.kt`: startDestination = Home; add Home composable wired with stores + serversContent.
6. `ChatScreen.kt`: add optional `onOpenHome` param + Home icon in toolbar left row (both
   orientations); default `{}` so no call-site breakage.
7. Consistency: all Home subscreens already render own Scaffold/TopAppBar + back → return to Home root.

## Files

Added: CuoDestination.kt (in SettingsScreen.kt file? separate file), HomeScreen.kt
Modified: strings.xml, AndroidManifest.xml, ChatScreen.kt, ProfileListScreen.kt,
AiopeForegroundService.kt, README.md, AiopeScreens.kt, AiopeNavHost.kt, AiopeNavigation.kt,
SettingsScreen.kt

## Build

JAVA_HOME=$(echo /home/bsracc/jdks/jdk-21*) sh gradlew :feature-chat:compileDebugKotlin
:core-navigation:compileDebugKotlin :app:compileDebugKotlin -x spotlessCheck -x spotlessKotlinCheck
--no-daemon -Dorg.gradle.jvmargs="-Xmx2560m -XX:+UseParallelGC -XX:MaxMetaspaceSize=640m"

Commits: chore(brand) for branding, feat(ui) for nav/home. No .cuo-work in commits, no push.

## STATUS: COMPLETE (2026-08-24)

- Commit 7a78935c — chore(brand): full user-visible AIOPE→CuO sweep (13 files).
- Commit 0a44cb6c — feat(ui): HomeScreen + CuoDestination + nav wiring (7 files).
- Compile: BUILD SUCCESSFUL (:feature-chat :core-navigation :app) at HEAD 0a44cb6c,
  verified again on top of the other agent's tools commit 830e1296.
- Extra branding found during sweep: notification titles/channels, file-server HTML/cert CN,
  agent prompts (AgentSeeder/AgentDefaults), export filename/subject, gallery album path,
  chat-export/PDF titles, env-context prompt line.
- Kept technical: package/applicationId, BuildConfig, UA string suffix "AIOPE/2",
  MCP clientInfo "AIOPE2", Log tags, gateway provider label "AIOPE Gateway" + builtinId,
  remote schtasks name 'AIOPE Remote', Theme.AIOPE2 resource name.
- Note: no standalone scheduled-tasks screen exists (timers live in AgentPanel TimersTab);
  the "Models per Task" card maps to the existing TaskModelScreen destination ("tasks").

