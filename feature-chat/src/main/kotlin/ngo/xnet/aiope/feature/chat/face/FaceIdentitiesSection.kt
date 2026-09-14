package ngo.xnet.aiope.feature.chat.face

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ngo.xnet.aiope.core.terminal.shell.FaceModelBootstrap
import ngo.xnet.aiope.feature.chat.settings.faceChatDao
import java.io.File

/**
 * Security-settings section for consent-based, on-device face identities.
 *
 * Shows: model download (YuNet + ArcFace), an opt-in "Enroll my face" capture flow, and the
 * list of enrolled identities with per-item delete. All processing is on-device; embeddings
 * are stored encrypted (Keystore-wrapped). This is the ONLY place identities are created —
 * there is no automatic/covert enrollment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceIdentitiesSection(context: Context) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { faceChatDao(ctx) }
    val manager = remember { FaceIdentityManager(ctx.applicationContext, dao) }

    val modelInstalled = remember { mutableStateOf(FaceModelBootstrap.isInstalled(ctx)) }
    val modelBusy = remember { mutableStateOf(false) }
    val modelStatus = remember {
        mutableStateOf(if (FaceModelBootstrap.isInstalled(ctx)) "Installed" else "Not installed")
    }

    val identities = remember { mutableStateOf<List<FaceEnrollmentStore.Identity>>(emptyList()) }
    val enrollLabel = remember { mutableStateOf("") }
    val enrollStatus = remember { mutableStateOf<String?>(null) }
    val showEnrollDialog = remember { mutableStateOf(false) }
    val pendingCaptureUri = remember { mutableStateOf<Uri?>(null) }
    val angleCount = remember { mutableStateOf(0) }

    fun refresh() {
        scope.launch { identities.value = manager.enrolled() }
    }
    LaunchedEffect(Unit) { refresh() }

    // Camera capture using the same TakePicture contract as the chat attach flow.
    // Multi-angle: each successful capture appends another sample under the same label.
    val captureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri.value
        if (success && uri != null) {
            scope.launch {
                enrollStatus.value = "Processing face..."
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openInputStream(uri).use { input ->
                            val bmp = BitmapFactory.decodeStream(input) ?: return@withContext false
                            manager.enroll(enrollLabel.value.trim(), bmp)
                        }
                    } catch (_: Throwable) {
                        false
                    }
                }
                if (ok) {
                    angleCount.value += 1
                    enrollStatus.value = "Captured ${angleCount.value} angle(s) for ${enrollLabel.value.trim()}. " +
                        "Capture more from different angles (front, slight left/right, up/down) for better accuracy, or Done."
                } else {
                    enrollStatus.value = "No face detected — try again"
                }
                refresh()
            }
        }
    }

    fun launchCapture() {
        val dir = File(ctx.cacheDir, "face_enroll").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingCaptureUri.value = uri
        captureLauncher.launch(uri)
    }

    Text(
        "Face identities (on-device)",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    Text(
        "Opt-in personalization. Enrolled users are recognized on-device to tailor the assistant. " +
            "Faces are never uploaded; embeddings are encrypted. Unrecognized faces stay unidentified. " +
            "Delete anytime.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    // Model download row
    ListItem(
        headlineContent = { Text("Face models (YuNet + ArcFace)") },
        supportingContent = {
            Text(
                modelStatus.value,
                style = MaterialTheme.typography.bodySmall,
                color = if (modelInstalled.value) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            TextButton(
                enabled = !modelBusy.value,
                onClick = {
                    if (!modelBusy.value) {
                        modelBusy.value = true
                        modelStatus.value = "Downloading..."
                        scope.launch(Dispatchers.IO) {
                            try {
                                FaceModelBootstrap.setup(ctx) { msg -> modelStatus.value = msg }
                                modelInstalled.value = FaceModelBootstrap.isInstalled(ctx)
                                modelStatus.value = if (modelInstalled.value) "Installed" else "Failed"
                            } catch (e: Exception) {
                                modelStatus.value = "Error: ${e.message?.take(40)}"
                            }
                            modelBusy.value = false
                        }
                    }
                },
            ) { Text(if (modelBusy.value) "Downloading..." else if (modelInstalled.value) "Redownload" else "Download") }
        },
    )

    // Enroll row (opt-in)
    ListItem(
        headlineContent = { Text("Enroll a face") },
        supportingContent = {
            Text(
                enrollStatus.value ?: if (modelInstalled.value) "Add yourself (capture several angles)" else "Download models first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            TextButton(
                enabled = modelInstalled.value,
                onClick = {
                    angleCount.value = 0
                    enrollStatus.value = null
                    showEnrollDialog.value = true
                },
            ) { Text("Enroll") }
        },
    )

    // Enrolled identities — grouped by person (label) with angle-sample count.
    val byLabel = identities.value.groupBy { it.label }
    for ((label, samples) in byLabel) {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = {
                Text(
                    "${samples.size} angle sample(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Row {
                    TextButton(onClick = {
                        // Add more angles to an existing person.
                        angleCount.value = samples.size
                        enrollLabel.value = label
                        enrollStatus.value = null
                        showEnrollDialog.value = true
                    }) { Text("Add") }
                    TextButton(onClick = {
                        scope.launch { manager.deleteByLabel(label); refresh() }
                    }) { Text("Delete") }
                }
            },
        )
    }

    if (showEnrollDialog.value) {
        AlertDialog(
            onDismissRequest = { showEnrollDialog.value = false },
            title = { Text(if (angleCount.value > 0) "Add face angles" else "Enroll your face") },
            text = {
                Column {
                    Text(
                        "Enrolling your own face for on-device personalization (stored encrypted on " +
                            "this device only). Capture SEVERAL angles — front, slight left/right, up/down — " +
                            "for reliable recognition. 3–5 angles recommended.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enrollLabel.value,
                        onValueChange = { enrollLabel.value = it },
                        label = { Text("Name / label") },
                        singleLine = true,
                        enabled = angleCount.value == 0, // lock the name once capturing starts
                    )
                    if (angleCount.value > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${angleCount.value} angle(s) captured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = enrollLabel.value.isNotBlank(),
                    onClick = { launchCapture() }, // dialog stays open for multiple captures
                ) { Text(if (angleCount.value > 0) "Capture another" else "Capture") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEnrollDialog.value = false
                    if (angleCount.value > 0) enrollStatus.value = "Enrolled ${enrollLabel.value.trim()} (${angleCount.value} angles)"
                    enrollLabel.value = ""
                    angleCount.value = 0
                }) { Text(if (angleCount.value > 0) "Done" else "Cancel") }
            },
        )
    }
}
