package ngo.xnet.aiope.feature.chat.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helper around Android's RoleManager for the ASSISTANT (default digital assistant) role.
 *
 * Requesting this role shows a system consent dialog; only the user can grant it. Holding it
 * makes AIOPE the device's assistant (assist gesture / long-press home handoff). It does NOT by
 * itself grant background-network parity — that is a separate, future platform capability.
 */
object AssistantRole {

  private fun roleManager(context: Context): RoleManager? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      context.getSystemService(RoleManager::class.java)
    } else {
      null
    }

  /** Whether the ASSISTANT role exists and can be requested on this device. */
  fun isAvailable(context: Context): Boolean {
    val rm = roleManager(context) ?: return false
    return try {
      rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    } catch (_: Exception) {
      false
    }
  }

  /** Whether AIOPE currently holds the ASSISTANT role. */
  fun isHeld(context: Context): Boolean {
    val rm = roleManager(context) ?: return false
    return try {
      rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Intent that launches the system consent dialog to request the ASSISTANT role. Returns null
   * if the role isn't available. Launch via an ActivityResultLauncher; RESULT_OK == granted.
   */
  fun requestIntent(context: Context): Intent? {
    val rm = roleManager(context) ?: return null
    if (!isAvailable(context)) return null
    return try {
      rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    } catch (_: Exception) {
      null
    }
  }
}
