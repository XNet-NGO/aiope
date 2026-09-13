---
title: Dynamic UI (native interactive UI in chat)
category: feature
keywords: [dynamic ui, aiope-ui, interactive ui, native components, forms, buttons, callback, toggle, open_url, copy_to_clipboard, tabs, accordion, table, chip_group, slider, countdown, node types, renderer, parser]
---

## Overview

Dynamic UI lets AIOPE render native, interactive Compose UI directly inside chat
messages instead of plain markdown. The agent emits a JSON UI description inside a
fenced `aiope-ui` code block; AIOPE parses that JSON into a tree of nodes and renders
it as real Material 3 widgets (buttons, text inputs, tables, tabs, etc.). User
interaction (button presses, form submissions) is sent back to the agent as callback
events.

Source files (package `ngo.xnet.aiope.feature.chat.dynamicui`):
- `AiopeUiNode.kt` — the sealed node hierarchy (the data model).
- `UiAction.kt` — the action types buttons/countdowns can trigger.
- `AiopeUiParser.kt` — parses `aiope-ui` JSON into a node tree (`AiopeUiParser.parse`).
- `AiopeUiRenderer.kt` — the `@Composable AiopeUiRenderer` that draws the tree.

Detection and rendering of the fenced block happen in `MessageBubble.kt`.

## The aiope-ui fenced block format

The agent wraps a single JSON object in a fenced block tagged `aiope-ui`:

    ```aiope-ui
    {"type":"column","children":[
      {"type":"text","value":"Your name?","style":"title"},
      {"type":"text_input","id":"name","placeholder":"Enter name"},
      {"type":"button","label":"Submit","action":{"type":"callback","event":"submit","collectFrom":["name"]}}
    ]}
    ```

How `MessageBubble.kt` handles it:
- It detects a block by checking that the message contains ` ```aiope-ui `.
- A block is considered **complete** only when the regex ` ```aiope-ui\s*\n[\s\S]*?``` `
  matches (i.e., the closing fence has arrived). While the block is still streaming and
  incomplete, a shimmer placeholder reading "Building UI…" with animated loading dots is
  shown, and any text *before* the block is rendered as markdown.
- Once complete, the message is split into segments: markdown text segments and
  `aiope-ui` blocks. Multiple `aiope-ui` blocks and interleaved markdown in one message
  are supported.
- Each block's inner JSON is passed to `AiopeUiParser.parse(...)`. If it returns a
  non-null node, `AiopeUiRenderer` renders it inside a `SelectionContainer`.
- The renderer is called with `isInteractive = !isStreaming`, so controls are disabled
  while the response is still streaming and become interactive once streaming finishes.

### Parser input tolerance

`AiopeUiParser` is deliberately tolerant of LLM formatting mistakes:
- It repairs broken keys of the form `"key={...` into `"key":{...` (via `fixBrokenKeys`).
- It sanitizes JSON by balancing braces/brackets and closing unclosed structures, and
  trims trailing `,`/`:` (via `sanitizeJson`).
- It supports **NDJSON**: if the input has multiple complete `{...}` objects, one per
  line, each line is parsed and the results are wrapped in a single `ColumnNode`.
- Unknown types return `null` and are skipped. An object with an empty/missing `type`
  is inferred (`inferBare`): if it has a `value`/`content`/`text`/`title`/`label` key it
  becomes a `TextNode`; if it has `children` it becomes a `ColumnNode`.

## Renderable node types

There are **28 renderable node types** defined in `AiopeUiNode.kt` (each is a data class
implementing the sealed interface `AiopeUiNode`, which has a single `id: String?`
property). They are grouped in the source as:

Layout (6):
- `ColumnNode` — `type: "column"` — vertical stack of `children`.
- `RowNode` — `type: "row"` — horizontal flow of `children` (uses `FlowRow`; if all
  children are `StatNode`, they are spaced evenly).
- `CardNode` — `type: "card"` — bordered/elevated surface wrapping `children`.
- `DividerNode` — `type: "divider"` — horizontal rule.
- `TabsNode` — `type: "tabs"` — pill-style tab bar; holds `tabs: List<TabItem>` and an
  optional `selectedIndex`.
- `AccordionNode` — `type: "accordion"` — expandable section with `title`, `children`,
  optional `expanded`.

Content (7):
- `TextNode` — `type: "text"` — `value`, optional `style`, `bold`, `italic`, `color`.
- `ImageNode` — `type: "image"` — `url` (falls back to `src`), optional `alt`. Rendered
  with Coil `AsyncImage`; long-press saves the image to the gallery.
