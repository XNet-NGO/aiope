package com.aiope2.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val Purple = Color(0xFF7B2FBE)
private val PurpleLight = Color(0xFF9B59B6)
private val PurpleDark = Color(0xFF5B1F9E)
private val BgColor = Color(0xFF121212)

@Composable
fun SplashScreen(onFinished: () -> Unit) {
  val rotation = rememberInfiniteTransition(label = "hex")
    .animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rot")

  // Auto-dismiss after 2s
  LaunchedEffect(Unit) {
    kotlinx.coroutines.delay(2000)
    onFinished()
  }

  Box(Modifier.fillMaxSize().background(BgColor), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(280.dp)) {
      val cx = size.width / 2f
      val cy = size.height / 2f
      val scale = size.width / 280f  // scale factor for density
      val hexCount = 18
      val hexSize = 28f * scale
      val spiralSpacing = 42f * scale

      for (i in 0 until hexCount) {
        val frac = i.toFloat() / hexCount
        val angle = Math.toRadians((frac * 720 + rotation.value).toDouble())
        val radius = spiralSpacing + i * spiralSpacing * 0.35f
        val hx = cx + (radius * cos(angle)).toFloat()
        val hy = cy + (radius * sin(angle)).toFloat()

        val alpha = (1f - frac * 0.6f).coerceIn(0.15f, 1f)
        val color = when (i % 3) { 0 -> Purple; 1 -> PurpleLight; else -> PurpleDark }

        val path = Path()
        for (j in 0..5) {
          val a = Math.toRadians(60.0 * j - 30)
          val s = hexSize - frac * 8f
          val px = hx + (s * cos(a)).toFloat()
          val py = hy + (s * sin(a)).toFloat()
          if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(path, color.copy(alpha = alpha), style = Stroke(width = (3f - frac * 1.5f).coerceAtLeast(1f)))
      }

      // Center hex icon (matches app icon)
      val iconSize = 52f * scale
      fun hexPath(ccx: Float, ccy: Float, s: Float): Path {
        val p = Path()
        for (j in 0..5) {
          val a = Math.toRadians(60.0 * j - 30)
          val px = ccx + (s * cos(a)).toFloat()
          val py = ccy + (s * sin(a)).toFloat()
          if (j == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        return p
      }
      drawPath(hexPath(cx, cy, iconSize), Color.White)
      drawPath(hexPath(cx, cy, iconSize * 0.78f), Color(0xFF6200EE))
      drawPath(hexPath(cx, cy, iconSize * 0.38f), Color(0xFF03DAC5))
    }
  }
}
