# Kelivo Chat App — Complete UI Analysis

Reference app at: /home/xnet-admin/reference/kelivo (Flutter)

## Typography

- No custom primary font — platform defaults (SF Pro on iOS, Roboto on Android)
- Font fallbacks: PingFang SC, Heiti SC, Hiragino Sans GB, Roboto
- Windows fallbacks: Twemoji Country Flags, Segoe UI, Microsoft YaHei, SimHei
- Code font: user-configurable, default 'monospace'

### Text Sizes
| Element | Mobile | Desktop | Weight | Line Height |
|---|---|---|---|---|
| User message | 15.5 | 14.0 | normal | 1.45 |
| Assistant message | 15.7 | 14.0 | normal | 1.5 |
| Code blocks | 13 | 13 | normal | 1.5 |
| Inline code | 13 | 13 | normal | 1.4 |
| Reasoning/thinking | 12.5 | 12.5 | normal | 1.32 |
| Header name | 13 | 13 | w500 | — |
| Header timestamp | 11 | 11 | normal | — |
| AppBar title | 18 | 18 | w600 | — |
| Snackbar | 14 | 14 | w500 | — |
| Translation | 15.5 | 14.0 | normal | 1.4 |

### Heading Sizes
| Level | Size | Weight | Height | Letter Spacing (non-CJK) |
|---|---|---|---|---|
| H1 | 24 | w700 | 1.25 | 0.1 |
| H2 | 20 | w600 | 1.3 | 0.08 |
| H3 | 18 | w600 | 1.35 | 0.05 |
| H4 | 16 | w500 | 1.35 | 0.05 |
| H5 | 15 | w500 | 1.35 | 0.05 |
| H6 | 14 | w500 | 1.35 | 0.05 |

### Base Markdown Text
- fontSize: 15.5
- height: 1.55
- letterSpacing: 0.05 (English), 0.0 (Chinese)

---

## Bubble Layout

### User Messages
- Alignment: right (CrossAxisAlignment.end)
- Max width: screenWidth * 0.75
- Outer padding: horizontal 16, vertical 12
- Inner padding: 12 all sides
- Background: primary.alpha(0.15 dark / 0.08 light)
- Border radius: 16
- Avatar: 32x32 circle, primary.alpha(0.1) bg

### Assistant Messages
- Alignment: left (CrossAxisAlignment.start)
- Width: full (double.infinity)
- Outer padding: horizontal 20, vertical 12
- Inner padding: 12 all sides
- Background: NONE in default style (bare content)
- Border radius: 16 (when styled)
- Avatar: 32x32 circle, primary.alpha(0.1) or secondary.alpha(0.1)

### 3 Bubble Styles (ChatMessageBackgroundStyle)
1. Default: User gets tinted container, assistant gets nothing
2. Frosted: BackdropFilter blur(14,14), dark Color(0xFF1C1C1E).alpha(0.66) / light white.alpha(0.66), border outlineVariant.alpha(0.14) width 0.8
3. Solid: dark Color(0xFF1C1C1E) / light white, border outlineVariant.alpha(0.16) width 0.8

### Spacing
- Header to content: 8px
- Content to actions: 8px
- Between reasoning and content: 8px
- Between tool cards: 8px bottom
- Between images: Wrap spacing 8, runSpacing 8
- Between action buttons: 6px
- Action button size: 28x28, icon 16px with padding 4

---

## Animations

### Duration Tiers
- kAnimFast: 180ms
- kAnim: 240ms (default)
- kAnimSlow: 320ms

### Dominant Curve: Curves.easeOutCubic (~70% of all animations)

### Loading Indicator (3-dot pulsing)
- 3 dots, each 9x9 circle
- Spacing: 6px between dots
- AnimationController: 1100ms, repeating
- Sine wave with phase offset per dot (index * 0.22)
- Scale: 0.85 + 0.15 * wave
- Opacity: 0.45 + 0.45 * wave
- Color: colorScheme.primary

### Streaming Content
- AnimatedSize wrapper: 260ms, easeOutCubic, alignment topLeft
- Respects reduce-motion / accessibleNavigation settings
- ValueNotifier-based partial rebuilds (only streaming message rebuilds)

### Shimmer Effect (on "Deep Thinking" label)
- Custom ShaderMask with moving LinearGradient
- Colors: transparent → white@0.35 → transparent
- Stops: [0.0, 0.5, 1.0]
- Gradient width: 40% of widget
- Duration: 1200ms, repeating
- BlendMode: srcATop

### Reasoning Section
- Collapse: AnimatedSize 300ms, Cubic(0.2, 0.8, 0.2, 1)
- Chevron rotation: AnimatedRotation 0→0.25 turns, 220ms easeInOutCubic
- When loading + collapsed: max height 80, ShaderMask fade (top 12px, bottom 28px)

### Code Block Collapse
- AnimatedSwitcher 220ms with FadeTransition + SizeTransition
- Chevron: AnimatedRotation 180ms easeOutCubic

### Snackbar
- Entrance: 300ms, SlideTransition from Offset(0,-1) + FadeTransition
- Slide curve: easeOutCubic, Fade curve: easeOut
- Max 3 visible, stacked with 8px offset, scale 1.0-(index*0.03)

### Image Viewer
- Hero transition with tag 'img:$path'
- Forward: 360ms, Reverse: 280ms
- CurvedAnimation: easeOutCubic / easeInCubic
- FadeTransition + SlideTransition Offset(0, 0.02)

