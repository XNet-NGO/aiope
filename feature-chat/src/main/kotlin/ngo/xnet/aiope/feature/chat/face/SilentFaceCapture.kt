package ngo.xnet.aiope.feature.chat.face

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Captures a single still frame from the FRONT camera with no preview, using CameraX
 * [ImageCapture] (which manages the camera session/threading correctly, unlike hand-rolled
 * Camera2). Binds to the process lifecycle, takes one picture, unbinds.
 *
 * Only invoked from the sensor/foreground-gated presence flow, never continuously.
 */
class SilentFaceCapture(private val context: Context) {

    companion object {
        private const val TAG = "SilentFaceCapture"
        private const val TIMEOUT_MS = 6000L
        /** Longer budget when capturing multiple frames for a robust scan. */
        private const val MULTI_TIMEOUT_MS = 12000L
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun captureFrontFace(): Bitmap? {
        if (!hasPermission()) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            // CameraX bind/unbind must occur on the main thread.
            val provider = awaitCameraProvider() ?: return@withTimeoutOrNull null
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val lifecycleOwner = ProcessLifecycleOwner.get()
            try {
                withContext(Dispatchers.Main) {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        imageCapture,
                    )
                }
                takePicture(imageCapture)
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed", t)
                null
            } finally {
                withContext(Dispatchers.Main) { runCatching { provider.unbindAll() } }
            }
        }
    }

    /**
     * Capture several frames over an extended window (camera stays bound the whole time). Each
     * frame is handed to [onFrame] as it arrives; capture stops early when [onFrame] returns true
     * (e.g. a confident match was found). Used by the tool/auto scan for a longer, more robust
     * recognition pass than a single shot.
     *
     * @param maxFrames maximum frames to take
     * @param gapMs delay between frames (lets the subject settle / lighting adjust)
     */
    suspend fun captureFrames(maxFrames: Int = 5, gapMs: Long = 900L, onFrame: suspend (Bitmap) -> Boolean) {
        if (!hasPermission()) return
        withTimeoutOrNull(MULTI_TIMEOUT_MS) {
            val provider = awaitCameraProvider() ?: return@withTimeoutOrNull
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val lifecycleOwner = ProcessLifecycleOwner.get()
            try {
                withContext(Dispatchers.Main) {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
                }
                repeat(maxFrames) { i ->
                    val bmp = takePicture(imageCapture)
                    if (bmp != null && onFrame(bmp)) return@repeat
                    if (i < maxFrames - 1) kotlinx.coroutines.delay(gapMs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "multi-capture failed", t)
            } finally {
                withContext(Dispatchers.Main) { runCatching { provider.unbindAll() } }
            }
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    cont.resume(runCatching { future.get() }.getOrNull())
                }, ContextCompat.getMainExecutor(context))
            }
        }

    private suspend fun takePicture(imageCapture: ImageCapture): Bitmap? =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bmp = try {
                            val b = image.toBitmap()
                            val rot = image.imageInfo.rotationDegrees
                            if (rot != 0) {
                                val m = Matrix().apply { postRotate(rot.toFloat()) }
                                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                            } else {
                                b
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "decode failed", t); null
                        } finally {
                            image.close()
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "onError", exception)
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
}
