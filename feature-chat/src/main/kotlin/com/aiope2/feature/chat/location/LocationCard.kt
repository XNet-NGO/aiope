package com.aiope2.feature.chat.location

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.ramani.compose.CameraPosition
import org.ramani.compose.MapLibre
import org.ramani.compose.Symbol
import org.ramani.compose.UiSettings

@Composable
fun LocationCard(
  latitude: Double,
  longitude: Double,
  altitude: Double? = null,
  speed: Double? = null,
  bearing: Double? = null,
  accuracy: Double? = null
) {
  // Consume ALL scroll and fling so parent LazyColumn never steals from the map
  val consumeAll = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource) = available
      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
      override suspend fun onPreFling(available: Velocity) = available
      override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
    }
  }
  Card(
    modifier = Modifier.fillMaxWidth().padding(4.dp),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
  ) {
    Box(modifier = Modifier
      .fillMaxWidth()
      .height(260.dp)
      .clip(RoundedCornerShape(8.dp))
      .nestedScroll(consumeAll)
    ) {
      val initialPos = remember {
        CameraPosition(
          target = org.maplibre.android.geometry.LatLng(latitude, longitude),
          zoom = 15.5
        )
      }
      val style = remember {
        org.maplibre.android.maps.Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
      }
      val ui = remember {
        UiSettings(
          scrollGesturesEnabled = true,
          zoomGesturesEnabled = true,
          rotateGesturesEnabled = false,
          tiltGesturesEnabled = false,
          doubleTapGesturesEnabled = true,
          quickZoomGesturesEnabled = true,
          isLogoEnabled = false,
          isAttributionEnabled = false
        )
      }
      MapLibre(
        modifier = Modifier.fillMaxSize(),
        styleBuilder = style,
        cameraPosition = initialPos,
        uiSettings = ui
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
