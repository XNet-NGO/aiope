package ngo.xnet.aiope.core.auth

import android.content.Context
import ngo.xnet.aiope.core.preferences.Preferences

/**
 * Persists which optional factors the user has enabled, plus the stable account id.
 *
 * The account id is seeded from the existing anonymous [Preferences.userUUID] so that any
 * data already associated with the device carries over when auth factors are introduced.
 */
class AuthSettings(context: Context, private val preferences: Preferences) {

  private val prefs = context.getSharedPreferences("aiope_auth", Context.MODE_PRIVATE)

  fun accountId(): String {
    val existing = prefs.getString(KEY_ACCOUNT_ID, null)
    if (existing != null) return existing
    // Migrate from the legacy anonymous identifier so associations are preserved.
    val migrated = preferences.userUUID
    prefs.edit().putString(KEY_ACCOUNT_ID, migrated).apply()
    return migrated
  }

  fun enabledFactors(): Set<AuthFactor> =
    // Defensive copy: getStringSet returns a live/cached instance that must not be reused.
    HashSet(prefs.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet())
      .mapNotNull { AuthFactor.from(it) }
      .toSet()

  fun setEnabled(factor: AuthFactor, enabled: Boolean) {
    val current = HashSet(prefs.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet())
    if (enabled) current.add(factor.id) else current.remove(factor.id)
    // Write a brand-new set instance so SharedPreferences reliably detects the change,
    // and use commit() so the value is durable before we re-read it in refresh().
    prefs.edit().putStringSet(KEY_ENABLED, HashSet(current)).commit()
  }

  /** Whether the app should require an enrolled factor on launch/return to foreground. */
  fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)

  fun setAppLockEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
  }

  companion object {
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_ENABLED = "enabled_factors"
    private const val KEY_APP_LOCK = "app_lock_enabled"
  }
}
