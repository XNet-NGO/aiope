package ngo.xnet.aiope.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ngo.xnet.aiope.core.designsystem.theme.AiopeTheme

@Composable
fun AiopeMain() {
  AiopeTheme {
    Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      var showSplash by remember { mutableStateOf(true) }
      if (showSplash) {
        SplashScreen { showSplash = false }
      } else {
        androidx.compose.material3.Text("AIOPE is running!")
      }
    }
  }
}
