package ngo.xnet.aiope.core.preferences

/**
 * Bridge so in-app flows that briefly leave the app (system file/image pickers, share sheets,
 * OAuth, etc.) can tell the app-lock gate not to treat the excursion as a security "backgrounding".
 * Without this, launching a picker re-locks the app and drops transient UI state.
 *
 * Lives in core-preferences so any feature module (chat, remote, …) can signal it.
 */
object AuthInterop {
  @Volatile
  var suppressLock: Boolean = false

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
