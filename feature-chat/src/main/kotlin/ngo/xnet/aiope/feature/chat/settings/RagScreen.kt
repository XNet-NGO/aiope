package ngo.xnet.aiope.feature.chat.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xnet.aiope.inference.LlamaEngine
import org.xnet.aiope.inference.RagEngine
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RagScreen(onBack: () -> Unit) {
    val theme = ngo.xnet.aiope.feature.chat.theme.LocalThemeState.current
    val scaffoldColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ragEngine by remember { mutableStateOf<RagEngine?>(null) }
    var documents by remember { mutableStateOf<List<RagEngine.DocumentInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var indexing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    // Initialize RagEngine and load documents
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val engine = LlamaEngine()
                val modelFile = File(context.filesDir, "models/all-MiniLM-L6-v2-Q4_K_M.gguf")
                if (!modelFile.exists()) {
                    modelFile.parentFile?.mkdirs()
                    context.assets.open("models/all-MiniLM-L6-v2-Q4_K_M.gguf").use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                engine.loadModel(modelFile.absolutePath, contextSize = 256, nThreads = 4, embedding = true)
                val rag = RagEngine(context, engine)
                ragEngine = rag
                documents = rag.listDocuments()
            } catch (e: Exception) {
                status = "Error: ${e.message?.take(60)}"
            }
            loading = false
        }
    }

    fun refresh() {
        ragEngine?.let { documents = it.listDocuments() }
    }

    // File picker for upload
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            indexing = true
            status = "Indexing..."
            scope.launch(Dispatchers.IO) {
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
                    val mimeType = context.contentResolver.getType(uri) ?: ""
                    val text = if (mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                        try {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() } ?: byteArrayOf()
                            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
                            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes)
                            val extracted = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                            doc.close()
                            extracted
                        } catch (e: Exception) {
                            ""
                        }
                    } else {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    }
                    if (text.isBlank()) {
                        withContext(Dispatchers.Main) { status = "File was empty or unreadable" }
                    } else {
                        ragEngine?.indexDocument(title = name, content = text, source = "upload")
                        withContext(Dispatchers.Main) {
                            status = "Indexed: $name (${text.length} chars)"
                            refresh()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { status = "Error: ${e.message?.take(60)}" }
                }
                withContext(Dispatchers.Main) { indexing = false }
            }
        }
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Documents") },
            text = { Text("This will remove all indexed documents and their embeddings. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAllDialog = false
                    scope.launch(Dispatchers.IO) {
                        ragEngine?.deleteAllDocuments()
                        withContext(Dispatchers.Main) {
                            refresh()
                            status = "All documents deleted"
                        }
                    }
                }) { Text("Delete All", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = scaffoldColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("RAG Documents") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (theme.useBackground) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { fileLauncher.launch("*/*") },
                        enabled = !indexing && !loading
                    ) {
                        Icon(Icons.Default.Add, "Upload document")
                    }
                    if (documents.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(Icons.Default.Delete, "Delete all")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            // Status bar
            if (status.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Loading RAG engine...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (indexing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Indexing document...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (documents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No documents indexed", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap + to upload a text file for indexing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(documents, key = { it.id }) { doc ->
                        ListItem(
                            headlineContent = { Text(doc.title) },
                            supportingContent = {
                                Text(
                                    "${doc.chunkCount} chunks • ${doc.createdAt.take(10)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        ragEngine?.deleteDocument(doc.id)
                                        withContext(Dispatchers.Main) {
                                            refresh()
                                            status = "Deleted: ${doc.title}"
                                        }
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
