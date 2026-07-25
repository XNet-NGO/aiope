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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                // Use cloud embeddings via gateway
                val taskStore = ngo.xnet.aiope.core.network.TaskModelStore(context)
                val tc = taskStore.getTaskConfig(ngo.xnet.aiope.core.network.ModelTask.RAG)
                val cloudEmbed = org.xnet.aiope.inference.CloudEmbeddingEngine(
                    baseUrl = "https://inf.xnet.ngo/v1",
                    apiKey = ngo.xnet.aiope.feature.chat.BuildConfig.GATEWAY_KEY,
                    model = (tc.modelId ?: "").let { if (it.endsWith(".tflite") || it.endsWith(".litertlm") || it.isBlank()) "google-ai-studio/models-gemini-embedding-2" else it }
                )
                val rag = RagEngine(context) { text -> cloudEmbed.embed(text) }
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
                        IconButton(onClick = {
                            // Reindex all documents with current embedding model
                            indexing = true
                            status = "Re-indexing all documents..."
                            scope.launch(Dispatchers.IO) {
                                try {
                                    ragEngine?.reindexAll()
                                    withContext(Dispatchers.Main) {
                                        status = "Re-indexed ${documents.size} documents"
                                        indexing = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        status = "Re-index error: ${e.message?.take(40)}"
                                        indexing = false
                                    }
                                }
                            }
                        }, enabled = !indexing) {
                            Icon(Icons.Default.Refresh, "Re-index all")
                        }
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
            // Search bar
            var searchQuery by remember { mutableStateOf("") }
            var searchResults by remember { mutableStateOf<List<org.xnet.aiope.inference.RagEngine.SearchResult>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search knowledge base") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (searchQuery.isNotBlank() && ragEngine != null) {
                            searching = true
                            scope.launch(Dispatchers.IO) {
                                val results = ragEngine!!.search(searchQuery, topK = 5)
                                withContext(Dispatchers.Main) {
                                    searchResults = results
                                    searching = false
                                    status = if (results.isEmpty()) "No results" else "${results.size} results"
                                }
                            }
                        }
                    },
                    enabled = !searching && searchQuery.isNotBlank()
                ) {
                    Icon(Icons.Default.Search, "Search")
                }
            }

            // Search results
            if (searchResults.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        Text("Search Results", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    items(searchResults.size) { i ->
                        val r = searchResults[i]
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("[${String.format("%.2f", r.score)}] ${r.title}", style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(r.text.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { searchResults = emptyList() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text("Clear results")
                        }
                    }
                }
            } else {
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
            } // end else (no search results)
        }
    }
}