- `CodeNode` — `type: "code"` — monospace `code` block with optional `language` label
  and a copy button.
- `QuoteNode` — `type: "quote"` — `text` with optional `source`, styled as a blockquote.
- `IconNode` — `type: "icon"` — `name`, optional `size`, `color` (see icon table below).
- `BadgeNode` — `type: "badge"` — small pill showing `value`, optional `color`.
- `StatNode` — `type: "stat"` — big `value` with a `label` and optional `description`.

Interactive (8):
- `ButtonNode` — `type: "button"` — `label`, optional `action`, `variant`, `enabled`.
- `TextInputNode` — `type: "text_input"` — requires `id`; optional `label`,
  `placeholder`, `value`, `multiline`.
- `CheckboxNode` — `type: "checkbox"` — `id`, `label`, optional `checked`.
- `SelectNode` — `type: "select"` — `id`, optional `label`, `options`, `selected`
  (dropdown).
- `SwitchNode` — `type: "switch"` — `id`, `label`, optional `checked`.
- `SliderNode` — `type: "slider"` — `id`, optional `label`, `value`, `min`, `max`,
  `step`.
- `RadioGroupNode` — `type: "radio_group"` — `id`, optional `label`, `options`,
  `selected`.
- `ChipGroupNode` — `type: "chip_group"` — `id`, `chips: List<ChipItem>`, and
  `selection` (default `"single"`; `"multi"` allows multiple selection).

Feedback (2):
- `ProgressNode` — `type: "progress"` — optional `value` (0..1; indeterminate if null)
  and `label`.
- `AlertNode` — `type: "alert"` — `message`, optional `title`, optional `severity`.

Data (2):
- `TableNode` — `type: "table"` — `headers` and `rows` (list of string rows).
- `ListNode` — `type: "list"` — `items` (each a node) with optional `ordered`
  (numbered vs bulleted).

Additional (3):
- `CountdownNode` — `type: "countdown"` — `seconds`, optional `label`, and an optional
  `action` fired when the timer reaches zero.
- `AvatarNode` — `type: "avatar"` — optional `name`, `imageUrl`, `size` (falls back to
  initials from `name` when no image).
- `BoxNode` — `type: "box"` — `children` with optional `contentAlignment`
  (`center`, `top_center`, `bottom_center`, else top-start).

### Accepted type strings (~29)

The parser's `parseNode` dispatch accepts one string per renderable node type, **plus
the `"tab"` alias**, for **29 accepted `type` strings** in total:

`column`, `row`, `card`, `divider`, `tabs`, `accordion`, `tab`, `text`, `image`,
`code`, `quote`, `icon`, `badge`, `stat`, `button`, `text_input`, `checkbox`, `select`,
`switch`, `slider`, `radio_group`, `chip_group`, `progress`, `alert`, `table`, `list`,
`countdown`, `avatar`, `box`.

- `"tab"` is an **alias, not its own node type**: a standalone `{"type":"tab",...}` is
  parsed into a `ColumnNode` from its `children`. `TabsNode` also accepts tabs either as
  a `tabs` array or as `children` of `type: "tab"` objects.
- An empty `type` (`""`) triggers `inferBare` (see above). Any other unrecognized string
  yields `null` (skipped).

Helper data classes in `AiopeUiNode.kt` that are **not** nodes: `TabItem` (label +
children for tabs), `ChipItem` (label + value for chips), and `FrozenSubmission` (frozen
submission state, see below).

## Enum values (from the parser)

- `TextStyle` (text `style`): `headline`, `title`, `body`, `caption`.
- `ButtonVariant` (button `variant`): `filled`, `outlined`, `text`, `tonal`.
- `AlertSeverity` (alert `severity`): `info`, `success`, `warning`, `error`.

Text also accepts `bold`/`italic` booleans; if omitted, a `style` value of `"bold"` or
`"italic"` is interpreted accordingly. Any unrecognized enum string parses to `null`.

## Action types

Actions live in `UiAction.kt` (sealed interface `UiAction`) and are attached to
`ButtonNode.action` and `CountdownNode.action`. There are **4 action types**:

1. **`CallbackAction`** — the default. Fields: `event` (String), optional `data`
   (Map), optional `collectFrom` (List of input ids). JSON:
   `{"type":"callback","event":"name","data":{...},"collectFrom":["input_id"]}`.
   A bare string action (e.g. `"action":"submit"`) is also treated as a callback with
   that string as the `event`.
