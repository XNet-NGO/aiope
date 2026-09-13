# Themes & Theming Engine

keywords: theme, theming, dark mode, light mode, system, material you, dynamic color, custom colors, accent, primary, secondary, ui color, surface, opacity, bubble colors, user bubble, ai bubble, agent report bubble, text color, background image, background video, exoplayer, coil, rotation, mute, loop, fps cap, 25fps, power saving, transparent chrome, wcag, contrast, ThemePrefs, ThemeProvider, ThemeState, DataStore

AIOPE's theming engine controls the entire look of chat: light/dark/system/custom modes,
Material You dynamic color, custom accent/UI/text/bubble colors with per-element opacity,
an optional full-screen background image or video, and per-message display toggles.
Everything is persisted in a Jetpack DataStore and pushed live into the Compose tree via a
`CompositionLocal`, so changes apply immediately with no restart.

This page is written from the actual source. Primary files (package
`ngo.xnet.aiope.feature.chat.theme`):
- `ThemePrefs.kt` — all persisted preference keys, their types, and defaults (DataStore
  named `theme_prefs`).
- `ThemeProvider.kt` — builds the Material 3 `ColorScheme` + `ThemeState` and provides
  `LocalThemeState`.
- `ThemeSettingsScreen.kt` — the settings UI, the media picker, and the internal-storage copy.
- `ChatBackground.kt` — draws the image (Coil) or video (ExoPlayer) background.
- `FpsCappedRenderersFactory.kt` — caps background-video presentation to 25 fps.
- `ColorPickerDialog.kt` — the HSV custom color picker.

Consumption happens in `MessageBubble.kt` (bubble colors/opacity, display toggles) and in
every screen's `Scaffold`/`TopAppBar` (transparent chrome when a background is active).

## Persistence and defaults

All theme state lives in a single Preferences DataStore named `theme_prefs`
(`preferencesDataStore(name = "theme_prefs")`). Keys and defaults, exactly as defined in
`ThemePrefs.kt`:

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `theme_mode` | String | `"system"` | `"light"`, `"dark"`, `"system"`, `"custom"` |
| `use_custom_colors` | Boolean | `false` | enables custom accent (primary/secondary) |
| `primary_color` | Int (ARGB) | unset (`null`) | accent primary |
| `secondary_color` | Int (ARGB) | unset (`null`) | accent secondary |
| `use_ui_color` | Boolean | `false` | override surface/background color |
| `ui_color` | Int (ARGB) | unset (`null`) | the surface/background override |
| `use_custom_text` | Boolean | `false` | enable custom text colors |
| `primary_text_color` | Int (ARGB) | unset (`null`) | `onSurface`/`onBackground` |
| `secondary_text_color` | Int (ARGB) | unset (`null`) | `onSurfaceVariant` |
| `use_background` | Boolean | `false` | show background image/video |
| `background_uri` | String | unset (`null`) | `file://` URI in internal storage |
| `background_media_type` | String | `"image"` | `"image"` or `"video"` |
| `background_opacity` | Float | `0.3f` | single opacity slider for the background |
| `video_muted` | Boolean | `true` | mute the background video |
| `video_loop` | Boolean | `true` | loop the background video |
| `video_rotation` | Int | `0` | `0`, `90`, `180`, `270` — applies to image **and** video |
| `use_custom_bubbles` | Boolean | `false` | enable custom bubble colors/opacity |
| `user_bubble_color` | Int (ARGB) | unset (`null`) | user message bubble |
| `ai_bubble_color` | Int (ARGB) | unset (`null`) | AI message bubble |
| `agent_report_bubble_color` | Int (ARGB) | unset (`null`) | agent-report bubble |
| `user_text_color` | Int (ARGB) | unset (`null`) | user bubble text |
| `ai_text_color` | Int (ARGB) | unset (`null`) | AI bubble text |
| `user_bubble_opacity` | Float | `1f` | user bubble alpha |
| `ai_bubble_opacity` | Float | `1f` | AI bubble alpha |
| `show_thinking` | Boolean | `true` | show the reasoning/thinking block |
| `show_status_tags` | Boolean | `true` | show status tags |
| `show_tool_activity` | Boolean | `true` | show tool-activity rows |
| `ui_opacity` | Float | `1f` | overall UI (foreground) opacity |

Colors are stored as ARGB `Int`s (`Color.toArgb()`) and read back with `Color(it)`. A
`null`/unset color means "use the Material default" for that slot.

## Theme modes

The mode is `theme_mode` and is chosen from a segmented button with exactly four options,
in this order: `dark`, `light`, `system`, `custom` (`ThemeSettingsScreen.kt`).
`ThemeProvider` resolves the color scheme from the mode:

