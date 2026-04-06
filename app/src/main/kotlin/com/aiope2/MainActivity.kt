package com.aiope2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aiope2.ui.AiopeMain
import com.aiope2.core.navigation.AppComposeNavigator
import com.aiope2.feature.chat.settings.ProviderStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  @Inject lateinit var composeNavigator: AppComposeNavigator
  @Inject lateinit var providerStore: ProviderStore

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { AiopeMain(composeNavigator = composeNavigator, providerStore = providerStore) }
  }
}
