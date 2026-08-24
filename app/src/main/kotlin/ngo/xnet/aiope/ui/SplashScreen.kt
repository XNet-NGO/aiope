package ngo.xnet.aiope.ui

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private val Cyan = Color(0xFF00E5FF)
private val Purple = Color(0xFF7B2FBE)
private val Teal = Color(0xFF00BFA5)
private val Gold = Color(0xFFFFD54F)
private val BgColor = Color(0xFF0A0A0A)

@Composable
fun SplashScreen(onFinished: () -> Unit) {
  val transition = rememberInfiniteTransition(label = "fractal")
  val rotation = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "rot")
  val pulse = transition.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pulse")

  LaunchedEffect(Unit) {
    kotlinx.coroutines.delay(2500)
    onFinished()
  }

  Box(Modifier.fillMaxSize().background(BgColor), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(320.dp)) {
      val cx = size.width / 2f
      val cy = size.height / 2f
      val scale = size.width / 320f

      // Draw multiple fractal spiral arms
      val arms = 5
      for (arm in 0 until arms) {
        val armOffset = arm * (360f / arms)
        drawFractalSpiral(cx, cy, scale, rotation.value + armOffset, arm)
      }

      // Inner rotating fractal ring
      rotate(degrees = -rotation.value * 0.5f, pivot = Offset(cx, cy)) {
        val ringPoints = 24
        for (i in 0 until ringPoints) {
          val angle = Math.toRadians((i * 360.0 / ringPoints))
          val r = 45f * scale * pulse.value
          val px = cx + (r * cos(angle)).toFloat()
          val py = cy + (r * sin(angle)).toFloat()
          val dotSize = (3f - (i % 3)) * scale
          drawCircle(Teal.copy(alpha = 0.6f), radius = dotSize, center = Offset(px, py))
        }
      }

      // Center "J" letter
      val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 72f * scale
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
      }
      drawContext.canvas.nativeCanvas.drawText("J", cx, cy + 24f * scale, paint)
    }
  }
}

private fun DrawScope.drawFractalSpiral(cx: Float, cy: Float, scale: Float, baseAngle: Float, armIdx: Int) {
  val points = 40
  val color = when (armIdx % 5) {
    0 -> Cyan
    1 -> Purple
    2 -> Teal
    3 -> Gold
    else -> Color(0xFFE040FB)
  }

  val path = Path()
  var prevX = cx
  var prevY = cy

  for (i in 0 until points) {
    val frac = i.toFloat() / points
    // Golden spiral approximation
    val angle = Math.toRadians((baseAngle + frac * 540.0).toDouble())
    val radius = (10f + frac.pow(1.3f) * 130f) * scale
    val x = cx + (radius * cos(angle)).toFloat()
    val y = cy + (radius * sin(angle)).toFloat()

    if (i == 0) {
      path.moveTo(x, y)
    } else {
      path.lineTo(x, y)
    }

    // Fractal branches at intervals
    if (i > 5 && i % 6 == 0) {
      val branchAngle = angle + Math.PI / 3
      val branchLen = (20f - frac * 12f) * scale
      for (b in 1..3) {
        val bf = b.toFloat() / 3f
        val bx = x + (branchLen * bf * cos(branchAngle)).toFloat()
        val by = y + (branchLen * bf * sin(branchAngle)).toFloat()
        drawCircle(
          color.copy(alpha = (0.7f - frac * 0.5f).coerceAtLeast(0.1f)),
          radius = (2.5f - bf) * scale,
          center = Offset(bx, by),
        )
      }
    }

    prevX = x
    prevY = y
  }

  drawPath(
    path,
    color.copy(alpha = 0.8f),
    style = Stroke(width = 2f * scale),
  )
}
