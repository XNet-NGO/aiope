package com.aiope2.feature.chat.location

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.ramani.compose.CameraPosition
import org.ramani.compose.MapLibre
import org.ramani.compose.Symbol

@Composable
fun LocationCard(
  latitude: Double,
  longitude: Double,
  altitude: Double? = null,
  speed: Double? = null,
  bearing: Double? = null,
  accuracy: Double? = null
) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(4.dp),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp))) {
      // Initial position only — never overridden so user can freely pan/zoom
      val initialPos = remember {
        CameraPosition(
          target = org.maplibre.android.geometry.LatLng(latitude, longitude),
          zoom = 15.5
        )
      }
      val style = remember {
        org.maplibre.android.maps.Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
      }
      MapLibre(
        modifier = Modifier.fillMaxSize(),
        styleBuilder = style,
        cameraPosition = initialPos
      ) {
        Symbol(
          center = org.maplibre.android.geometry.LatLng(latitude, longitude),
          color = "Red",
          size = 1.4f
        )
      }
    }
  }
}
