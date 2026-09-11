package ngo.xnet.aiope.feature.chat.engine

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ngo.xnet.aiope.core.network.ModelConfig
import ngo.xnet.aiope.core.network.ModelDef
import ngo.xnet.aiope.core.network.ModelTask
import ngo.xnet.aiope.core.network.ProviderProfile
import ngo.xnet.aiope.core.network.TaskModelStore
import javax.inject.Inject
import javax.inject.Singleton

/** Realtime voice session state, observable app-wide. */
enum class VoiceState { IDLE, STARTING, LISTENING, SPEAKING }

/** A voice turn event surfaced to any observer (e.g. the ChatViewModel to update the UI). */
sealed interface VoiceTurn {
  data class UserTranscript(val id: String, val text: String) : VoiceTurn
  data class AssistantDelta(val id: String, val text: String) : VoiceTurn
  data class Error(val message: String) : VoiceTurn
}

/**
 * Process-scoped (Hilt @Singleton) owner of the realtime voice session. Because it lives in the
 * app process — not the Activity — the floating overlay button and the assistant session can start
 * and stop live voice HEADLESSLY (no need to foreground the app). It is the single authority for
 * voice, which structurally prevents the double-session / can't-hang-up bugs.
 *
 * Message persistence goes straight to the DB via [messageSink] so turns appear when the user opens
 * the app, and observers (the ChatViewModel) mirror turns into the visible message list live.
 */
