package ngo.xnet.aiope.feature.chat.face

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xnet.aiope.inference.FaceEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live diagnostic scan to validate enrollments. Shows a front-camera preview; each analyzed frame
 * runs a detailed identify and reports the per-label cosine scores, top match, margin, and whether
 * it would be ACCEPTED — plus a store integrity line (decryptable / valid-dim rows).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScanScreen(
    manager: FaceIdentityManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val busy = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()

    val report = remember { mutableStateOf<FaceEngine.ScanReport?>(null) }
    val integrity = remember { mutableStateOf<FaceEnrollmentStore.Integrity?>(null) }
    val enrolledCount = remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        integrity.value = manager.integrityReport()
        enrolledCount.intValue = manager.enrolled().size
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                if (!busy.compareAndSet(false, true)) { proxy.close(); return@setAnalyzer }
                                val bmp = try {
                                    val b = proxy.toBitmap()
                                    val rot = proxy.imageInfo.rotationDegrees
                                    if (rot != 0) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b
                                } catch (_: Throwable) { null }
                                if (bmp == null) { busy.set(false); proxy.close(); return@setAnalyzer }
                                scope.launch(Dispatchers.IO) {
                                    val r = runCatching { manager.testScan(bmp) }.getOrNull()
                                    withContext(Dispatchers.Main) { if (r != null) report.value = r }
                                    busy.set(false)
                                }
                                proxy.close()
                            }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        pv
                    },
                )
            }

            // Results panel
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val r = report.value
                    val integ = integrity.value
                    // Integrity line — encrypt/decrypt health of the stored embeddings.
                    if (integ != null) {
                        val ok = integ.total > 0 && integ.decryptable == integ.total && integ.validDim == integ.total
                        Text(
                            "Store: ${integ.total} row(s), ${integ.decryptable} decryptable, " +
                                "${integ.validDim} valid ${integ.expectedDim}-d " +
                                if (integ.total == 0) "(nothing enrolled)" else if (ok) "✓ healthy" else "⚠ ISSUE",
                            color = if (integ.total == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else if (ok) Color(0xFF00C853) else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    when {
                        enrolledCount.intValue == 0 ->
                            Text("No enrollments to test. Enroll a face first.", fontSize = 15.sp)
                        r == null ->
                            Text("Point the front camera at a face…", fontSize = 15.sp)
                        !r.faceFound ->
                            Text("No face detected in frame.", fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            Text(
                                if (r.accepted) "MATCH: ${r.topLabel}" else "No confident match",
                                color = if (r.accepted) Color(0xFF00C853) else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "top=${"%.3f".format(r.topScore)}  " +
                                    "runnerUp=${if (r.runnerUp < 0) "—" else "%.3f".format(r.runnerUp)}  " +
                                    "margin=${"%.3f".format(r.margin)}",
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Per-person cosine:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            for ((lbl, sc) in r.perLabelScores) {
                                Text("  $lbl: ${"%.3f".format(sc)}",
                                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
