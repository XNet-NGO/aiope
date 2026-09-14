package ngo.xnet.aiope.feature.chat.face

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the "who's here" face identification automatically, gated on:
 *   1. App entering the foreground (ProcessLifecycleOwner ON_START).
 *   2. Cheap presence sensors (light + accelerometer + gyro) via [PresenceSensorGate].
 *   3. A debounce so it runs at most once per [MIN_INTERVAL_MS].
 * Then it silently captures a front-camera frame ([SilentFaceCapture]) and updates the
 * cached identity used for prompt injection. Everything on-device; only enrolled users match.
 *
 * Install once at app start via [install]. No-ops when face models aren't installed, no users
 * are enrolled, or CAMERA permission is missing.
 */
class PresenceIdentifier private constructor(
    private val appContext: Context,
    private val manager: FaceIdentityManager,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "PresenceIdentifier"
        // Only used to coalesce near-simultaneous triggers (foreground + navigation); NOT a
        // rate limit. Recognition runs on every app open/return.
        private const val COALESCE_MS = 1500L

        @Volatile private var installed = false
        @Volatile private var instance: PresenceIdentifier? = null

        /** Idempotent install of the process-lifecycle observer. */
        fun install(appContext: Context, manager: FaceIdentityManager) {
            if (installed) return
            installed = true
            val pi = PresenceIdentifier(appContext.applicationContext, manager)
            instance = pi
            ProcessLifecycleOwner.get().lifecycle.addObserver(pi)
            // When a prompt build finds the identity missing/stale (TTL expired), run a fresh scan.
            FaceIdentityManager.onStaleRefreshRequested = { pi.trigger() }
        }

        /** Trigger a recognition pass now (e.g. when navigating back to the chat screen). */
        fun triggerNow() {
            instance?.trigger()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = PresenceSensorGate(appContext)
    private val capture = SilentFaceCapture(appContext)
    private val runLock = Mutex()
    @Volatile private var lastRun = 0L

    override fun onStart(owner: LifecycleOwner) {
        // App came to the foreground.
        trigger()
    }

    /**
     * Public trigger — call whenever recognition should run (app foreground, app open, or
     * navigating back to the chat screen). Fires on every call; a tiny coalescing window only
     * prevents duplicate runs when foreground + navigation land together.
     */
    fun trigger() {
        scope.launch { maybeIdentify() }
    }

    private suspend fun maybeIdentify() {
        val now = System.currentTimeMillis()
        // Coalesce near-simultaneous triggers (e.g. ON_START + screen navigation), but otherwise
        // run every time the app is opened/returned to.
        if (now - lastRun < COALESCE_MS) return
        if (!manager.modelsInstalled()) return
        if (!capture.hasPermission()) return
        // Only bother if there is at least one enrolled identity to match against.
        if (manager.enrolled().isEmpty()) return

        if (!runLock.tryLock()) return
        try {
            lastRun = now
            // Sensor gate is advisory: log when conditions look poor, but still attempt so the
            // user is recognized every time they open/return to the app.
            if (!gate.shouldIdentify()) {
                Log.d(TAG, "sensor gate: conditions not ideal, attempting anyway")
            }
            // Extended multi-frame scan for a robust recognition pass (not a single shot).
            val who = manager.identifyBestOverFrames(capture, maxFrames = 5)
            if (who == null) {
                Log.d(TAG, "no confident match over frames — identity cleared/unidentified")
            }
            Log.d(TAG, "identify result: ${who ?: "unidentified"}")
        } catch (t: Throwable) {
            Log.e(TAG, "presence identify failed", t)
        } finally {
            runLock.unlock()
        }
    }
}
