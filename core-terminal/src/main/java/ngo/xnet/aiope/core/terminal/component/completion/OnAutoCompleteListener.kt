package ngo.xnet.aiope.core.terminal.component.completion

interface OnAutoCompleteListener {
  fun onCompletionRequired(newText: String?)
  fun onKeyCode(keyCode: Int, keyMod: Int)
  fun onCleanUp()
  fun onFinishCompletion(): Boolean
}
