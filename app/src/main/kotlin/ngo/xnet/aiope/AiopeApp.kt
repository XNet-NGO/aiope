package ngo.xnet.aiope

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AiopeApp :
  Application(),
  ImageLoaderFactory {
  override fun onCreate() {
    super.onCreate()
    installNetworkCrashGuard()
    ngo.xnet.aiope.feature.chat.engine.AgentRescheduleWorker.enqueue(this)
    // Index the bundled AIOPE manual into its own DB (aiope_manual.db) for the
    // introspect tool. Runs off the main thread; no-op when already indexed for
    // this app version. Re-indexes automatically on version change.
    Thread {
      runCatching { ngo.xnet.aiope.feature.chat.engine.EmbeddingBackend.ensureManualIndexed(this) }
    }.apply { isDaemon = true; start() }
  }

  /**
   * Last-resort guard: a transient network error (e.g. host unresolvable when connectivity drops
   * on backgrounding) thrown on a background worker thread must not crash the whole app. We only
   * swallow clearly network-related IO exceptions; everything else is delegated to the previous
   * (default) handler so genuine bugs still surface.
   */
  private fun installNetworkCrashGuard() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
      val networkish = generateSequence(e) { it.cause }.any {
        it is java.io.IOException ||
          it is java.net.UnknownHostException ||
          it is java.net.SocketException ||
          it is java.net.SocketTimeoutException ||
          (it.message?.contains("Unable to resolve host") == true)
      }
      if (networkish) {
        android.util.Log.e("AIOPE2", "Network exception on ${thread.name} suppressed (non-fatal): ${e.message}")
      } else {
        previous?.uncaughtException(thread, e)
      }
    }
  }

  override fun newImageLoader() = ImageLoader.Builder(this)
    .components { add(SvgDecoder.Factory()) }
    .build()
}
