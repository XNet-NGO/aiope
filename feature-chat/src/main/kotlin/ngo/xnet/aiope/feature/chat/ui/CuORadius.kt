package ngo.xnet.aiope.feature.chat.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner-radius scale for CuO, built on a 4dp grid so every radius is a spacing increment.
 *
 * Nested surfaces follow the concentric rule — the gap between a shape and whatever it contains
 * must stay visually constant around the curve:
 *
 * ```
 * R_inner = R_outer − gap        (gap = padding between outer edge and inner edge)
 * R_outer = R_inner + gap
 * ```
 *
 * Example: screen-level card 32dp with 12dp padding → inner elements use 20dp.
 * Never reuse one radius for nested shapes; derive it. If the formula yields a radius that looks
 * wrong (tiny slivers, near-circles that should stay pill-like), adjust optically by ±2dp and say
 * so in the call site.
 */
object CuORadius {
  /** Tiny inline chips inside bubbles/cards (icon badges, mini-tags). */
  val xs: Dp = 8.dp

  /** Small controls: text fields inside cards, list rows, menu items. */
  val sm: Dp = 12.dp

  /** Standalone cards, message bubbles, sheets' inner sections. */
  val md: Dp = 16.dp

  /** Large cards / starter-prompt tiles (32dp outer − ~12dp content padding). */
  val lg: Dp = 20.dp

  /** Screen-level containers: composer capsule outer, drawer header card. */
  val xl: Dp = 28.dp

  /** Hero surfaces: full-screen panels, bottom-sheet top corners. */
  val xxl: Dp = 32.dp

  fun inner(outer: Dp, gap: Dp): Dp = (outer - gap).coerceAtLeast(xs)

  fun outer(inner: Dp, gap: Dp): Dp = inner + gap

  fun shape(r: Dp) = RoundedCornerShape(r)
}
