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
import androidx.navigation.compose.rememberNavController
import ngo.xnet.aiope.core.navigation.AppComposeNavigator
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.settings.ProviderStore
import ngo.xnet.aiope.feature.chat.settings.ToolStore
import ngo.xnet.aiope.navigation.AiopeNavHost

@Composable
fun AiopeMain(composeNavigator: AppComposeNavigator, providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao) {
  ngo.xnet.aiope.feature.chat.theme.ThemeProvider {
    Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      var showSplash by remember { mutableStateOf(true) }
      if (showSplash) {
        SplashScreen { showSplash = false }
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
