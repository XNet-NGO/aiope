package ngo.xnet.aiope.feature.chat.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.graphics.Color as AndroidColor

/**
 * Custom color picker.
 * - Hue square: X = hue (0..360), Y = brightness (top = 100%, bottom = 0%)
 * - Saturation slider, Brightness slider
 * - Hex input + live preview
 */
@Composable
fun ColorPickerDialog(
  initialColor: Int,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  // Convert initial ARGB to HSV
  val initHsv = FloatArray(3)
  AndroidColor.colorToHSV(initialColor, initHsv)
  var hue by remember { mutableFloatStateOf(initHsv[0]) }
  var sat by remember { mutableFloatStateOf(initHsv[1]) }
  var value by remember { mutableFloatStateOf(initHsv[2]) }
  var hex by remember { mutableStateOf("%06X".format(initialColor and 0xFFFFFF)) }

  fun syncFromHsv() {
    val c = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    hex = "%06X".format(c and 0xFFFFFF)
  }

  val current = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))

  fun applyHex(raw: String) {
    val cleaned = raw.trim().removePrefix("#").uppercase()
    if (cleaned.length != 6 || !cleaned.all { it.isDigit() || it in 'A'..'F' }) return
    // Build a full ARGB int (alpha = FF) so colorToHSV always sees valid channels
    val argb = (0xFF000000L or cleaned.toLong(16)).toInt()
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb, hsv)
    hue = hsv[0]
    sat = hsv[1]
    value = hsv[2]
    hex = cleaned
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Custom Color") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Live preview
        Box(
          Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
            .background(current).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        )

        // Hue square: X = hue, Y = brightness
        HueSquare(hue = hue, brightness = value, onChange = { h, b ->
          hue = h
          value = b
          syncFromHsv()
        })

        // Saturation slider
        Text("Saturation: ${(sat * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = sat, onValueChange = {
          sat = it
          syncFromHsv()
        })

        // Brightness slider
        Text("Brightness: ${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = {
          value = it
          syncFromHsv()
        })

        // Hex input
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = hex,
            onValueChange = {
              hex = it.uppercase()
              applyHex(hex)
            },
            singleLine = true,
            label = { Text("Hex") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.width(140.dp),
          )
          Text(
            "RGB: ${(current.red * 255f).toInt()}, ${(current.green * 255f).toInt()}, ${(current.blue * 255f).toInt()}",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onPick(current.toArgb()) }) { Text("Done") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
private fun HueSquare(
  hue: Float,
  brightness: Float,
  onChange: (hue: Float, brightness: Float) -> Unit,
) {
  val hueBrush = remember {
    Brush.horizontalGradient(
      listOf(
        Color(AndroidColor.HSVToColor(floatArrayOf(0f, 1f, 1f))),
        Color(AndroidColor.HSVToColor(floatArrayOf(60f, 1f, 1f))),
        Color(AndroidColor.HSVToColor(floatArrayOf(120f, 1f, 1f))),
        Color(AndroidColor.HSVToColor(floatArrayOf(180f, 1f, 1f))),
        Color(AndroidColor.HSVToColor(floatArrayOf(240f, 1f, 1f))),
        Color(AndroidColor.HSVToColor(floatArrayOf(300f, 1f, 1f))),
        Color(AndroidColor.HSVToColor(floatArrayOf(360f, 1f, 1f))),
      ),
    )
  }

  Box(
    Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp))
      .background(hueBrush)
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
      .pointerInput(hue, brightness) {
        detectDragGestures { change, _ ->
          change.consume()
          val x = (change.position.x / size.width).coerceIn(0f, 1f)
          val y = (change.position.y / size.height).coerceIn(0f, 1f)
          onChange(x * 360f, 1f - y)
        }
      }
      .pointerInput(hue, brightness) {
        detectTapGestures { offset ->
          val x = (offset.x / size.width).coerceIn(0f, 1f)
          val y = (offset.y / size.height).coerceIn(0f, 1f)
          onChange(x * 360f, 1f - y)
        }
      },
  ) {
    // Brightness overlay (transparent at top -> black at bottom)
    Canvas(Modifier.fillMaxSize()) {
      drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
    }
    // Current marker
    val markerX = (hue / 360f).coerceIn(0f, 1f)
    val markerY = (1f - brightness).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
      val cx = size.width * markerX
      val cy = size.height * markerY
      drawCircle(Color.White, radius = 8f, center = Offset(cx, cy))
      drawCircle(Color.Black, radius = 8f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
  }
}
