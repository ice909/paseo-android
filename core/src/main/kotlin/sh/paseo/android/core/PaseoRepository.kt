package sh.paseo.android.core

import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** One active host, one catalog subscription, and one viewed timeline. No Android dependencies. */
class PaseoRepository(private val socket: DaemonSocket = DaemonSocket()) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(PaseoState())
    val state = _state.asStateFlow()
    private val buffer = TimelineBuffer()
    private var connectionJob: Job? = null
    private var timelineJob: Job? = null
    private var publishJob: Job? = null
    private var timelineSubscription: String? = null
    private var catalogSubscription: String? = null

    init { scope.launch { socket.messages.collect { message ->
        try { handle(message) } catch (error: Exception) { report(error) }
    } } }

    fun connect(host: Host) {
        connectionJob?.cancel()
        socket.disconnect()
        timelineJob?.cancel()
        publishJob?.cancel()
        connectionJob = scope.launch {
            val selected = if (state.value.host?.id == host.id) state.value.selectedAgentId else null
            buffer.clear()
            _state.value = PaseoState(host = host, selectedAgentId = selected, connection = ConnectionStatus.Connecting)
            var attempt = 0
            while (isActive) {
                try {
                    catalogSubscription = null
                    timelineSubscription = null
                    val info = socket.open(host)
                    _state.update { it.copy(connection = ConnectionStatus.Connected, serverName = info.string("hostname").ifBlank { host.name }, error = null) }
                    coroutineScope {
                        launch { fetchAgents(subscribe = true) }
                        launch {
                            val result = socket.request("list_available_providers_request")
                            val providers = result.array("providers").map { wireJson.decodeFromJsonElement<Provider>(it) }
                            _state.update { it.copy(providers = providers) }
                        }
                    }
                    state.value.selectedAgentId?.let { selectAgent(it) }
                    attempt = 0
                    socket.connected.first { !it }
                } catch (cancelled: CancellationException) {
                    if (!isActive) throw cancelled
                    _state.update { it.copy(error = "连接或请求超时") }
                } catch (error: AuthenticationException) {
                    _state.update { it.copy(connection = ConnectionStatus.Disconnected, error = error.message) }
                    return@launch
                } catch (error: Exception) {
                    _state.update { it.copy(error = error.message ?: "连接失败") }
                }
                socket.disconnect()
                timelineJob?.cancel()
                publishJob?.cancel()
                _state.update { it.copy(connection = ConnectionStatus.Reconnecting, loadingTimeline = false) }
                delay(min(30_000L, 1_000L shl min(attempt++, 5)))
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        timelineJob?.cancel()
        publishJob?.cancel()
        socket.disconnect()
        _state.update { it.copy(connection = ConnectionStatus.Disconnected, loadingTimeline = false, busy = false) }
    }
    fun clearError() { _state.update { it.copy(error = null) } }

    suspend fun refreshAgents(loadMore: Boolean = false) = withContext(dispatcher) {
        fetchAgents(cursor = if (loadMore) state.value.nextAgentCursor else null)
    }
    private suspend fun fetchAgents(subscribe: Boolean = false, cursor: String? = null) {
        val result = socket.request("fetch_agents_request",
            "scope" to "active", "page" to jsonObject("limit" to 100, "cursor" to cursor),
            "sort" to listOf(jsonObject("key" to "updated_at", "direction" to "desc")),
            "subscribe" to if (subscribe) jsonObject() else null,
        )
        if (subscribe) catalogSubscription = result.string("subscriptionId").ifBlank { null }
        val agents = result.array("entries").map { wireJson.decodeFromJsonElement<Agent>(it.jsonObject.obj("agent")) }
        _state.update { current -> current.copy(
            agents = ((if (cursor != null) current.agents else emptyList()) + agents).distinctBy { it.id }.filter { it.archivedAt == null },
            nextAgentCursor = result.obj("pageInfo").string("nextCursor").ifBlank { null },
        ) }
    }

    fun selectAgent(agentId: String) {
        timelineJob?.cancel()
        timelineJob = scope.launch {
            val changed = state.value.selectedAgentId != agentId
            if (changed) buffer.clear()
            _state.update { it.copy(selectedAgentId = agentId, timeline = if (changed) emptyList() else it.timeline, loadingTimeline = true, hasOlder = false) }
            try {
                releaseTimeline()
                val subscription = socket.request("agent.timeline.set_subscription.request", "agentIds" to listOf(agentId))
                timelineSubscription = subscription.string("subscriptionId").ifBlank { null }
                val payload = socket.request("fetch_agent_timeline_request", "agentId" to agentId,
                    "direction" to "tail", "limit" to 200, "projection" to "canonical")
                if (state.value.selectedAgentId == agentId) {
                    buffer.hydrate(payload)
                    payload["agent"]?.takeIf { it is JsonObject }?.let { upsert(wireJson.decodeFromJsonElement<Agent>(it)) }
                    publishTimeline()
                }
            } catch (error: Exception) { report(error) }
            finally {
                if (state.value.selectedAgentId == agentId) _state.update { it.copy(loadingTimeline = false) }
            }
        }
    }
    fun leaveAgent() {
        timelineJob?.cancel()
        scope.launch {
            _state.update { it.copy(selectedAgentId = null, timeline = emptyList(), hasOlder = false) }
            buffer.clear()
            try { releaseTimeline() } catch (_: Exception) { /* Connection cleanup also releases it. */ }
        }
    }
    private suspend fun releaseTimeline() {
        val id = timelineSubscription
        timelineSubscription = null
        if (id != null && state.value.connected) socket.request("subscription.release.request", "subscriptionId" to id)
    }

    suspend fun loadOlder() = withContext(dispatcher) {
        val current = state.value
        val id = current.selectedAgentId ?: return@withContext
        val seq = buffer.startSeq ?: return@withContext
        if (current.loadingTimeline || !current.hasOlder) return@withContext
        _state.update { it.copy(loadingTimeline = true) }
        try {
            val payload = socket.request("fetch_agent_timeline_request", "agentId" to id,
                "direction" to "before", "cursor" to jsonObject("epoch" to buffer.epoch, "seq" to seq),
                "limit" to 200, "projection" to "canonical")
            if (state.value.selectedAgentId == id) { buffer.hydrate(payload); publishTimeline() }
        } finally { _state.update { it.copy(loadingTimeline = false) } }
    }

    suspend fun createAgent(provider: String, cwd: String, model: String, prompt: String, title: String): Agent = withContext(dispatcher) {
        require(provider.isNotBlank() && cwd.startsWith('/')) { "请选择提供方并填写主机上的绝对目录" }
        val result = socket.request("create_agent_request",
            "config" to jsonObject("provider" to provider, "cwd" to cwd, "model" to model.ifBlank { null }, "title" to title.ifBlank { null }),
            "initialPrompt" to prompt.ifBlank { null }, "clientMessageId" to newId(),
        )
        if (result.string("status") == "agent_create_failed") throw IOException(result.string("error"))
        val agent = wireJson.decodeFromJsonElement<Agent>(result.getValue("agent"))
        upsert(agent)
        selectAgent(agent.id)
        agent
    }
    suspend fun models(provider: String, cwd: String): List<ModelOption> {
        val result = socket.request("list_provider_models_request", "provider" to provider, "cwd" to cwd.ifBlank { null })
        return result.array("models").map { wireJson.decodeFromJsonElement<ModelOption>(it) }
    }
    suspend fun sendMessage(agentId: String, text: String, messageId: String) {
        require(text.isNotBlank())
        socket.request("send_agent_message_request", "agentId" to agentId, "text" to text, "messageId" to messageId)
    }
    suspend fun cancelAgent(agentId: String) { socket.request("cancel_agent_request", "agentId" to agentId) }
    suspend fun archiveAgent(agentId: String) = withContext(dispatcher) {
        socket.request("archive_agent_request", "agentId" to agentId)
        _state.update { it.copy(agents = it.agents.filterNot { agent -> agent.id == agentId }) }
        if (state.value.selectedAgentId == agentId) leaveAgent()
    }
    suspend fun renameAgent(agentId: String, name: String) {
        socket.request("update_agent_request", "agentId" to agentId, "name" to name)
    }
    suspend fun respondToPermission(agentId: String, request: PermissionRequest, behavior: String, actionId: String? = null) {
        // The permission ID is the correlation ID; this message has no separate RPC receipt.
        socket.send("agent_permission_response", "agentId" to agentId, "requestId" to request.id,
            "response" to jsonObject("behavior" to behavior, "selectedActionId" to actionId))
    }
    suspend fun files(cwd: String, path: String = "", file: Boolean = false): JsonObject = socket.request(
        "file_explorer_request", "cwd" to cwd, "path" to path, "mode" to if (file) "file" else "list", "maxBytes" to 1_048_576,
    )
    suspend fun saveFile(cwd: String, file: JsonObject, content: String): JsonObject {
        val result = socket.request("fs.file.write.request", "cwd" to cwd, "path" to file.string("path"),
            "content" to content, "expectedModifiedAt" to file.string("modifiedAt"), "expectedRevision" to file.string("revision").ifBlank { null })
        val status = result.obj("result")
        when (status.string("status")) {
            "conflict" -> throw IOException("文件已在主机上修改，请重新打开后再编辑")
            "error" -> throw IOException(status.string("error"))
        }
        return status
    }
    suspend fun diff(cwd: String): JsonObject = socket.request("checkout.diff.get.request", "cwd" to cwd, "compare" to jsonObject("mode" to "uncommitted"))
    suspend fun terminals(agent: Agent): List<JsonObject> = socket.request("list_terminals_request", "cwd" to agent.cwd,
        "workspaceId" to agent.workspaceId).array("terminals").map { it.jsonObject }
    suspend fun createTerminal(agent: Agent): JsonObject = socket.request("create_terminal_request", "cwd" to agent.cwd,
        "workspaceId" to agent.workspaceId, "name" to "Android").obj("terminal")
    suspend fun captureTerminal(id: String): String = socket.request("capture_terminal_request", "terminalId" to id,
        "start" to -300, "stripAnsi" to true).array("lines").joinToString("\n") { it.jsonPrimitive.content }
    fun terminalInput(id: String, data: String) = socket.send("terminal_input", "terminalId" to id, "message" to jsonObject("type" to "input", "data" to data))

    private fun upsert(agent: Agent) {
        _state.update { current -> current.copy(agents = (current.agents.filterNot { it.id == agent.id } +
            if (agent.archivedAt == null) listOf(agent) else emptyList()).sortedByDescending { it.updatedAt }) }
    }
    private fun permission(agentId: String, request: PermissionRequest?, resolvedId: String = "") {
        _state.update { current -> current.copy(agents = current.agents.map { agent ->
            if (agent.id != agentId) agent else agent.copy(pendingPermissions =
                agent.pendingPermissions.filterNot { it.id == (request?.id ?: resolvedId) } + listOfNotNull(request))
        }) }
    }
    private fun handle(message: JsonObject) {
        val payload = message.obj("payload")
        val agentId = payload.string("agentId")
        when (message.string("type")) {
            "agent_update" -> if (payload.string("kind") == "upsert") upsert(wireJson.decodeFromJsonElement<Agent>(payload.obj("agent")))
                else _state.update { it.copy(agents = it.agents.filterNot { agent -> agent.id == agentId }) }
            "agent_status" -> upsert(wireJson.decodeFromJsonElement<Agent>(payload.obj("info")))
            "agent_permission_request" -> permission(agentId, wireJson.decodeFromJsonElement<PermissionRequest>(payload.obj("request")))
            "agent_permission_resolved" -> permission(agentId, null, payload.string("requestId"))
            "agent.timeline.replacement" -> if (state.value.selectedAgentId == agentId) { buffer.clear(); selectAgent(agentId) }
            "agent_stream" -> {
                val event = payload.obj("event")
                when (event.string("type")) {
                    "timeline" -> if (state.value.selectedAgentId == agentId) {
                        if (buffer.append(payload)) { buffer.clear(); selectAgent(agentId) }
                        else if (publishJob?.isActive != true) publishJob = scope.launch { delay(50); publishTimeline() }
                    }
                    "permission_requested" -> permission(agentId, wireJson.decodeFromJsonElement<PermissionRequest>(event.obj("request")))
                    "permission_resolved" -> permission(agentId, null, event.string("requestId"))
                    "turn_started", "turn_completed", "turn_canceled", "turn_failed" -> {
                        _state.update { current -> current.copy(agents = current.agents.map {
                            if (it.id != agentId) it else it.copy(status = when (event.string("type")) {
                                "turn_started" -> "running"; "turn_failed" -> "error"; else -> "idle"
                            })
                        }) }
                        if (agentId == state.value.selectedAgentId) publishTimeline()
                    }
                }
            }
        }
    }
    private fun publishTimeline() { _state.update { it.copy(timeline = buffer.rows(), hasOlder = buffer.hasOlder) } }
    private fun report(error: Exception) {
        if (error is TimeoutCancellationException) { _state.update { it.copy(error = "主机响应超时") }; return }
        if (error is CancellationException) throw error
        _state.update { it.copy(error = error.message ?: "请求失败") }
    }
    fun close() { scope.cancel(); socket.close() }
}
