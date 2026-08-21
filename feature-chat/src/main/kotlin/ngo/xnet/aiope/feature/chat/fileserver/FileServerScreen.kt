package ngo.xnet.aiope.feature.chat.fileserver

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileServerScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val prefs = remember { context.getSharedPreferences("file_server", 0) }
  var isRunning by remember { mutableStateOf(FileServerService.isRunning()) }
  var rootPath by remember { mutableStateOf(prefs.getString("root_path", "") ?: "") }
  var port by remember { mutableStateOf(prefs.getInt("port", FileServerService.DEFAULT_PORT).toString()) }
  var serverUrl by remember { mutableStateOf(FileServerService.currentUrl()) }

  val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    uri?.let {
      // Take persistent permission
      context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      // Convert to file path if possible, otherwise use the URI path
      val path = uriToPath(it)
      if (path != null) {
        rootPath = path
        prefs.edit().putString("root_path", path).apply()
      }
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        title = { Text("File Server") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(pad)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      // Status card
      if (isRunning && serverUrl != null) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(16.dp)) {
            Text("Server Running", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(serverUrl!!, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row {
              OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(serverUrl!!))
                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
              }) { Text("Copy URL") }
            }
          }
        }
        Spacer(Modifier.height(16.dp))
      }

      // Directory selection
      Text("Shared Directory", style = MaterialTheme.typography.labelMedium)
      Spacer(Modifier.height(4.dp))
      OutlinedTextField(
        value = rootPath,
        onValueChange = {
          rootPath = it
          prefs.edit().putString("root_path", it).apply()
        },
        label = { Text("Path") },
        placeholder = { Text("/storage/emulated/0/Download") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = { dirPicker.launch(null) }) {
        Text("Browse…")
      }

      Spacer(Modifier.height(16.dp))

      // Port
      Text("Port", style = MaterialTheme.typography.labelMedium)
      Spacer(Modifier.height(4.dp))
      OutlinedTextField(
        value = port,
        onValueChange = {
          port = it.filter { c -> c.isDigit() }
          port.toIntOrNull()?.let { p -> prefs.edit().putInt("port", p).apply() }
        },
        label = { Text("Port") },
        modifier = Modifier.width(120.dp),
        singleLine = true,
      )

      Spacer(Modifier.height(24.dp))

      // Start/Stop button
      Button(
        onClick = {
          if (isRunning) {
            FileServerService.stop(context)
            isRunning = false
            serverUrl = null
          } else {
            if (rootPath.isBlank()) {
              Toast.makeText(context, "Select a directory first", Toast.LENGTH_SHORT).show()
              return@Button
            }
            val p = port.toIntOrNull() ?: FileServerService.DEFAULT_PORT
            FileServerService.start(context, rootPath, p)
            isRunning = true
            // Small delay to get the URL
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
              serverUrl = FileServerService.currentUrl()
            }, 500)
          }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = if (isRunning) {
          ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
          ButtonDefaults.buttonColors()
        },
      ) {
        Text(if (isRunning) "Stop Server" else "Start Server")
      }

      Spacer(Modifier.height(16.dp))

      // Help text
      Text(
        "Serves files from the selected directory over your local network. " +
          "Other devices on the same WiFi can access files by opening the URL in a browser.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun uriToPath(uri: Uri): String? {
  // Try to extract real path from content URI
  val docId = try {
    android.provider.DocumentsContract.getTreeDocumentId(uri)
  } catch (_: Exception) {
    return null
  }
  // Primary storage: "primary:Download"
  val parts = docId.split(":")
  return if (parts.size == 2 && parts[0] == "primary") {
    "/storage/emulated/0/${parts[1]}"
  } else if (parts.size == 2) {
    "/storage/${parts[0]}/${parts[1]}"
  } else {
    null
  }
}
