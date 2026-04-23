package com.aiope2.feature.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiope2.feature.remote.db.RemoteServerDao
import com.aiope2.feature.remote.db.RemoteServerEntity
import com.aiope2.feature.remote.ssh.DeployUseCase
import com.aiope2.feature.remote.ssh.SshSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
  private val serverDao: RemoteServerDao,
  private val sshManager: SshSessionManager,
  private val deployUseCase: DeployUseCase,
) : ViewModel() {

  val servers = serverDao.getAll()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _isDeploying = MutableStateFlow(false)
  val isDeploying = _isDeploying.asStateFlow()

  private val _deployError = MutableStateFlow<String?>(null)
  val deployError = _deployError.asStateFlow()

  fun clearError() {
    _deployError.value = null
  }

  fun isConnected(server: RemoteServerEntity): Boolean = sshManager.isConnected(server.id)

  fun addServer(name: String, host: String, user: String, port: Int, privateKey: String?, publicKey: String?) {
    viewModelScope.launch {
      serverDao.upsert(
        RemoteServerEntity(
          id = UUID.randomUUID().toString(),
          name = name,
          host = host,
          user = user,
          bootstrapPort = port,
          privateKey = privateKey,
          publicKey = publicKey,
        ),
      )
    }
  }

  fun updateServer(id: String, name: String, host: String, user: String, port: Int, privateKey: String?, publicKey: String?) {
    viewModelScope.launch {
      val existing = serverDao.getById(id) ?: return@launch
      serverDao.upsert(
        existing.copy(
          name = name,
          host = host,
          user = user,
          bootstrapPort = port,
          privateKey = privateKey,
          publicKey = publicKey,
        ),
      )
    }
  }

  fun addAndDeploy(name: String, host: String, user: String, port: Int, privateKey: String?, publicKey: String?) {
    val id = UUID.randomUUID().toString()
    viewModelScope.launch {
      val server = RemoteServerEntity(
        id = id,
        name = name,
        host = host,
        user = user,
        bootstrapPort = port,
        privateKey = privateKey,
        publicKey = publicKey,
        status = "deploying",
      )
      serverDao.upsert(server)
      deploy(server)
    }
  }

  fun redeployServer(server: RemoteServerEntity) {
    viewModelScope.launch { deploy(server) }
  }

  private suspend fun deploy(server: RemoteServerEntity) {
    _isDeploying.value = true
    _deployError.value = null
    try {
      deployUseCase.deploy(server)
    } catch (e: Exception) {
      _deployError.value = "Deploy error: ${e.message}"
      serverDao.updateStatus(server.id, "error")
    } finally {
      _isDeploying.value = false
    }
  }

  fun connectServer(server: RemoteServerEntity) {
    viewModelScope.launch {
      try {
        _deployError.value = null
        sshManager.connect(server)
        serverDao.updateStatus(server.id, "online")
      } catch (e: Exception) {
        _deployError.value = "Connect error: ${e.message}"
        serverDao.updateStatus(server.id, "error")
      }
    }
  }

  fun disconnectServer(server: RemoteServerEntity) {
    viewModelScope.launch {
      sshManager.disconnect(server.id)
      serverDao.updateStatus(server.id, "offline")
    }
  }

  fun deleteServer(server: RemoteServerEntity) {
    viewModelScope.launch {
      sshManager.disconnect(server.id)
      serverDao.delete(server)
    }
  }
}
