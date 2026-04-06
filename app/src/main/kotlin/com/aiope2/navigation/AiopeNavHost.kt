package com.aiope2.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.aiope2.core.navigation.AppComposeNavigator
import com.aiope2.core.navigation.AiopeScreens
import com.aiope2.feature.chat.settings.ProviderStore

@Composable
fun AiopeNavHost(
  navHostController: NavHostController,
  composeNavigator: AppComposeNavigator,
  providerStore: ProviderStore
) {
  NavHost(
    navController = navHostController,
    startDestination = AiopeScreens.Chat.route
  ) {
    aiopeNavigation(composeNavigator = composeNavigator, providerStore = providerStore)
  }
}
