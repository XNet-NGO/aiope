package ngo.xnet.aiope.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import ngo.xnet.aiope.core.navigation.AiopeScreens
import ngo.xnet.aiope.core.navigation.AppComposeNavigator
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.settings.ProviderStore
import ngo.xnet.aiope.feature.chat.settings.ToolStore

@Composable
fun AiopeNavHost(navHostController: NavHostController, composeNavigator: AppComposeNavigator, providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao) {
  NavHost(
    navController = navHostController,
    startDestination = AiopeScreens.Chat.route,
  ) {
    aiopeNavigation(composeNavigator = composeNavigator, providerStore = providerStore, toolStore = toolStore, chatDao = chatDao)
  }
}
