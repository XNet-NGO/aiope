package ngo.xnet.aiope.core.preferences

/**
 * Carries assist-invocation context from the Activity layer to the chat layer.
 *
 * When AIOPE is invoked as the device assistant (assist gesture / long-press home) over another
 * app, Android can provide a snapshot of the foreground app's visible content. We capture that
 * text here so the chat/voice layer can use it to enrich the user's next prompt. This is
 * per-invocation, user-triggered context — not continuous monitoring.
 */
object AssistBridge {
  /** Screen text captured at the last assist invocation (may be blank). */
  @Volatile
  var pendingContext: String? = null

  /** Set when the assistant was invoked in a way that should auto-start voice. */
  @Volatile
  var startVoice: Boolean = false

  /** Consume and clear the pending assist context. */
  fun takeContext(): String? {
    val c = pendingContext
    pendingContext = null
    return c
  }

  fun takeStartVoice(): Boolean {
    val v = startVoice
    startVoice = false
    return v
  }
}
