package com.aiope2.feature.chat.theme

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.rememberAsyncImagePainter

@Composable
fun ChatBackground(theme: ThemeState, modifier: Modifier = Modifier) {
  if (!theme.useBackground || theme.backgroundUri.isNullOrBlank()) return

  // graphicsLayer for rotation — applied identically to image and video
  val rotMod = Modifier.fillMaxSize().graphicsLayer {
    rotationZ = theme.videoRotation.toFloat()
    if (theme.videoRotation == 90 || theme.videoRotation == 270) {
      val scale = maxOf(size.width / size.height, size.height / size.width)
      scaleX = scale
      scaleY = scale
    }
  }

  Box(modifier.fillMaxSize()) {
    if (theme.backgroundMediaType == "video") {
      VideoBackground(
        uri = theme.backgroundUri,
        opacity = theme.backgroundOpacity,
        muted = theme.videoMuted,
        loop = theme.videoLoop,
        modifier = rotMod,
      )
    } else {
      Image(
        painter = rememberAsyncImagePainter(Uri.parse(theme.backgroundUri)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = rotMod.alpha(theme.backgroundOpacity),
      )
    }
  }
}

@Composable
private fun VideoBackground(uri: String, opacity: Float, muted: Boolean, loop: Boolean, modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val player = remember {
    ExoPlayer.Builder(ctx)
      .setLoadControl(
        DefaultLoadControl.Builder()
          .setBufferDurationsMs(5000, 10000, 500, 1000)
          .setTargetBufferBytes(5 * 1024 * 1024)
          .build(),
      )
      .build().apply {
        repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        volume = if (muted) 0f else 1f
        playWhenReady = true
        setMediaItem(MediaItem.fromUri(uri))
        prepare()
      }
  }

  DisposableEffect(Unit) {
    onDispose {
      player.stop()
      player.release()
    }
  }

  AndroidView(
    factory = { context ->
      PlayerView(context).apply {
        this.player = player
        useController = false
        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Crop-fill: no stretch, fills entire view, clips overflow
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      }
    },
    modifier = modifier.alpha(opacity),
  )
}
