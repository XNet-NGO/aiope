package ngo.xnet.aiope.feature.chat.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.xnet.aiope.inference.Detection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live object-detection viewfinder (A). Shows a back-camera preview with real-time RT-DETR
 * detection boxes overlaid. A snapshot button captures the current frame + detections and hands
 * them back via [onSnapshot] so the caller can attach them to a chat message for the agent.
 *
 * Detection is throttled: at most one inference in flight at a time (frames are dropped while
 * busy), which keeps a transformer detector usable on-device without backing up the pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveObjectDetectionScreen(
    onSnapshot: (bitmap: Bitmap, summary: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Latest detections + the source frame dimensions they were computed against.
    val detections = remember { mutableStateOf<List<Detection>>(emptyList()) }
    val frameW = remember { mutableStateOf(1) }
    val frameH = remember { mutableStateOf(1) }
    val latestBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val busy = remember { AtomicBoolean(false) }
    val ready = remember { ObjectDetectionHelper.isReady(context) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Detection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready) {
                ExtendedFloatingActionButton(
                    text = { Text("Send to agent") },
                    icon = { Icon(Icons.Default.Send, null) },
                    onClick = {
                        val bmp = latestBitmap.value
                        if (bmp != null) {
                            onSnapshot(bmp, ObjectDetectionHelper.summarize(detections.value))
                        }
                    },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (!ready) {
                Text(
                    "Object-detection model not downloaded. Download it in settings first.",
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
                return@Box
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            handleFrame(proxy, busy, context, detections, frameW, frameH, latestBitmap)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        } catch (_: Throwable) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )

            // Detection overlay — map frame coords to view coords.
            Canvas(Modifier.fillMaxSize()) {
                val fw = frameW.value.toFloat().coerceAtLeast(1f)
                val fh = frameH.value.toFloat().coerceAtLeast(1f)
                val scaleX = size.width / fw
                val scaleY = size.height / fh
                for (d in detections.value) {
                    val left = d.x1 * scaleX
                    val top = d.y1 * scaleY
                    val right = d.x2 * scaleX
                    val bottom = d.y2 * scaleY
                    drawRect(
                        color = Color(0xFF00E676),
                        topLeft = Offset(left, top),
                        size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                        style = Stroke(width = 4f),
                    )
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.GREEN
                            textSize = 32f
                            isAntiAlias = true
                        }
                        drawText("${d.label} ${"%.2f".format(d.score)}", left + 6f, (top - 8f).coerceAtLeast(28f), paint)
                    }
                }
            }
        }
    }
}

private fun handleFrame(
    proxy: ImageProxy,
    busy: AtomicBoolean,
    context: android.content.Context,
    detections: MutableState<List<Detection>>,
    frameW: MutableState<Int>,
    frameH: MutableState<Int>,
    latestBitmap: MutableState<Bitmap?>,
) {
    try {
        if (!busy.compareAndSet(false, true)) {
            return
        }
        val bmp = proxy.toBitmapCompat()
        if (bmp != null) {
            val rotated = rotateBitmap(bmp, proxy.imageInfo.rotationDegrees)
            frameW.value = rotated.width
            frameH.value = rotated.height
            latestBitmap.value = rotated
            detections.value = ObjectDetectionHelper.detect(context, rotated)
        }
    } catch (_: Throwable) {
    } finally {
        busy.set(false)
        proxy.close()
    }
}

private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bmp
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

private fun ImageProxy.toBitmapCompat(): Bitmap? {
    return try {
        // CameraX provides a Bitmap conversion on the ImageProxy.
        this.toBitmap()
    } catch (_: Throwable) {
        null
    }
}
