package sh.paseo.android.core

import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun jsonObject(vararg values: Pair<String, Any?>): JsonObject = JsonObject(
    values.filter { it.second != null }.associate { it.first to it.second.toJson() }
)
private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Iterable<*> -> JsonArray(map { it.toJson() })
    else -> error("Unsupported JSON value")
}
fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class Host(
    val id: String = newId(), val name: String, val url: String, val password: String = "",
    val relay: RelayOffer? = null, val automaticName: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { relay?.serverId ?: runCatching { URI(url).host }.getOrNull() ?: "Paseo 主机" }
}

@Serializable
data class PermissionAction(val id: String, val label: String, val behavior: String, val variant: String? = null)
@Serializable
data class PermissionRequest(
    val id: String, val name: String = "", val kind: String = "tool",
    val title: String? = null, val description: String? = null,
    val input: JsonObject? = null, val detail: JsonObject? = null,
    val actions: List<PermissionAction> = emptyList(),
)
@Serializable
data class Agent(
    val id: String, val provider: String = "", val cwd: String = "",
    val workspaceId: String? = null, val model: String? = null,
    val currentModeId: String? = null, val availableModes: List<ModeOption> = emptyList(),
    val title: String? = null, val status: String = "idle",
    val updatedAt: String = "", val archivedAt: String? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val lastError: String? = null, val activeTurnId: String? = null,
    val activeTurn: JsonObject? = null,
    val features: List<JsonObject> = emptyList(),
    val capabilities: JsonObject = jsonObject(), val thinkingOptionId: String? = null,
    val runtimeInfo: JsonObject? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "新会话"
    val currentTurnId: String get() = activeTurn?.string("turnId").orEmpty().ifBlank { activeTurnId.orEmpty() }
    val running: Boolean get() = activeTurn != null || status in setOf("running", "initializing")
}
@Serializable
data class Provider(val provider: String, val available: Boolean = false, val error: String? = null)
@Serializable
data class ModelOption(val id: String, val label: String = "", val name: String = "",
    val thinkingOptions: List<ModeOption> = emptyList(), val defaultThinkingOptionId: String? = null,
    val aliases: List<String> = emptyList(), val isDefault: Boolean = false, val isSelectable: Boolean = true) {
    val displayName: String get() = label.ifBlank { name.ifBlank { id } }
}
@Serializable
data class ModeOption(val id: String, val label: String = "", val description: String? = null, val isDefault: Boolean = false) {
    val displayName: String get() = label.ifBlank { id }
}
@Serializable
data class Workspace(
    val id: String, val projectId: String, val projectDisplayName: String,
    val projectRootPath: String, val workspaceDirectory: String? = null,
    val projectKind: String = "directory", val workspaceKind: String = "directory",
    val name: String, val title: String? = null, val status: String = "done",
    val pinnedAt: String? = null, val activityAt: String? = null,
    val gitRuntime: JsonObject? = null, val diffStat: JsonObject? = null, val projectKey: String? = null,
) {
    val cwd: String get() = workspaceDirectory ?: projectRootPath
    val context: WorkspaceContext get() = WorkspaceContext(id, cwd)
    fun contains(agent: Agent): Boolean = agent.workspaceId == id || (agent.workspaceId == null && agent.cwd == cwd)
}
@Serializable
data class Project(
    val projectId: String, val projectDisplayName: String, val projectRootPath: String,
    val projectKind: String = "directory", val projectKey: String? = null,
) {
    fun identityKey(serverId: String): String = projectKey?.takeIf { it.isNotBlank() } ?: JsonArray(listOf(JsonPrimitive(serverId), JsonPrimitive(projectId))).toString()
}
data class WorkspaceContext(val id: String, val cwd: String)
data class TimelineRow(
    val key: String, val type: String, val text: String = "", val title: String = "",
    val status: String = "", val detail: JsonObject = jsonObject(),
    val messageId: String = "", val turnId: String = "", val timestamp: String = "",
    val images: List<JsonObject> = emptyList(), val attachments: List<JsonObject> = emptyList(),
    val error: JsonElement? = null, val metadata: JsonObject = jsonObject(),
    val agentMessage: JsonObject? = null,
)
data class TimelinePage(
    val epoch: String, val startSeq: Long?, val endSeq: Long?, val hasOlder: Boolean,
)
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting }
data class PaseoState(
    val serverId: String = "",
    val host: Host? = null,
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val serverName: String = "", val features: JsonObject = jsonObject(), val capabilities: JsonObject = jsonObject(),
    val agents: List<Agent> = emptyList(),
    val nextAgentCursor: String? = null,
    val providers: List<Provider> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val emptyProjects: List<Project> = emptyList(),
    val nextWorkspaceCursor: String? = null,
    val selectedWorkspaceId: String? = null,
    val catalogLoaded: Boolean = false,
    val selectedAgentId: String? = null,
    val timeline: List<TimelineRow> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingTimeline: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val selectedWorkspace: Workspace? get() = workspaces.find { it.id == selectedWorkspaceId }
    val workspaceAgents: List<Agent> get() = selectedWorkspace?.let { workspace -> agents.filter(workspace::contains) }.orEmpty()
    val projects: List<Project> get() = (workspaces.map { Project(it.projectId, it.projectDisplayName, it.projectRootPath, it.projectKind, it.projectKey) } + emptyProjects).distinctBy { it.projectId }
    val selectedAgent: Agent? get() = agents.find { it.id == selectedAgentId }
    val connected: Boolean get() = connection == ConnectionStatus.Connected
}
