package ngo.xnet.aiope.ui

import android.content.Context
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
import androidx.navigation.compose.rememberNavController
import ngo.xnet.aiope.core.designsystem.theme.AiopeTheme
import ngo.xnet.aiope.core.navigation.AppComposeNavigator
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.settings.ProviderStore
import ngo.xnet.aiope.feature.chat.settings.ToolStore
import ngo.xnet.aiope.navigation.AiopeNavHost

@Composable
fun AiopeMain(composeNavigator: AppComposeNavigator, providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val prefs = androidx.compose.runtime.remember { ctx.getSharedPreferences("cuo_main", Context.MODE_PRIVATE) }
  var hasSeenWelcome by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("welcome_seen", false)) }
  ngo.xnet.aiope.feature.chat.theme.ThemeProvider {
    // Edge-to-edge (enforced on targetSdk 35+/Android 17): consume system bar insets once here
    // so every screen draws below the status bar and above the navigation bar.
    Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      // Welcome/permission setup shows once per install (until the user taps Get started);
      // it replaces the old animated splash, which only added a 2s delay.
      var showSplash by remember { mutableStateOf(!hasSeenWelcome) }
      if (showSplash) {
        SplashScreen {
          hasSeenWelcome = true
          showSplash = false
        }
      } else {
        val navHostController = rememberNavController()
        LaunchedEffect(Unit) {
          composeNavigator.handleNavigationCommands(navHostController)
        }
        AiopeNavHost(navHostController = navHostController, composeNavigator = composeNavigator, providerStore = providerStore, toolStore = toolStore, chatDao = chatDao)
      }
    }
  }
}
