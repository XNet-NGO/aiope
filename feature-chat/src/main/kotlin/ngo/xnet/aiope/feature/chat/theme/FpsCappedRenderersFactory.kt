package ngo.xnet.aiope.feature.chat.theme

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderers factory that caps the chat background video to [maxFps] frames
 * per second.
 *
 * Decoding continues at the source rate, but frames are only presented to the
 * surface at the cap. Surface composition is the dominant power cost of a
 * looping background video, so capping presentation cuts GPU/compositor work
 * substantially without stalling the pipeline or dropping audio sync.
 */
internal class FpsCappedRenderersFactory(
  context: Context,
  private val maxFps: Float,
) : DefaultRenderersFactory(context) {

  override fun buildVideoRenderers(
    context: Context,
    extensionRendererMode: Int,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    allowedVideoJoiningTimeMs: Long,
    out: ArrayList<Renderer>,
  ) {
    // We intentionally add only the capped renderer (no extension renderers):
    // the background video always uses a hardware codec via MediaCodecSelector.
    out.add(
      FpsCappedVideoRenderer(
        builder = MediaCodecVideoRenderer.Builder(context)
          .setMediaCodecSelector(mediaCodecSelector)
          .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
          .setEnableDecoderFallback(enableDecoderFallback)
          .setEventHandler(eventHandler)
          .setEventListener(eventListener)
          .setMaxDroppedFramesToNotify(
            DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
          ),
        maxFps = maxFps,
      ),
    )
  }
}

/**
 * [MediaCodecVideoRenderer] that presents frames at no more than [maxFps].
 *
 * The player loop calls [render] at high frequency. We only pass through to the
 * real pipeline when the configured frame interval has elapsed since the last
 * presented frame; intermediate passes are skipped. Because the parent render
 * drains every pending output buffer and refills the decoder input queue on
 * each pass, skipped frames are simply dropped as late by the renderer and the
 * codec never starves.
 */
internal class FpsCappedVideoRenderer(
  builder: MediaCodecVideoRenderer.Builder,
  private val maxFps: Float,
) : MediaCodecVideoRenderer(builder) {

  /** Minimum interval between presented frames, in microseconds. */
  private val minFrameIntervalUs: Long = (1_000_000L / maxFps).toLong()

  /** Elapsed time of the last presented frame; `Long.MIN_VALUE` = never. */
  private var lastPresentedElapsedUs: Long = Long.MIN_VALUE

  override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
    if (lastPresentedElapsedUs == Long.MIN_VALUE ||
      elapsedRealtimeUs - lastPresentedElapsedUs >= minFrameIntervalUs
    ) {
      lastPresentedElapsedUs = elapsedRealtimeUs
      super.render(positionUs, elapsedRealtimeUs)
    }
    // else: inside the frame-interval window — skip this pass. The next
    // pass presents the latest frame and refills the input queue.
  }
}
