package com.aiope2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.aiope2.ui.AiopeMain
import com.aiope2.core.navigation.AppComposeNavigator
import com.aiope2.feature.chat.settings.ProviderStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  @Inject lateinit var composeNavigator: AppComposeNavigator
  @Inject lateinit var providerStore: ProviderStore

  private val runtimePermissions = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
  }.toTypedArray()

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { results ->
    // After runtime permissions, request All Files Access if needed
    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
      try {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
          Uri.parse("package:$packageName")))
      } catch (_: Exception) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Request permissions on first launch
    val needed = runtimePermissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isNotEmpty()) {
      permissionLauncher.launch(needed.toTypedArray())
    } else if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
      try {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
          Uri.parse("package:$packageName")))
      } catch (_: Exception) {}
    }

    setContent { AiopeMain(composeNavigator = composeNavigator, providerStore = providerStore) }
  }
}