- **`"light"`** → `lightColorScheme()`, `isDark = false`.
- **`"dark"`** → `darkColorScheme()`, `isDark = true`.
- **`"system"`** → follows `isSystemInDarkTheme()`. **Material You:** on Android 12+
  (`Build.VERSION.SDK_INT >= 31`) and only in `system` mode, it uses the OS dynamic
  palette via `dynamicDarkColorScheme(ctx)` / `dynamicLightColorScheme(ctx)`. On older
  devices it falls back to the plain dark/light scheme.
- **`"custom"`** → treated as a dark base (`isDark = true`; "custom defaults to dark
  base"). All the custom-color, UI-color, custom-text, and bubble-color controls in the
  settings screen are shown **only** when the mode is `custom`.

### Custom accent colors

When mode is `custom`, `use_custom_colors` is `true`, and a `primary_color` is set,
`ThemeProvider` copies the dark/light base scheme and overrides:
- `primary` = the chosen primary,
- `secondary` = the chosen secondary (falls back to primary if unset),
- `primaryContainer` = primary at `alpha = 0.3`,
- `secondaryContainer` = secondary (or primary) at `alpha = 0.3`.

Accent colors are picked from a preset swatch row or the custom HSV picker
(`ColorPickerDialog.kt`). The preset palette (`PRESET_COLORS`) is: `0xFF00E5FF`,
`0xFF2979FF`, `0xFF651FFF`, `0xFFD500F9`, `0xFFFF1744`, `0xFFFF9100`, `0xFFFFEA00`,
`0xFF00E676`, `0xFF69F0AE`, `0xFFFFFFFF`, `0xFF000000`. Picking a preset in the Primary or
Secondary row also flips `use_custom_colors` to `true`.

### Custom UI color and text colors

Still under `custom` mode:
- **UI color** (`use_ui_color` + `ui_color`): overrides the scheme's `surface`,
  `background`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerLow` to the
  chosen color, and `surfaceVariant` to that color at `alpha = 0.7`.
- **Custom text** (`use_custom_text` + `primary_text_color` / `secondary_text_color`):
  overrides `onSurface`/`onBackground` with the primary text color (defaulting to the
  scheme's `onSurface`), and `onSurfaceVariant` with the secondary text color (defaulting
  to the primary text color at `alpha = 0.7`).

## Message bubbles

Bubble customization requires `custom` mode **and** `use_custom_bubbles`
(`useCustomBubbles = isCustom && …` in `ThemeProvider`). When active, `MessageBubble.kt`
applies:
- **User bubble** — `user_bubble_color` at `user_bubble_opacity`
  (`userBubbleColor.copy(alpha = userBubbleOpacity)`); otherwise the Material
  `primaryContainer`.
- **AI bubble** — `ai_bubble_color` at `ai_bubble_opacity`; otherwise `surfaceVariant`.
  AI content also honors `ai_bubble_opacity` via an `alpha(...)` modifier.
- **Agent-report bubble** — `agent_report_bubble_color`; otherwise the default
  `Color(0xFF1A1A2E)`.
- **Per-bubble text** — `user_text_color` and `ai_text_color` feed the markdown/text theme.

Bubble opacity sliders in the settings screen range `0.05f..1f`. The custom-picker defaults
shown when opening the HSV dialog for each row are: user bubble `0xFF2979FF`, AI bubble
`0xFF37474F`, agent-report `0xFF1A237E`, and all text rows `0xFFFFFFFF`.

## Display toggles (all modes)

These three toggles and the UI-opacity slider are available regardless of mode:
- `show_thinking` (default `true`) — show the reasoning/thinking block in AI messages.
- `show_status_tags` (default `true`) — show status tags.
- `show_tool_activity` (default `true`) — show tool-activity rows.
- `ui_opacity` (default `1f`, slider range `0.1f..1f`) — global foreground opacity applied
  to the whole screen content (`Modifier.alpha(theme.uiOpacity)` wrapping the chat/settings
  content, drawn on top of the background).

## Background image or video

A single background can be enabled in **any** mode via `use_background`. It is either an
image or a video — never both — selected by `background_media_type`.

### Self-contained internal-storage copy

The media picker uses `ActivityResultContracts.GetContent()` launched with `"*/*"`. On
selection (`ThemeSettingsScreen.kt`):
1. The MIME type decides the kind: `contentResolver.getType(uri)?.startsWith("video")`
   → video, otherwise image.
2. The file is **copied into internal storage** under `filesDir/theme_bg/`, named
   `background.mp4` for video or `background.jpg` for image. The stored `background_uri`
   is the resulting `file://` URI (`Uri.fromFile(dest)`).
3. `background_media_type` is set to `"video"`/`"image"` and `use_background` is set `true`.

Because the media is copied into the app's own `filesDir`, the background is fully
self-contained: it survives the source content being moved/deleted and needs no persistable
URI permission. "Clear background" removes `background_uri` and sets `use_background` to
`false`.

### Rendering (ChatBackground.kt)

`ChatBackground` returns early if `use_background` is off or the URI is blank. Otherwise:
- **Image** — Coil `rememberAsyncImagePainter` with `contentScale = ContentScale.Crop`
  (fills the screen, clips overflow, no stretch). Opacity is applied via
  `Modifier.alpha(backgroundOpacity)`.
- **Video** — ExoPlayer inside a `PlayerView` with
  `resizeMode = RESIZE_MODE_ZOOM` (crop-fill, no stretch). The player is muted by setting
  `volume = 0f` when `video_muted`, and loops with `REPEAT_MODE_ALL` when `video_loop`
  (otherwise `REPEAT_MODE_OFF`). `playWhenReady = true`, the controller is hidden, and the
  shutter background is transparent. The `PlayerView`'s alpha is set to `backgroundOpacity`
  via `graphicsLayer`. The player is released in `onDispose`.

### Opacity and rotation

- **Opacity** — one slider drives `background_opacity` (default `0.3f`, slider range
  `0.05f..1f`) for both image and video.
- **Rotation** — `video_rotation` is one of `0`, `90`, `180`, `270`, chosen from a
  segmented button. Despite the name, rotation is applied **identically to image and
  video** through the same `graphicsLayer { rotationZ = … }`. For `90`/`270` the layer is
  scaled up (by `max(w/h, h/w)`) so the rotated content still fills the screen without
  letterboxing.
- **Mute / loop** — `video_muted` and `video_loop` are per-background and only shown in the
  settings UI when the current background is a video.

### The 25 fps cap (power saving)

Background video presentation is capped to **25 fps** via
`FpsCappedRenderersFactory` (`MAX_VIDEO_BACKGROUND_FPS = 25f`, passed into the ExoPlayer
builder). Only a single hardware `MediaCodecVideoRenderer` (subclassed as
`FpsCappedVideoRenderer`) is added — no extension renderers.

Key detail: **decoding continues at the source frame rate; only presentation to the surface
is throttled.** The player loop calls `render(...)` at high frequency; the capped renderer
only forwards to the real pipeline when at least `minFrameIntervalUs = 1_000_000 / 25` µs
have elapsed since the last presented frame, and skips intermediate passes. Because the
parent render still drains output buffers and refills the decoder input queue on every pass,
skipped frames are dropped as late and the codec never starves. Surface composition is the
dominant power cost of a looping background video, so capping presentation cuts
GPU/compositor work substantially without stalling the pipeline. The ExoPlayer load control
is also tightened (`setBufferDurationsMs(5000, 10000, 500, 1000)`, target buffer 5 MB) to
keep memory/battery low for a looping clip.

## Live propagation (ThemeProvider / ThemeState)

`ThemeProvider` is a composable that wraps the app content. It reads every `ThemePrefs` flow
with `collectAsState(...)`, assembles an immutable `ThemeState` data class, and provides it
through `LocalThemeState` (a `compositionLocalOf`), while wrapping `content` in a Material 3
`MaterialTheme(colorScheme = finalScheme)`. Any screen reads the current theme with
`LocalThemeState.current`.

Because the values are DataStore flows collected as Compose state, editing any preference in
the settings screen recomposes consumers immediately — the theme updates live, with no
restart. `ThemeState` carries every field documented above (mode, isDark, accent/UI/text
colors, background settings, bubble colors + opacity, display toggles, `uiOpacity`).

## Transparent chrome when a background is active

When `use_background` is on, the app deliberately makes its chrome transparent so the
background shows through. Every screen's `Scaffold` sets
`containerColor = if (useBackground) Color.Transparent else colorScheme.background`, and each
`TopAppBar` sets its `containerColor` the same way (`Color.Transparent` vs
`colorScheme.surface`). This pattern is applied consistently across the chat screen and all
settings screens (e.g. `ChatScreen.kt`, `SettingsScreen.kt`, `ThemeSettingsScreen.kt`,
`ProfileListScreen.kt`, `RagScreen.kt`, `VoiceSettingsScreen.kt`, `ToolToggleScreen.kt`,
and others). The background itself is drawn by `ChatBackground(theme)` behind the
`ui_opacity`-scaled foreground `Column`/`Box`.

## Accessibility (WCAG 2.1 AA)

Readable contrast is a **design target**: custom colors, bubble opacity, background opacity
(default a low `0.3`), and the custom-text overrides are intended to keep foreground text
against its surface at or above the WCAG 2.1 AA contrast ratios (4.5:1 for normal text,
3:1 for large text). Note that AA contrast is a guideline for choosing colors — it is
**not** currently enforced or auto-corrected in code, so user-chosen custom colors and very
low opacities can fall below AA. Defaults (dark/Material You schemes, opaque bubbles, and a
faint 0.3 background) are chosen to stay comfortably readable.
