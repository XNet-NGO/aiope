package ngo.xnet.aiope.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import ngo.xnet.aiope.core.designsystem.theme.AiopeTheme
import ngo.xnet.aiope.core.navigation.AppComposeNavigator
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.settings.ProviderStore
import ngo.xnet.aiope.feature.chat.settings.ToolStore
import ngo.xnet.aiope.navigation.AiopeNavHost

@Composable
fun AiopeMain(composeNavigator: AppComposeNavigator, providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao) {
  ngo.xnet.aiope.feature.chat.theme.ThemeProvider {
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
