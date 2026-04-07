package com.aiope2.ui

import androidx.compose.runtime.*
import androidx.navigation.compose.rememberNavController
import com.aiope2.core.designsystem.theme.AiopeTheme
import com.aiope2.core.navigation.AppComposeNavigator
import com.aiope2.feature.chat.settings.ProviderStore
import com.aiope2.navigation.AiopeNavHost

@Composable
fun AiopeMain(composeNavigator: AppComposeNavigator, providerStore: ProviderStore) {
  AiopeTheme {
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
      SplashScreen { showSplash = false }
    } else {
      val navHostController = rememberNavController()
      LaunchedEffect(Unit) {
        composeNavigator.handleNavigationCommands(navHostController)
      }
      AiopeNavHost(navHostController = navHostController, composeNavigator = composeNavigator, providerStore = providerStore)
    }
  }
}
