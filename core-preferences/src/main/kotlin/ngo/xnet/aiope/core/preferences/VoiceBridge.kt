package ngo.xnet.aiope.core.preferences

/**
 * Cross-layer bridge for the realtime voice session state and control requests, so surfaces that
 * live outside the chat ViewModel (e.g. the floating overlay button, the assistant session) can
 * observe whether voice is active and request start/stop without owning the engine.
 *
 * The ChatViewModel is the single owner: it publishes [isActive] and consumes [requestToggle].
 */
object VoiceBridge {
  /** True while a realtime voice session is active. Updated by the ViewModel. */
  @Volatile
  var isActive: Boolean = false

  /** Set by an external surface (overlay/assist) to request the ViewModel toggle voice. */
  @Volatile
  var requestToggle: Boolean = false

  fun takeToggleRequest(): Boolean {
    val v = requestToggle
    requestToggle = false
    return v
  }
}