### All Duration Values Used
80ms, 100ms, 110ms, 120ms, 140ms, 150ms, 160ms, 180ms, 200ms, 220ms, 240ms, 260ms, 280ms, 300ms, 320ms, 360ms, 900ms, 1100ms, 1200ms

---

## Markdown Rendering

### Package: gpt_markdown ^1.1.4

### Code Blocks (_CollapsibleCodeBlock)
- Container: borderRadius 12, clipBehavior antiAlias
- Border: outlineVariant.alpha(0.2) via foregroundDecoration
- Body bg: Color.alphaBlend(primary.alpha(isDark ? 0.06 : 0.03), surface)
- Header bg: Color.alphaBlend(primary.alpha(isDark ? 0.16 : 0.10), surface)
- Header bottom border: outlineVariant.alpha(0.28), width 1.0
- Body padding: EdgeInsets.fromLTRB(10, 6, 6, 10)
- Margin: vertical 6
- Code text: monospace, fontSize 13, height 1.5
- Language label: fontSize 13, w700
- Copy button: icon 14 + label fontSize 12, w600, alpha 0.6
- Syntax themes: githubTheme (light), atomOneDarkReasonableTheme (dark)
- Auto-collapse: configurable threshold (default 2 lines)
- Mobile: horizontal scroll default, optional word wrap
- Desktop: word wrap, selectable

### Inline Code
- Background: dark Colors.white12, light Color(0xFFF1F3F5)
- Padding: horizontal 6, vertical 2
- Border: outlineVariant.alpha(0.22)
- BorderRadius: 6
- Font: codeFontFamily, fontSize 13, height 1.4

### Tables
- Border: outlineVariant.alpha(isDark ? 0.22 : 0.28)
- Header bg: Color.alphaBlend(primary.alpha(isDark ? 0.14 : 0.08), surface)
- Header text: w600
- Cell padding: horizontal 10, vertical 8
- Container: borderRadius 12, border width 0.8
- Internal borders: width 0.5
- Mobile: IntrinsicColumnWidth, horizontal scroll
- Desktop: FlexColumnWidth, SelectableText.rich

### Block Quotes (ModernBlockQuote)
- Background: primaryContainer.alpha(isDark ? 0.18 : 0.12)
- Left border: primary.alpha(isDark ? 0.90 : 0.80), width 3
- BorderRadius: 12
- Padding: fromLTRB(10, 8, 10, 8)

### Horizontal Rules
- Color: outlineVariant.alpha(0.4), Height: 1px, Padding: vertical 10

### LaTeX
- Package: flutter_math_fork ^0.7.4
- Block: centered, horizontal scroll
- Inline: font size 1.2x base

### Mermaid
- WebView rendering (mobile/macOS/Windows)
- 29 Material ColorScheme → Mermaid theme variables
- Height cache: LRU 200, Image cache: LRU 120

### Links
- Color: primary, no underline
- Citations: 20x20 circle badge, primary.alpha(0.20), fontSize 12

---

## Copy & Selection

### Action Buttons (below each message)
- Copy, Regenerate, Speak, Translate, More, Branch, TokenDisplay
- Each: 28x28 SizedBox, icon 16px with padding 4

### Mobile Copy Solution
- "Select & Copy" bottom sheet (DraggableScrollableSheet initial 0.8, max 0.9, min 0.4)
- Header: title fontSize 18 w600 + "Copy All" button
- Body: SelectionArea wrapping Text(message.content, fontSize: 15, height: 1.5)
- Full native text selection — SEPARATE from markdown view

### Desktop Copy
- SelectionArea wrapping markdown content directly

### Context Menus
- Mobile: long-press → frosted glass popup (BackdropFilter blur 14), 150ms fade-in
- Desktop: right-click → frosted glass, item height 44, icon 18, text 14.5

---

## Color Palettes (9 total)

### Default Palette
- Light: surface #F7F7F7, primary #4D5C92, onSurface #202020
- Dark: surface #121213, primary #B6C4FF, onSurface #F9F9F9

### Color Patterns
- Primary alpha: 0.08 (light bg), 0.10 (avatar), 0.15 (dark bg), 0.45 (focus border)
- onSurface alpha: 0.5 (secondary), 0.7 (labels), 0.8 (body), 0.9 (icons)
- Card bg: dark white10/white12, light #F2F3F5/#F7F7F9

---

## Design Tokens

### Spacing: xxs:4, xs:8, sm:12, md:16, lg:20
### Radii: 6(inline code), 8(images), 10(forms), 12(code/tables/cards), 14(snackbar), 16(dialogs/bubbles), 28(capsule), 999(pills)
### Shadow: soft = black@5%, blur 18, offset (0,6)
### Breakpoints: mobile<600, tablet:900, desktop:1200, wide:1600

---

## Reasoning/Thinking Section
- Background: primaryContainer.alpha(0.25 dark / 0.30 light)
- BorderRadius: 16, Padding: h10 v8
- Header: SVG 18x18 + label 13sp w700 + elapsed time + shimmer
- Collapsible AnimatedSize 300ms
- Content: 12.5sp, height 1.32
- Loading collapsed: max height 80, gradient fade mask

## Tool Call Cards
- BorderRadius: 16, Padding: fromLTRB(16,12,12,12)
- Background: primaryContainer.alpha(0.25/0.30)
- Title: 13sp w700, Icon: 18x18

## Platform Adaptations
- Desktop: right-click, hover, font 14.0, MouseRegion
- Mobile: long-press, bottom sheets, font 15.5-15.7, haptics
- iOS: no ripple, color-tween press (200ms, 35% shift), haptic feedback
