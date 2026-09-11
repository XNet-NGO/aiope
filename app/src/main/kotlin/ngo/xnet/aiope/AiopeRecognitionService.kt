package ngo.xnet.aiope

import android.speech.RecognitionService

/**
 * Minimal RecognitionService. Required to be referenced by the voice-interaction service metadata.
 * AIOPE performs its own speech handling via the live voice session, so this is a stub that simply
 * reports an error if invoked directly (the assist/voice flow does not route through it).
 */
class AiopeRecognitionService : RecognitionService() {
  override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback?) {
    try { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
  }
  override fun onCancel(listener: Callback?) {}
  override fun onStopListening(listener: Callback?) {}
}
