package ngo.xnet.aiope.feature.chat.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    // 25fps cap: skip frames that arrive sooner than this interval.
    val lastFrameAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val smoother = remember { DetectionSmoother() }
    val ready = remember { ObjectDetectionHelper.anyReady(context) }

    // Runtime CAMERA permission — without it the preview is black.
    val hasCamPerm = remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamPerm.value = granted }
    LaunchedEffect(Unit) {
        if (!hasCamPerm.value) permLauncher.launch(android.Manifest.permission.CAMERA)
    }

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
            if (!hasCamPerm.value) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Camera permission is required for live detection.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(android.Manifest.permission.CAMERA) }) {
                        Text("Grant camera access")
                    }
                }
                return@Box
            }

            // TEST MODE: constrain preview + overlay to a centered 1:1 square so the displayed
            // area is exactly the 640x640 the model receives.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        // SurfaceView (PERFORMANCE, the default) renders black inside a Compose
                        // Dialog window; TextureView-backed COMPATIBLE mode fixes that.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        // FILL_CENTER fills the square box (center-cropping overflow) so there is
                        // NO left/right pillarbox padding. Because the analysis frame is also
                        // center-cropped to a square, the visible region matches what the model sees.
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        // TEST MODE: everything is a strict 640x640 square. Analysis is locked to
                        // 640x640, the incoming frame is center-cropped to a square before inference,
                        // and the preview is constrained to a 1:1 box so what you see == what the
                        // model sees (no distortion, no hidden crop mismatch).
                        val resSel = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(640, 640),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resSel)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            handleFrame(proxy, busy, lastFrameAt, smoother, context, detections, frameW, frameH, latestBitmap)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                wideAngleBackSelector(provider),
                                preview,
                                analysis,
                            )
                        } catch (_: Throwable) {
                            // Fall back to the default back camera if the wide-angle bind fails.
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                                )
                            }
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )

            // Overlay: a 640x640 square frame shown FILL_CENTER inside a 1:1 square box — the box
            // and frame are both square, so this is a single uniform scale with zero offset.
            Canvas(Modifier.fillMaxSize()) {
                val fw = frameW.value.toFloat().coerceAtLeast(1f)
                val fh = frameH.value.toFloat().coerceAtLeast(1f)
                // Square-in-square: uniform scale, no letterbox offset.
                val scale = minOf(size.width / fw, size.height / fh)
                val dx = (size.width - fw * scale) / 2f
                val dy = (size.height - fh * scale) / 2f
                for (d in detections.value) {
                    val left = d.x1 * scale + dx
                    val top = d.y1 * scale + dy
                    val right = d.x2 * scale + dx
                    val bottom = d.y2 * scale + dy
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
            } // end square Box
        }
    }
}

// Only skip frames arriving absurdly fast; the busy-gate + KEEP_ONLY_LATEST already ensure we
// process the newest frame and never queue. Keeping this low minimizes box-vs-video lag: the
// detector always starts on the latest frame the instant it's free.
private const val MIN_FRAME_INTERVAL_MS = 10L

/**
 * Choose the ULTRA-WIDE back camera when available: among back-facing cameras, pick the one with
 * the shortest minimum focal length (widest field of view). Falls back to the default back camera.
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun wideAngleBackSelector(provider: ProcessCameraProvider): CameraSelector {
    return try {
        val backInfos = provider.availableCameraInfos.filter { info ->
            runCatching { Camera2CameraInfo.from(info) }
                .getOrNull()
                ?.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        }
        if (backInfos.isEmpty()) return CameraSelector.DEFAULT_BACK_CAMERA
        val widest = backInfos.minByOrNull { info ->
            val focals = runCatching { Camera2CameraInfo.from(info) }
                .getOrNull()
                ?.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                )
            focals?.minOrNull() ?: Float.MAX_VALUE
        } ?: return CameraSelector.DEFAULT_BACK_CAMERA
        // Build a selector that filters to exactly this (widest) camera.
        CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { it === widest } }
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
    } catch (_: Throwable) {
        CameraSelector.DEFAULT_BACK_CAMERA
    }
}

/**
 * Temporal smoother that stabilizes the overlay: matches each new detection to the previous one
 * of the same label by IoU, EMA-blends the box coordinates (so it glides instead of jittering),
 * and keeps a recently-seen box alive for a few frames of misses to avoid flicker.
 */
