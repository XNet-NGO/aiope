package ngo.xnet.aiope.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import ngo.xnet.aiope.core.navigation.AiopeScreens
import ngo.xnet.aiope.core.navigation.AppComposeNavigator
import ngo.xnet.aiope.feature.chat.ChatScreen
import ngo.xnet.aiope.feature.chat.db.ChatDao
import ngo.xnet.aiope.feature.chat.settings.ProviderStore
import ngo.xnet.aiope.feature.chat.settings.SettingsScreen
import ngo.xnet.aiope.feature.chat.settings.ToolStore
import ngo.xnet.aiope.feature.remote.ui.ServerListScreen

fun NavGraphBuilder.aiopeNavigation(composeNavigator: AppComposeNavigator, providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao) {
  composable(route = AiopeScreens.Chat.route) {
    ChatScreen(onOpenSettings = { composeNavigator.navigate(AiopeScreens.Settings.route) })
  }
  composable(route = AiopeScreens.Settings.route) {
    SettingsScreen(
      providerStore = providerStore,
      toolStore = toolStore,
      chatDao = chatDao,
      onBack = { composeNavigator.navigateUp() },
      serversContent = { onBack -> ServerListScreen(onBack = onBack) },
    )
  }
}
