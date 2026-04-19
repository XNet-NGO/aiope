package com.aiope2.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.aiope2.core.navigation.AiopeScreens
import com.aiope2.core.navigation.AppComposeNavigator
import com.aiope2.feature.chat.ChatScreen
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.settings.ProviderStore
import com.aiope2.feature.chat.settings.SettingsScreen
import com.aiope2.feature.chat.settings.ToolStore
import javax.inject.Inject

fun NavGraphBuilder.aiopeNavigation(composeNavigator: AppComposeNavigator, providerStore: ProviderStore, toolStore: ToolStore, chatDao: ChatDao) {
  composable(route = AiopeScreens.Chat.route) {
    ChatScreen(onOpenSettings = { composeNavigator.navigate(AiopeScreens.Settings.route) })
  }
  composable(route = AiopeScreens.Settings.route) {
    SettingsScreen(providerStore = providerStore, toolStore = toolStore, chatDao = chatDao, onBack = { composeNavigator.navigateUp() })
  }
}