2. **`ToggleAction`** — `{"type":"toggle","targetId":"id"}` (or any action object with a
   `targetId` key). Toggles the visibility of the node whose `id` matches `targetId`.
3. **`OpenUrlAction`** — `{"type":"open_url","url":"..."}` (or an action object that has
   a `url` but no `event`). Opens the URL.
4. **`CopyToClipboardAction`** — `{"type":"copy_to_clipboard","text":"..."}`. Copies the
   given text to the clipboard.

### How callbacks and `collectFrom` work

- All form controls with an `id` (`text_input`, `checkbox`, `select`, `switch`,
  `slider`, `radio_group`, `chip_group`) write their current value into a shared
  in-memory form-state map keyed by `id`.
- When a button with a `CallbackAction` is pressed, `collectFormData` builds the payload:
  it starts from the action's static `data`, then for each id in `collectFrom` it adds
  that input's current value from the form state.
- The resulting `(event, data)` pair is delivered to the host via the renderer's
  `onCallback` lambda. In `MessageBubble` this is forwarded to `onUiCallback(event, data)`.
- **Forms are composed, not a node type.** There is no `"form"` node. A form is simply a
  `column`/`card` containing input nodes plus a submit `button` whose `CallbackAction`
  lists the input ids in `collectFrom`.

### `aiope://` deep-link callbacks

The renderer installs a custom `UriHandler`. Any link/URL of the form
`aiope://action?key=value&key2=value2` is intercepted: the scheme is stripped, the path
becomes the callback `event`, query params (URL-decoded) become the `data` map, and it is
dispatched through the same `onCallback` path. Non-`aiope://` URLs are opened normally.

## Rendering behavior and details

- The root is drawn inside a rounded `Surface` (dark card background). Nesting is capped
  at `MAX_DEPTH = 10`; deeper nodes are not rendered.
- A node whose `id` has been toggled off (`toggle[id] == false`) is skipped, enabling the
  `toggle` action to show/hide sections.
- **Interactivity:** when `isInteractive` is false (during streaming) controls are
  disabled. A `ButtonNode` is enabled only when interactive and `enabled != false`.
- **Frozen submissions:** `FrozenSubmission(values, pressedEvent, isPending)` records a
  past submission so a rendered-again message shows which button was pressed and the
  values sent; the pressed button shows a pulsing highlight while `isPending`.
- **Colors** (`resolveColor`) accept named strings: `primary`/`cyan`, `secondary`/
  `gray`/`grey`, `error`/`red`, `success`/`green`, `warning`/`orange`/`amber`,
  `violet`/`purple`, `blue`, `yellow`, `white`. Unknown names fall back to defaults.
- **Icons** (`resolveIcon`) are a fixed set of names mapped to Material icons, including:
  `home`, `settings`, `search`, `add`, `delete`, `edit`, `check`/`done`, `check_circle`,
  `close`, `star`, `favorite`, `share`, `info`, `warning`, `person`, `email`, `phone`,
  `location`, `refresh`, `download`, `upload`, `code`, `build`, `lock`, `play`, `pause`,
  `stop`, `cloud`, `bolt`, `link`, `copy`, `notifications`, `music`/`music_note`,
  `image`. Unknown icon names render nothing.
- `TextNode` strips `**` markers and applies bold/italic/style; the design guidance is to
  use the style/bold/italic props rather than markdown inside `value`.

## Enabling / disabling (toggleable per profile)

Dynamic UI is a togglable capability:
- The setting is stored under the key `dynamic_ui_enabled` and read via
  `ToolStore.isDynamicUiEnabled()` (**defaults to `true`** when unset). It is written via
  `ToolStore.setDynamicUiEnabled(...)`.
- Users toggle it from the tool settings screen (`ToolToggleScreen.kt`), described as
  "Enable aiope-ui rich interactive blocks in responses".
- The system prompt only teaches the agent about `aiope-ui` when the toggle is on:
  `AiopePersona.forMode(mode, dynamicUiEnabled)` appends the `DYNAMIC_UI` prompt section
  (which lists the components and actions) only if `dynamicUiEnabled` is true. The prompt
  is built via `buildAgentPrompt(chatDao, mode, toolStore.isDynamicUiEnabled())` in
  `ChatViewModel`.

So when the toggle is off, the agent is not instructed to emit `aiope-ui` blocks; when
on, it is encouraged to use them proactively for input collection, choices, structured
info, and multi-step workflows.
