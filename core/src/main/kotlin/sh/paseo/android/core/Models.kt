package sh.paseo.android.core

import java.net.URI
import java.net.URLDecoder
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
data class Host(val id: String = newId(), val name: String, val url: String, val password: String = "")

data class Endpoint(val url: String, val password: String? = null) {
    companion object {
        /** Supports official direct-pairing links, IPv6 and reverse-proxy path prefixes. */
        fun parse(input: String): Endpoint {
            val raw = input.trim().substringAfter("#connect=", input.trim()).let {
                if (it.startsWith("tcp%", true) || it.startsWith("relay%", true))
                    URLDecoder.decode(it, "UTF-8") else it
            }
            require(raw.isNotBlank()) { "请输入主机地址" }
            require(!raw.startsWith("relay:", true)) { "中继配对尚未支持，请使用直连地址或 Tailscale 地址" }
            val uri = URI(if ("://" in raw) raw else "ws://$raw")
            val query = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                "tcp" -> if (query["ssl"] == "true") "wss" else "ws"
                else -> error("支持 ws、wss、http、https 或 tcp 地址")
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "主机地址格式不正确" }
            require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
            val path = uri.path.orEmpty().trimEnd('/').let { if (it.endsWith("/ws")) it else "$it/ws" }
            val retainedQuery = uri.rawQuery?.split('&')?.filter {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") !in setOf("password", "ssl")
            }?.joinToString("&")?.ifBlank { null }
            val authority = uri.rawAuthority
            return Endpoint("$scheme://$authority$path" + (retainedQuery?.let { "?$it" } ?: ""), query["password"])
        }
    }
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
    val title: String? = null, val status: String = "idle",
    val updatedAt: String = "", val archivedAt: String? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val lastError: String? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "新会话"
    val running: Boolean get() = status in setOf("running", "initializing")
}
@Serializable
data class Provider(val provider: String, val available: Boolean = false, val error: String? = null)
@Serializable
data class ModelOption(val id: String, val label: String = "", val name: String = "") {
    val displayName: String get() = label.ifBlank { name.ifBlank { id } }
}
data class TimelineRow(
    val key: String, val type: String, val text: String = "", val title: String = "",
    val status: String = "", val detail: JsonObject = jsonObject(),
    val messageId: String = "", val turnId: String = "",
)
data class TimelinePage(
    val epoch: String, val startSeq: Long?, val endSeq: Long?, val hasOlder: Boolean,
)
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting }
data class PaseoState(
    val host: Host? = null,
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val serverName: String = "",
    val agents: List<Agent> = emptyList(),
    val nextAgentCursor: String? = null,
    val providers: List<Provider> = emptyList(),
    val selectedAgentId: String? = null,
    val timeline: List<TimelineRow> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingTimeline: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val selectedAgent: Agent? get() = agents.find { it.id == selectedAgentId }
    val connected: Boolean get() = connection == ConnectionStatus.Connected
}
