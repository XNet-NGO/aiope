package ngo.xnet.aiope.feature.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass surfaces for the chat shell (top bar, composer, drawer, floating chips).
 *
 * Compose has no backdrop-blur primitive: `Modifier.blur` blurs the composable's own content, not
 * what sits behind it, so a real frosted pane would need a RenderNode capture of the backdrop. The
 * effect that reads as "frosted" without that machinery is a translucent tint plus a top-lit
 * gradient and a hairline border, over whatever background (image/video/solid) the theme draws.
 * That is what [GlassSurface] does, and it stays legible on light and dark themes because the tint
 * is derived from the current colour scheme rather than hardcoded.
 *
 * Use it for chrome that floats over content. Do not wrap message bubbles in it — text over a
 * translucent pane over a photo background is exactly the contrast trap the UI guidelines warn
 * about.
 */
@Composable
fun GlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(CuORadius.xl),
  tintAlpha: Float = 0.72f,
  borderAlpha: Float = 0.35f,
  content: @Composable BoxScope.() -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  val isLight = cs.background.luminance() > 0.5f
  // Sheen: a light top edge fading out, which is what sells "pane of glass".
  val sheen = if (isLight) Color.White else Color.White.copy(alpha = 0.06f)
  Box(
    modifier
      .clip(shape)
      .background(cs.surfaceContainer.copy(alpha = tintAlpha))
      .background(
        Brush.verticalGradient(
          0f to sheen.copy(alpha = if (isLight) 0.55f else 0.08f),
          0.45f to Color.Transparent,
        ),
      )
      .glassBorder(shape, cs.outlineVariant.copy(alpha = borderAlpha)),
    content = content,
  )
}

/** Hairline border drawn on top of the fill, so the gradient doesn't wash it out. */
private fun Modifier.glassBorder(shape: Shape, color: Color): Modifier = this.border(BorderStroke(0.7.dp, color), shape)
