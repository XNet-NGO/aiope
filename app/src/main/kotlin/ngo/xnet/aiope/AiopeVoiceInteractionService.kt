package ngo.xnet.aiope

import android.service.voice.VoiceInteractionService

/**
 * The always-on voice-interaction service. Its presence (gated by BIND_VOICE_INTERACTION) makes
 * AIOPE a full voice assistant and qualifies it for the ASSISTANT role via the voice-interaction
 * path. The actual work happens in AiopeVoiceInteractionSessionService/Session.
 */
class AiopeVoiceInteractionService : VoiceInteractionService()
