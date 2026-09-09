package ngo.xnet.aiope.feature.chat.settings

/**
 * Bridge so in-app flows that briefly leave the app (system file/image pickers, share sheets,
 * OAuth, etc.) can tell [AuthGate] not to treat the excursion as a security "backgrounding".
 * Without this, launching the file picker re-locks the app and drops transient UI state.
 */
object AuthInterop {
  /** True while an in-app launcher/picker is active. AuthGate skips re-lock while set. */
  @Volatile
  var suppressLock: Boolean = false

  /** Timestamp of the last suppression release, to also cover the brief return window. */
  @Volatile
  var lastSuppressAt: Long = 0L

  fun begin() {
    suppressLock = true
    lastSuppressAt = System.currentTimeMillis()
  }

  fun end() {
    suppressLock = false
    lastSuppressAt = System.currentTimeMillis()
  }

  /** Whether re-lock should be suppressed right now (active, or within a short return grace). */
  fun active(returnGraceMs: Long = 4000L): Boolean =
    suppressLock || (System.currentTimeMillis() - lastSuppressAt < returnGraceMs)
}
