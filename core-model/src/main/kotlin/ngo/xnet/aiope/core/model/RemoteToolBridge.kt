package ngo.xnet.aiope.core.model

interface RemoteToolBridge {
  data class ToolDef(val name: String, val description: String, val parameters: String, val parallel: Boolean = false)
  fun buildToolDefs(): List<ToolDef>
  suspend fun execute(name: String, args: Map<String, Any?>): String
  suspend fun buildSystemContext(): String
  suspend fun disconnectAll()
  fun isConnected(serverId: String): Boolean
}
