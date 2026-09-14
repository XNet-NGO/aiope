package ngo.xnet.aiope.feature.chat.face

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ngo.xnet.aiope.core.terminal.shell.FaceModelBootstrap
import org.xnet.aiope.inference.FaceEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guided face enrollment with a live CameraX preview (front camera).
 *
 * Uses YuNet detection each frame to guide the user (center face / move closer) and, when a
 * face is well-positioned and stable, AUTO-CAPTURES that angle's embedding. Collects several
 * angles for one label, then persists. No manual shutter; the on-screen guidance drives it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedEnrollScreen(
    label: String,
    manager: FaceIdentityManager,
    onDone: (captured: Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val engine = remember {
        FaceEngine(FaceModelBootstrap.detectFile(context), FaceModelBootstrap.embedFile(context))
    }
    val guidance = remember { mutableStateOf("Position your face in the circle") }
    val direction = remember { mutableStateOf(EnrollDir.NONE) }
    val captured = remember { mutableIntStateOf(0) }
    val targetAngles = 5
    val busy = remember { AtomicBoolean(false) }
    val stableFrames = remember { mutableIntStateOf(0) }
    val lastCaptureAt = remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val done = remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown(); engine.close() } }

    // Angle prompts + which chevron to emphasize for each capture step.
    val prompts = listOf(
        "Look straight ahead" to EnrollDir.NONE,
        "Slowly turn your head LEFT" to EnrollDir.LEFT,
        "Slowly turn your head RIGHT" to EnrollDir.RIGHT,
        "Slowly tilt your head UP" to EnrollDir.UP,
        "Slowly tilt your head DOWN" to EnrollDir.DOWN,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enroll: $label") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val pv = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            handleEnrollFrame(
                                proxy, engine, busy, guidance, direction, captured, stableFrames,
                                lastCaptureAt, targetAngles, prompts, manager, label, done, scope,
                            ) { onDone(captured.intValue) }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    pv
                },
            )

            // --- Directional chevrons on all four edges ---
            val activeColor = Color(0xFF00E676)
            val idleColor = Color(0x33FFFFFF)
            EdgeChevron(
                icon = Icons.Filled.KeyboardArrowLeft,
                active = direction.value == EnrollDir.LEFT,
                activeColor = activeColor, idleColor = idleColor,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
            )
            EdgeChevron(
                icon = Icons.Filled.KeyboardArrowRight,
                active = direction.value == EnrollDir.RIGHT,
                activeColor = activeColor, idleColor = idleColor,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            )
            EdgeChevron(
                icon = Icons.Filled.KeyboardArrowUp,
                active = direction.value == EnrollDir.UP,
                activeColor = activeColor, idleColor = idleColor,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
            )
            EdgeChevron(
                icon = Icons.Filled.KeyboardArrowDown,
                active = direction.value == EnrollDir.DOWN,
                activeColor = activeColor, idleColor = idleColor,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp),
            )

            // --- Big guidance text + progress ---
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${captured.intValue} / $targetAngles captured",
                    color = activeColor, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                )
                Spacer(Modifier.height(10.dp))
                Surface(color = Color(0xE6000000), shape = MaterialTheme.shapes.large) {
                    Text(
                        guidance.value,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 32.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    )
                }
            }
            if (captured.intValue > 0) {
                Button(
                    onClick = { onDone(captured.intValue) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Text("Done") }
            }
        }
    }
}

/** A large edge chevron that pulses when it is the active direction to move. */
@Composable
private fun EdgeChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    idleColor: Color,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "chev")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "chevAlpha",
    )
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (active) activeColor.copy(alpha = alpha) else idleColor,
        modifier = modifier.size(if (active) 96.dp else 56.dp),
    )
}

