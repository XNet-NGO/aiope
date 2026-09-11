package ngo.xnet.aiope

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dagger.hilt.android.EntryPointAccessors

/**
 * The assistant session. Triggered by the assist gesture / long-press home when AIOPE holds the
 * assistant role. It captures the foreground app's visible content (AssistStructure) to enrich the
 * prompt, then starts the live voice session HEADLESSLY via the shared VoiceSessionController —
 * without bringing AIOPE's UI to the foreground.
 */
class AiopeVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

  private fun controller() =
    EntryPointAccessors.fromApplication(
      context.applicationContext,
      AssistantOverlayService.VoiceOverlayEntryPoint::class.java,
    ).voiceController()

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    try {
      val text = StringBuilder()
      state.assistStructure?.let { extractText(it, text) }
      state.assistContent?.let { c ->
        c.structuredData?.let { text.append('\n').append(it) }
        c.webUri?.let { text.append("\nURL: ").append(it) }
      }
      val screen = text.toString().trim()
      android.util.Log.i("AiopeAssist", "onHandleAssist: capturedScreenChars=${screen.length}")
      if (screen.isNotBlank()) controller().pendingScreenContext = screen.take(4000)
    } catch (e: Exception) {
      android.util.Log.e("AiopeAssist", "onHandleAssist failed: ${e.message}", e)
    }
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    android.util.Log.i("AiopeAssist", "onShow: starting headless voice (showFlags=$showFlags)")
    try { controller().start() } catch (e: Exception) {
      android.util.Log.e("AiopeAssist", "onShow start failed: ${e.message}", e)
    }
    hide()
  }

  private fun extractText(structure: AssistStructure, out: StringBuilder) {
    val n = structure.windowNodeCount
    for (i in 0 until n) {
      walk(structure.getWindowNodeAt(i).rootViewNode, out)
    }
  }

  private fun walk(node: AssistStructure.ViewNode?, out: StringBuilder) {
    node ?: return
    node.text?.let { if (it.isNotBlank()) out.append(it).append(' ') }
    node.contentDescription?.let { if (it.isNotBlank()) out.append(it).append(' ') }
    for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
  }
}
