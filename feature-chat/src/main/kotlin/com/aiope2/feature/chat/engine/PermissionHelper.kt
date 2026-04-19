package com.aiope2.feature.chat.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PermissionHelper {
  private var latch: CountDownLatch? = null
  private var granted = false

  fun hasPermission(ctx: Context, permission: String): Boolean = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

  /** Check + request permission, blocking until user responds. Call from background thread only. */
  fun ensurePermission(ctx: Context, vararg permissions: String): Boolean {
    if (permissions.all { hasPermission(ctx, it) }) return true
    latch = CountDownLatch(1)
    granted = false
    val intent = Intent(ctx, PermissionRequestActivity::class.java).apply {
      putExtra("permissions", permissions)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
    latch?.await(30, TimeUnit.SECONDS)
    return granted
  }

  internal fun onResult(allGranted: Boolean) {
    granted = allGranted
    latch?.countDown()
  }
}

class PermissionRequestActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val perms = intent.getStringArrayExtra("permissions") ?: run {
      finish()
      return
    }
    requestPermissions(perms, 1)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    PermissionHelper.onResult(grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    finish()
  }
}
