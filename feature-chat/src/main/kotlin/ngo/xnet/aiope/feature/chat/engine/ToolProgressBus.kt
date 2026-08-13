package ngo.xnet.aiope.feature.chat.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton event bus for reporting tool execution progress to the UI.
 * Long-running tools (shell, SSH, fetch) emit progress updates here;
 * MessageBubble observes and shows a progress indicator.
 */
data class ToolProgressEvent(
  val toolName: String,
  val progress: Float = -1f, // -1 = indeterminate, 0.0-1.0 = determinate
  val message: String = "",
)

object ToolProgressBus {
  private val _progress = MutableStateFlow<ToolProgressEvent?>(null)
  val progress: StateFlow<ToolProgressEvent?> = _progress.asStateFlow()

  fun update(toolName: String, progress: Float = -1f, message: String = "") {
    _progress.value = ToolProgressEvent(toolName, progress, message)
  }

  fun clear() {
    _progress.value = null
  }
}