@Singleton
class VoiceSessionController @Inject constructor(
  private val app: Application,
  private val providerStore: ngo.xnet.aiope.feature.chat.settings.ProviderStore,
  private val chatDao: ngo.xnet.aiope.feature.chat.db.ChatDao,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val okHttp = SafeOkHttp.builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()

  // Whether collaborators have been wired (by the ViewModel when the app is open, or headlessly).
  @Volatile private var wired = false
  @Volatile private var headlessConversationId: String = java.util.UUID.randomUUID().toString()

  /**
   * Wire minimal collaborators for HEADLESS operation (overlay/assist with no Activity/ViewModel).
   * The ViewModel's richer wiring takes precedence when the app is open (it sets [wired]).
   */
  @Synchronized
  fun ensureHeadlessWiring() {
    if (wired) return
    wired = true
    _providerStore = providerStore
    conversationIdProvider = { headlessConversationId }
    messageSink = { id, role, content ->
      scope.launch {
        try {
          // Ensure a conversation row exists for the headless voice lane.
          runCatching { chatDao.insertConversation(ngo.xnet.aiope.feature.chat.db.ConversationEntity(id = headlessConversationId, title = "Voice", agentName = "default")) }
          chatDao.insertMessage(ngo.xnet.aiope.feature.chat.db.MessageEntity(id = id, conversationId = headlessConversationId, role = role, content = content))
        } catch (_: Exception) {}
      }
    }
    // No tools in the pure-headless path (toolExecutorProvider stays null) — voice still works.
  }

  /** Called by the ViewModel when the app is open to claim ownership of wiring. */
  fun markWiredByViewModel() { wired = true }

  private val _state = MutableStateFlow(VoiceState.IDLE)
  val state: StateFlow<VoiceState> = _state.asStateFlow()

  /** Turn events for live UI mirroring. replay=0, extraBufferCapacity so headless emits don't block. */
  val turns = MutableSharedFlow<VoiceTurn>(replay = 0, extraBufferCapacity = 64)

  // Collaborators supplied by whoever wires the controller (set once, at app start).
  /** Provider for the tool executor, invoked lazily at session start (never at wiring time). */
  @Volatile var toolExecutorProvider: (() -> ToolExecutor?)? = null
  @Volatile var conversationIdProvider: (() -> String)? = null
  /** Persist a finished turn (id, role, content). */
  @Volatile var messageSink: ((id: String, role: String, content: String) -> Unit)? = null
  /** Optional screen context (from the assistant session) to inject at session start. */
  @Volatile var pendingScreenContext: String? = null

  private var streaming: RealtimeStreaming? = null
  private var audio: RealtimeAudioManager? = null
  private var job: Job? = null
  private var starting = false
  private var turnId: String = ""

  val isActive: Boolean get() = _state.value != VoiceState.IDLE

  @Synchronized
  fun toggle() {
    ensureHeadlessWiring()
    if (_state.value != VoiceState.IDLE || starting) stop() else start()
  }

  @Synchronized
  fun start() {
    ensureHeadlessWiring()
    if (_state.value != VoiceState.IDLE || starting) {
      android.util.Log.i("VoiceCtl", "start ignored (active/starting)")
      return
    }
    starting = true
    _state.value = VoiceState.STARTING
    ngo.xnet.aiope.core.preferences.VoiceBridge.isActive = true

    val taskStore = TaskModelStore(app)
    val ps = _providerStore
    // resolve provider+model for REALTIME_SPEECH
    val (profileId, modelId) = if (ps != null) {
      runCatching { taskStore.resolve(ModelTask.REALTIME_SPEECH, ps) }.getOrNull() ?: (null to null)
    } else {
      null to null
    }
    val profile = resolveProfile(profileId)
    val resolvedModelId = modelId ?: "google-ai-studio/gemini-3.1-flash-live-preview"
    val modelDef = ModelDef(id = resolvedModelId, supportsAudio = true, useStreaming = true, sampleRate = 16000)

    audio = RealtimeAudioManager(AudioConfig(sampleRate = modelDef.sampleRate)).apply { startPlayback() }
    routeAudioToSpeaker()

    job = scope.launch {
      val te = toolExecutorProvider?.invoke()
      try {
        val screen = pendingScreenContext?.also { pendingScreenContext = null }
        val sys = buildString {
          append("You are AIOPE in a live voice session. Be concise. Execute tools directly when needed.")
          if (!screen.isNullOrBlank()) append("\n\nThe user invoked you over another app. Visible screen context:\n").append(screen.take(4000))
        }
        val stream = RealtimeStreaming(
          okHttp = okHttp,
          modelDef = modelDef,
          config = ModelConfig(modelId = modelDef.id),
          provider = profile,
          audioManager = audio!!,
          systemPrompt = sys,
          voiceName = ngo.xnet.aiope.feature.chat.settings.getVoiceName(app),
          tools = te?.buildToolDefs() ?: emptyList(),
        )
        streaming = stream
        starting = false
        _state.value = VoiceState.LISTENING

        stream.createStream().collect { event ->
          when (event) {
            is StreamEvent.AudioChunk -> {
              audio?.playAudio(event.pcmData)
              _state.value = VoiceState.SPEAKING
            }
            is StreamEvent.TurnComplete -> {
              audio?.onTurnComplete()
              _state.value = VoiceState.LISTENING
              if (turnId.isNotBlank()) { persistLastAssistant(turnId); turnId = "" }
            }
            is StreamEvent.Interrupted -> audio?.clearPlayback()
            is StreamEvent.InputTranscription -> {
              val id = java.util.UUID.randomUUID().toString()
              persist(id, "user", event.text)
              turns.tryEmit(VoiceTurn.UserTranscript(id, event.text))
            }
            is StreamEvent.OutputTranscription -> {
              if (turnId.isBlank()) turnId = java.util.UUID.randomUUID().toString()
              assistantBuffer.append(event.text)
              turns.tryEmit(VoiceTurn.AssistantDelta(turnId, event.text))
            }
            is StreamEvent.ToolCallEvent -> {
              val responses = event.functionCalls.map { fc ->
                val result = runCatching { te?.execute(fc.name, fc.args) ?: "Tools unavailable" }.getOrElse { "Error: ${it.message}" }
                fc.id to result
              }
              stream.sendToolResponse(responses)
            }
            is StreamEvent.Error -> {
              if (!event.message.isNullOrBlank()) turns.tryEmit(VoiceTurn.Error(event.message!!))
              stop()
            }
            else -> {}
          }
        }
      } catch (e: Exception) {
        if (e !is kotlinx.coroutines.CancellationException) {
          e.message?.let { turns.tryEmit(VoiceTurn.Error(it)) }
        }
        stop()
      }
    }
  }

  private val assistantBuffer = StringBuilder()

  @Synchronized
  fun stop() {
    streaming?.lastSessionHandle // touch for parity; resumption handled elsewhere if needed
    starting = false
    ngo.xnet.aiope.core.preferences.VoiceBridge.isActive = false
    _state.value = VoiceState.IDLE
    if (turnId.isNotBlank()) { persistLastAssistant(turnId); turnId = "" }
    job?.cancel(); job = null
    try { streaming?.stop() } catch (_: Exception) {}
    streaming = null
    try { audio?.stop() } catch (_: Exception) {}
    audio = null
    restoreAudioMode()
    assistantBuffer.clear()
  }

  /** Send a text/user turn into an active session (used by the in-app composer during voice). */
  fun sendText(text: String) {
    val stream = streaming ?: return
    scope.launch {
      runCatching { stream.sendClientContent(listOf(org.json.JSONObject().put("text", text))) }
    }
  }

  private fun persist(id: String, role: String, content: String) {
    if (content.isBlank()) return
    messageSink?.invoke(id, role, content)
  }

  private fun persistLastAssistant(id: String) {
    val text = assistantBuffer.toString()
    if (text.isNotBlank()) persist(id, "assistant", text)
    assistantBuffer.clear()
  }

  private fun providerStore() = _providerStore
  @Volatile var _providerStore: ngo.xnet.aiope.feature.chat.settings.ProviderStore? = null

  private fun resolveProfile(profileId: String?): ProviderProfile {
    val ps = _providerStore
    return if (ps != null) {
      if (profileId != null) ps.getAll().find { it.id == profileId } ?: ps.getActive() else ps.getActive()
    } else {
      ProviderProfile()
    }
  }

  private fun routeAudioToSpeaker() {
    try {
      val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.mode = AudioManager.MODE_IN_COMMUNICATION
      am.isSpeakerphoneOn = true
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
      }
    } catch (_: Exception) {}
  }

  private fun restoreAudioMode() {
    try {
      val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.mode = AudioManager.MODE_NORMAL
      am.isSpeakerphoneOn = false
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) am.clearCommunicationDevice()
    } catch (_: Exception) {}
  }
}
