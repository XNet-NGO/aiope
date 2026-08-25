package ngo.xnet.aiope.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ngo.xnet.aiope.core.designsystem.R

/**
 * CuO type system.
 *
 * The stock theme used the platform default at every size, which is why the UI read as
 * "system settings screen" rather than a product. Inter (variable, bundled) gives a
 * geometric, tight-set UI face; the monospace face is kept for anything the agent
 * produces mechanically — token counts, ids, timestamps — so data reads as data.
 *
 * Scale is deliberately compressed at the top (no 45sp/57sp display sizes on a phone
 * harness) and weights carry the hierarchy instead of size alone.
 */
private fun interFont(weight: FontWeight) = Font(
  R.font.inter_variable,
  weight,
  // InterVariable exposes a `wght` axis. Registering the same file five times with only a
  // FontWeight tag makes every entry resolve to the file's default instance, so declared weights
  // silently collapse into one — the wght variation must be set explicitly per entry.
  variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private val Inter = FontFamily(
  interFont(FontWeight.Light),
  interFont(FontWeight.Normal),
  interFont(FontWeight.Medium),
  interFont(FontWeight.SemiBold),
  interFont(FontWeight.Bold),
)

/** Exposed so screens can opt into the mono face for machine output. */
val CuoMono: FontFamily = FontFamily.Monospace

val Typography = Typography(
  displayLarge = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.8).sp,
  ),
  displayMedium = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.6).sp,
  ),
  displaySmall = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.4).sp,
  ),
  headlineLarge = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = (-0.4).sp,
  ),
  headlineMedium = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.3).sp,
  ),
  headlineSmall = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.2).sp,
  ),
  titleLarge = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.2).sp,
  ),
  titleMedium = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 21.sp,
    letterSpacing = (-0.1).sp,
  ),
  titleSmall = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 23.sp,
    letterSpacing = 0.sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.sp,
  ),
  bodySmall = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 12.5.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.1.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
  ),
  labelMedium = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
  ),
  // Machine output: ids, token counts, timestamps.
  labelSmall = TextStyle(
    fontFamily = CuoMono,
    fontWeight = FontWeight.Medium,
    fontSize = 10.5.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.3.sp,
  ),
)
