package com.aiope2.feature.remote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiope2.feature.remote.db.RemoteServerEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerListScreen(
  onBack: () -> Unit,
  viewModel: ServerListViewModel = hiltViewModel(),
) {
  val servers by viewModel.servers.collectAsState()
  val isDeploying by viewModel.isDeploying.collectAsState()
  val deployError by viewModel.deployError.collectAsState()
  var showSheet by remember { mutableStateOf(false) }
  var editServer by remember { mutableStateOf<RemoteServerEntity?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Remote Servers") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, "Back")
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = {
        editServer = null
        showSheet = true
      }) {
        Icon(Icons.Default.Add, "Add server")
      }
    },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad)) {
      if (isDeploying) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      deployError?.let { err ->
        Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
              err,
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") }
          }
        }
      }

      if (servers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("No servers configured", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(servers, key = { it.id }) { server ->
            val haptic = LocalHapticFeedback.current
            ServerCard(
              server = server,
              isConnected = viewModel.isConnected(server),
              onConnect = { viewModel.connectServer(server) },
              onDisconnect = { viewModel.disconnectServer(server) },
              onDelete = { viewModel.deleteServer(server) },
              onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                editServer = server
                showSheet = true
              },
            )
          }
        }
      }
    }
  }

  if (showSheet) {
    ServerEditSheet(
      server = editServer,
      onDismiss = { showSheet = false },
      onSave = { name, host, user, port, privateKey, publicKey ->
        if (editServer != null) {
          viewModel.updateServer(editServer!!.id, name, host, user, port, privateKey, publicKey)
        } else {
          viewModel.addServer(name, host, user, port, privateKey, publicKey)
        }
        showSheet = false
      },
      onDeploy = { name, host, user, port, privateKey, publicKey ->
        if (editServer != null) {
          viewModel.updateServer(editServer!!.id, name, host, user, port, privateKey, publicKey)
          viewModel.redeployServer(
            editServer!!.copy(
              name = name,
              host = host,
              user = user,
              bootstrapPort = port,
              privateKey = privateKey,
              publicKey = publicKey,
            ),
          )
        } else {
          viewModel.addAndDeploy(name, host, user, port, privateKey, publicKey)
        }
        showSheet = false
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
  server: RemoteServerEntity,
  isConnected: Boolean,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  onDelete: () -> Unit,
  onLongPress: () -> Unit,
) {
  val statusColor by animateColorAsState(
    when {
      isConnected -> MaterialTheme.colorScheme.primary
      server.status == "online" -> MaterialTheme.colorScheme.primary
      server.status == "error" -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.outlineVariant
    },
    label = "status",
  )

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = { if (isConnected) onDisconnect() else onConnect() },
        onLongClick = onLongPress,
      ),
  ) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(10.dp)
          .clip(CircleShape)
          .background(statusColor),
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(server.name, style = MaterialTheme.typography.titleSmall)
        Text(
          "${server.user}@${server.host}:${server.port}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
        )
        if (server.osInfo != null) {
          Text(
            server.osInfo!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        val hasKey = !server.privateKey.isNullOrBlank()
        Text(
          if (hasKey) "Key configured" else "No key set",
          style = MaterialTheme.typography.bodySmall,
          color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          fontSize = 11.sp,
        )
      }
      if (isConnected) {
        IconButton(onClick = onDisconnect) {
          Icon(Icons.Default.LinkOff, "Disconnect", tint = MaterialTheme.colorScheme.primary)
        }
      } else {
        IconButton(onClick = onConnect) {
          Icon(Icons.Default.Link, "Connect", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditSheet(
  server: RemoteServerEntity?,
  onDismiss: () -> Unit,
  onSave: (name: String, host: String, user: String, port: Int, privateKey: String?, publicKey: String?) -> Unit,
  onDeploy: (name: String, host: String, user: String, port: Int, privateKey: String?, publicKey: String?) -> Unit,
) {
  var name by remember { mutableStateOf(server?.name ?: "") }
  var host by remember { mutableStateOf(server?.host ?: "") }
  var user by remember { mutableStateOf(server?.user ?: "") }
  var port by remember { mutableStateOf((server?.bootstrapPort ?: 22).toString()) }
  var privateKey by remember { mutableStateOf(server?.privateKey ?: "") }
  var publicKey by remember { mutableStateOf(server?.publicKey ?: "") }

  val isEdit = server != null
  val title = if (isEdit) "Edit Server" else "Add Server"

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleLarge)

      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        placeholder = { Text("e.g. serv-2") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("Host") },
        placeholder = { Text("192.168.0.12") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = user,
          onValueChange = { user = it },
          label = { Text("User") },
          placeholder = { Text("root") },
          modifier = Modifier.weight(1f),
          singleLine = true,
        )
        OutlinedTextField(
          value = port,
          onValueChange = { port = it.filter { c -> c.isDigit() } },
          label = { Text("Port") },
          placeholder = { Text("22") },
          modifier = Modifier.width(100.dp),
          singleLine = true,
        )
      }

      HorizontalDivider()
      Text("SSH Keys", style = MaterialTheme.typography.titleSmall)
      Text(
        "Paste your private key (and optionally public key) for authentication.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedTextField(
        value = privateKey,
        onValueChange = { privateKey = it },
        label = { Text("Private Key") },
        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        maxLines = 12,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
      )
      OutlinedTextField(
        value = publicKey,
        onValueChange = { publicKey = it },
        label = { Text("Public Key (optional)") },
        placeholder = { Text("ssh-ed25519 AAAA...") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        maxLines = 4,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
      )

      Spacer(Modifier.height(8.dp))

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = {
            val p = port.toIntOrNull() ?: 22
            onSave(
              name.trim(),
              host.trim(),
              user.trim(),
              p,
              privateKey.trim().ifBlank { null },
              publicKey.trim().ifBlank { null },
            )
          },
          modifier = Modifier.weight(1f),
          enabled = name.isNotBlank() && host.isNotBlank() && user.isNotBlank(),
        ) {
          Text("Save")
        }
        Button(
          onClick = {
            val p = port.toIntOrNull() ?: 22
            onDeploy(
              name.trim(),
              host.trim(),
              user.trim(),
              p,
              privateKey.trim().ifBlank { null },
              publicKey.trim().ifBlank { null },
            )
          },
          modifier = Modifier.weight(1f),
          enabled = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && privateKey.isNotBlank(),
        ) {
          Icon(Icons.Default.Upload, null, Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Deploy")
        }
      }
    }
  }
}
