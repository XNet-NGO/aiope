package com.aiope2.feature.chat.location

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.ramani.compose.CameraPosition
import org.ramani.compose.MapLibre
import org.ramani.compose.Symbol
import org.ramani.compose.UiSettings

@Composable
fun LocationCard(latitude: Double, longitude: Double, altitude: Double? = null, speed: Double? = null, bearing: Double? = null, accuracy: Double? = null) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(4.dp),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    // AndroidView wrapper that steals touch from parent LazyColumn at the View level
    AndroidView(
      modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)),
      factory = { ctx ->
        @SuppressLint("ClickableViewAccessibility")
        val frame = object : FrameLayout(ctx) {
          override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // Always tell parent to not intercept while we're touched
            parent?.requestDisallowInterceptTouchEvent(true)
            return false
          }
        }
        val composeView = ComposeView(ctx).apply {
          setContent {
            MapContent(latitude, longitude)
          }
        }
        frame.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame
      },
    )
  }
}

@Composable
private fun MapContent(latitude: Double, longitude: Double) {
  val initialPos = remember(latitude, longitude) {
    CameraPosition(target = org.maplibre.android.geometry.LatLng(latitude, longitude), zoom = 15.5)
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
      isAttributionEnabled = false,
    )
  }
  MapLibre(
    modifier = Modifier.fillMaxSize(),
    styleBuilder = style,
    cameraPosition = initialPos,
    uiSettings = ui,
  ) {
    Symbol(center = org.maplibre.android.geometry.LatLng(latitude, longitude), color = "Red", size = 1.4f)
  }
}