/** Per-frame: detect, guide, and auto-capture when the face is well-framed and stable. */
private fun handleEnrollFrame(
    proxy: ImageProxy,
    engine: FaceEngine,
    busy: AtomicBoolean,
    guidance: MutableState<String>,
    direction: MutableState<EnrollDir>,
    captured: androidx.compose.runtime.MutableIntState,
    stableFrames: androidx.compose.runtime.MutableIntState,
    lastCaptureAt: androidx.compose.runtime.MutableLongState,
    targetAngles: Int,
    prompts: List<Pair<String, EnrollDir>>,
    manager: FaceIdentityManager,
    label: String,
    done: MutableState<Boolean>,
    scope: kotlinx.coroutines.CoroutineScope,
    onComplete: () -> Unit,
) {
    // Slower pacing: require the face to be well-framed for more consecutive frames, and wait
    // longer between captures, so the user has time to move to the next angle deliberately.
    val requiredStable = 12          // ~1.5–2s of steady good framing before a shot
    val cooldownMs = 2500L           // pause between captures so people can reposition slowly
    try {
        if (done.value || captured.intValue >= targetAngles) { proxy.close(); return }
        if (!busy.compareAndSet(false, true)) { proxy.close(); return }
        val bmp = try {
            val b = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b
        } catch (_: Throwable) { null }

        if (bmp == null) { busy.set(false); proxy.close(); return }

        val step = captured.intValue.coerceIn(0, prompts.lastIndex)
        val (stepText, stepDir) = prompts[step]
        // Point the chevron for this step (only while we still need to move / aren't mid-cooldown).
        direction.value = stepDir

        val faces = engine.detectForGuidance(bmp)
        val verdict = evaluateFraming(faces, bmp.width, bmp.height)
        when (verdict) {
            Framing.NONE -> { guidance.value = "No face detected —\nface the camera"; stableFrames.intValue = 0 }
            Framing.TOO_SMALL -> { guidance.value = "Move a little CLOSER"; stableFrames.intValue = 0 }
            Framing.TOO_BIG -> { guidance.value = "Move back a little"; stableFrames.intValue = 0 }
            Framing.OFF_CENTER -> { guidance.value = "Center your face"; stableFrames.intValue = 0 }
            Framing.GOOD -> {
                stableFrames.intValue += 1
                val now = System.currentTimeMillis()
                val cooling = now - lastCaptureAt.longValue <= cooldownMs
                if (cooling) {
                    guidance.value = "Great — now:\n$stepText"
                } else if (stableFrames.intValue >= requiredStable) {
                    lastCaptureAt.longValue = now
                    stableFrames.intValue = 0
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching { manager.enroll(label, bmp) }.getOrDefault(false)
                        if (ok) {
                            val c = captured.intValue + 1
                            withContext(Dispatchers.Main) { captured.intValue = c }
                            if (c >= targetAngles) {
                                done.value = true
                                withContext(Dispatchers.Main) { onComplete() }
                            }
                        }
                    }
                    val next = captured.intValue + 1
                    guidance.value = if (next < targetAngles) {
                        "Captured ${next}!\nNext: ${prompts.getOrNull(next)?.first ?: "hold still"}"
                    } else {
                        "Captured! Finishing…"
                    }
                } else {
                    // Countdown-style hold cue so it feels deliberate, not rushed.
                    val remaining = ((requiredStable - stableFrames.intValue) / 6) + 1
                    guidance.value = "$stepText\nHold still… $remaining"
                }
            }
        }
    } catch (_: Throwable) {
    } finally {
        busy.set(false)
        proxy.close()
    }
}

/** Direction the user should move their head for the current enrollment step. */
enum class EnrollDir { NONE, LEFT, RIGHT, UP, DOWN }

private enum class Framing { NONE, TOO_SMALL, TOO_BIG, OFF_CENTER, GOOD }

/** Heuristic framing check from the largest detected face box. */
private fun evaluateFraming(faces: List<FaceEngine.Face>?, w: Int, h: Int): Framing {
    if (faces.isNullOrEmpty()) return Framing.NONE
    val f = faces.maxByOrNull { (it.box[2] - it.box[0]) * (it.box[3] - it.box[1]) } ?: return Framing.NONE
    val bw = (f.box[2] - f.box[0]).coerceAtLeast(0f)
    val bh = (f.box[3] - f.box[1]).coerceAtLeast(0f)
    val frac = (bw * bh) / (w.toFloat() * h.toFloat())
    val cx = (f.box[0] + f.box[2]) / 2f / w
    val cy = (f.box[1] + f.box[3]) / 2f / h
    return when {
        frac < 0.06f -> Framing.TOO_SMALL
        frac > 0.75f -> Framing.TOO_BIG
        cx < 0.30f || cx > 0.70f || cy < 0.25f || cy > 0.75f -> Framing.OFF_CENTER
        else -> Framing.GOOD
    }
}