private class DetectionSmoother(
    private val alpha: Float = 0.85f,    // EMA weight for the new box (higher = snappier, less lag)
    private val maxMisses: Int = 1,      // frames a lost box survives before it disappears
    private val iouMatch: Float = 0.3f,
) {
    private data class Track(var d: Detection, var misses: Int)
    private val tracks = mutableListOf<Track>()

    fun update(dets: List<Detection>): List<Detection> {
        val used = BooleanArray(dets.size)
        // Match existing tracks to new detections.
        for (t in tracks) {
            var bestI = -1; var bestIou = iouMatch
            for (i in dets.indices) {
                if (used[i] || dets[i].label != t.d.label) continue
                val iou = iou(t.d, dets[i])
                if (iou > bestIou) { bestIou = iou; bestI = i }
            }
            if (bestI >= 0) {
                used[bestI] = true
                val n = dets[bestI]
                t.d = Detection(
                    label = n.label,
                    score = n.score,
                    x1 = ema(t.d.x1, n.x1), y1 = ema(t.d.y1, n.y1),
                    x2 = ema(t.d.x2, n.x2), y2 = ema(t.d.y2, n.y2),
                )
                t.misses = 0
            } else {
                t.misses++
            }
        }
        // Spawn tracks for unmatched detections.
        for (i in dets.indices) if (!used[i]) tracks.add(Track(dets[i], 0))
        // Drop stale tracks.
        tracks.removeAll { it.misses > maxMisses }
        return tracks.map { it.d }
    }

    private fun ema(old: Float, new: Float) = old + alpha * (new - old)
    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = maxOf(a.x1, b.x1); val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2); val iy2 = minOf(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f); val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        val ua = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (ua <= 0f) 0f else inter / ua
    }
}

private fun handleFrame(
    proxy: ImageProxy,
    busy: AtomicBoolean,
    lastFrameAt: java.util.concurrent.atomic.AtomicLong,
    smoother: DetectionSmoother,
    context: android.content.Context,
    detections: MutableState<List<Detection>>,
    frameW: MutableState<Int>,
    frameH: MutableState<Int>,
    latestBitmap: MutableState<Bitmap?>,
) {
    // Throttle check first (no buffer held yet). Close+return on skip.
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastFrameAt.get() < MIN_FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) {
        proxy.close()
        return
    }
    lastFrameAt.set(now)

    // Extract the bitmap and CLOSE THE PROXY IMMEDIATELY — do NOT hold a camera buffer during the
    // (slow) inference. Holding the ImageProxy across inference starves the KEEP_ONLY_LATEST queue
    // and intermittently freezes the preview after startup.
    val rotated: Bitmap? = try {
        val b = proxy.toBitmapCompat()
        if (b != null) rotateBitmap(b, proxy.imageInfo.rotationDegrees) else null
    } catch (_: Throwable) {
        null
    } finally {
        proxy.close()
    }

    try {
        if (rotated != null) {
            // Center-crop to a square, then scale to exactly 640x640.
            val square = cropCenterSquare640(rotated)
            frameW.value = square.width
            frameH.value = square.height
            latestBitmap.value = square
            val raw = ObjectDetectionHelper.detectFast(context, square)
            detections.value = smoother.update(raw)
        }
    } catch (_: Throwable) {
    } finally {
        busy.set(false)
    }
}

private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bmp
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/** Center-crop to the largest square, then scale to exactly 640x640. */
private fun cropCenterSquare640(src: Bitmap): Bitmap {
    val side = minOf(src.width, src.height)
    val left = (src.width - side) / 2
    val top = (src.height - side) / 2
    val cropped = if (src.width == side && src.height == side) src
        else Bitmap.createBitmap(src, left, top, side, side)
    return if (cropped.width == 640 && cropped.height == 640) cropped
        else Bitmap.createScaledBitmap(cropped, 640, 640, true)
}

private fun ImageProxy.toBitmapCompat(): Bitmap? {
    return try {
        // CameraX provides a Bitmap conversion on the ImageProxy.
        this.toBitmap()
    } catch (_: Throwable) {
        null
    }
}
