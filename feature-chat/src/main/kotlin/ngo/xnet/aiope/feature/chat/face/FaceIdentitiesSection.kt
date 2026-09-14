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
        mutableStateOf(FaceModelBootstrap.statusText(ctx))
    }

    val identities = remember { mutableStateOf<List<FaceEnrollmentStore.Identity>>(emptyList()) }
    val enrollLabel = remember { mutableStateOf("") }
    val enrollStatus = remember { mutableStateOf<String?>(null) }
    val showEnrollDialog = remember { mutableStateOf(false) }
    val showGuided = remember { mutableStateOf(false) }
    val showTestScan = remember { mutableStateOf(false) }
    val angleCount = remember { mutableStateOf(0) }

    fun refresh() {
        scope.launch { identities.value = manager.enrolled() }
    }
    LaunchedEffect(Unit) {
        refresh()
        // Re-derive model state from disk so a version bump / prior download shows correctly.
        modelInstalled.value = FaceModelBootstrap.isInstalled(ctx)
        modelStatus.value = FaceModelBootstrap.statusText(ctx)
    }

    // Enrollment uses the guided CameraX preview screen (GuidedEnrollScreen), which auto-captures
    // several angles via the front camera — the SAME pipeline the scan uses, so embeddings match.
    val faceCapture = remember { ngo.xnet.aiope.feature.chat.face.SilentFaceCapture(ctx.applicationContext) }
    // Which camera action requested permission (so we open the right screen once granted).
    val pendingCamAction = remember { mutableStateOf<String?>(null) }
    val camPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (pendingCamAction.value) {
                "scan" -> showTestScan.value = true
                else -> showGuided.value = true
            }
        } else {
            enrollStatus.value = "Camera permission is required."
        }
        pendingCamAction.value = null
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

    // Model row (YuNet detector + librefacerec-l embedder). State is re-checked on load so it
    // does not go stale after a version bump / background download.
    ListItem(
        headlineContent = { Text("Face models (YuNet + librefacerec-l)") },
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
                                modelStatus.value = if (modelInstalled.value) FaceModelBootstrap.statusText(ctx) else "Failed"
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

    // Test-scan row — validates enrollments + store integrity with a live camera diagnostic.
    ListItem(
        headlineContent = { Text("Test scan") },
        supportingContent = {
            Text(
                "Live check: who does the camera recognize + are stored embeddings healthy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            TextButton(
                enabled = modelInstalled.value,
                onClick = {
                    if (!faceCapture.hasPermission()) {
                        pendingCamAction.value = "scan"
                        camPermLauncher.launch(android.Manifest.permission.CAMERA)
                    } else {
                        showTestScan.value = true
                    }
                },
            ) { Text("Scan") }
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
                        "Enroll a face for on-device personalization (stored encrypted, this device only). " +
                            "A live camera will guide you to capture several angles automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enrollLabel.value,
                        onValueChange = { enrollLabel.value = it },
                        label = { Text("Name / label") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = enrollLabel.value.isNotBlank(),
                    onClick = {
                        showEnrollDialog.value = false
                        if (!faceCapture.hasPermission()) {
                            pendingCamAction.value = "enroll"
                            camPermLauncher.launch(android.Manifest.permission.CAMERA)
                        } else {
                            showGuided.value = true
                        }
                    },
                ) { Text("Start guided capture") }
            },
            dismissButton = {
                TextButton(onClick = { showEnrollDialog.value = false }) { Text("Cancel") }
            },
        )
    }

    // Full-screen guided enrollment (live CameraX preview + auto-capture).
    if (showGuided.value) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showGuided.value = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            GuidedEnrollScreen(
                label = enrollLabel.value.trim(),
                manager = manager,
                onDone = { count ->
                    showGuided.value = false
                    enrollStatus.value = if (count > 0) "Enrolled ${enrollLabel.value.trim()} ($count angles)" else "No captures — try again"
                    enrollLabel.value = ""
                    refresh()
                },
                onBack = { showGuided.value = false; refresh() },
            )
        }
    }

    // Full-screen live diagnostic scan.
    if (showTestScan.value) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showTestScan.value = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            TestScanScreen(
                manager = manager,
                onBack = { showTestScan.value = false },
            )
        }
    }
}
