package ngo.xnet.aiope

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Produces AIOPE's assistant session (captures screen + starts headless voice). */
class AiopeVoiceInteractionSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession =
    AiopeVoiceInteractionSession(this)
}
