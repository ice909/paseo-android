package sh.paseo.android.core

import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** One active host, one catalog subscription, and one viewed timeline. No Android dependencies. */
class PaseoRepository(private val socket: DaemonSocket = DaemonSocket()) {
    companion object { const val PCM_FORMAT = "audio/pcm;rate=16000;bits=16" }
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(PaseoState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    private val buffer = TimelineBuffer()
    private var connectionJob: Job? = null
    private var timelineJob: Job? = null
    private var publishJob: Job? = null
    private var timelineSubscription: String? = null
    private var catalogSubscription: String? = null
    private var workspaceSubscription: String? = null
    private var creationLifecycle = false

    init { scope.launch { socket.messages.collect { message ->
        try { handle(message); _events.emit(message) } catch (error: Exception) { report(error) }
    } } }

    fun connect(host: Host) {
        connectionJob?.cancel()
        socket.disconnect()
        timelineJob?.cancel()
        publishJob?.cancel()
        connectionJob = scope.launch {
            val sameHost = state.value.host?.id == host.id
            val selected = state.value.selectedAgentId.takeIf { sameHost }
            val workspace = state.value.selectedWorkspaceId.takeIf { sameHost }
            buffer.clear()
            _state.value = PaseoState(host = host, selectedAgentId = selected, selectedWorkspaceId = workspace, connection = ConnectionStatus.Connecting)
            var attempt = 0
            while (isActive) {
                try {
                    catalogSubscription = null
                    timelineSubscription = null
                    workspaceSubscription = null
                    val info = socket.open(host)
                    creationLifecycle = info.obj("features").bool("creationLifecycle")
                    _state.update { it.copy(connection = ConnectionStatus.Connected, serverName = info.string("hostname").ifBlank { host.name }, features = info.obj("features"), capabilities = info.obj("capabilities"), error = null) }
                    coroutineScope {
                        launch {
                            fetchAgents(subscribe = true)
                            while (state.value.nextAgentCursor != null) fetchAgents(cursor = state.value.nextAgentCursor)
                        }
                        launch { fetchWorkspaces(subscribe = true) }
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
        if (!loadMore) while (state.value.nextAgentCursor != null) fetchAgents(cursor = state.value.nextAgentCursor)
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

    suspend fun refreshWorkspaces(loadMore: Boolean = false) = withContext(dispatcher) {
        fetchWorkspaces(cursor = if (loadMore) state.value.nextWorkspaceCursor else null)
    }
    private suspend fun fetchWorkspaces(subscribe: Boolean = false, cursor: String? = null) {
        val result = socket.request("fetch_workspaces_request",
            "page" to jsonObject("limit" to 200, "cursor" to cursor),
            "sort" to listOf(jsonObject("key" to "activity_at", "direction" to "desc")),
            "subscribe" to if (subscribe) jsonObject() else null)
        if (subscribe) workspaceSubscription = result.string("subscriptionId").ifBlank { null }
        val workspaces = result.array("entries").map { wireJson.decodeFromJsonElement<Workspace>(it) }
        _state.update { current -> current.copy(
            workspaces = ((if (cursor != null) current.workspaces else emptyList()) + workspaces).distinctBy { it.id },
            emptyProjects = if (cursor == null) result.array("emptyProjects").map { wireJson.decodeFromJsonElement<Project>(it) } else current.emptyProjects,
            nextWorkspaceCursor = result.obj("pageInfo").string("nextCursor").ifBlank { null }, catalogLoaded = true) }
    }
    fun openWorkspace(workspace: Workspace) {
        _state.update { it.copy(selectedWorkspaceId = workspace.id) }
        val agent = state.value.agents.firstOrNull { workspace.contains(it) }
        if (agent != null) selectAgent(agent.id) else leaveAgent()
    }
    private fun upsertWorkspace(workspace: Workspace) {
        _state.update { it.copy(workspaces = (it.workspaces.filterNot { w -> w.id == workspace.id } + workspace)
            .sortedWith(compareByDescending<Workspace> { it.pinnedAt != null }.thenByDescending { it.activityAt }),
            emptyProjects = it.emptyProjects.filterNot { p -> p.projectId == workspace.projectId }) }
    }
    suspend fun renameWorkspace(id: String, title: String) {
        socket.request("workspace.title.set.request", "workspaceId" to id, "title" to title)
    }
    suspend fun pinWorkspace(id: String, pinned: Boolean) {
        socket.request("workspace.pin.set.request", "workspaceId" to id, "pinned" to pinned)
    }
    suspend fun archiveWorkspace(id: String) = withContext(dispatcher) {
        socket.request("archive_workspace_request", "workspaceId" to id)
        if (state.value.selectedWorkspaceId == id) leaveAgent()
        _state.update { it.copy(workspaces = it.workspaces.filterNot { w -> w.id == id },
            selectedWorkspaceId = it.selectedWorkspaceId.takeUnless { selected -> selected == id }) }
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

    /** Callers retain the key with their draft: a lost receipt can be retried without duplicate resources. */
    suspend fun createWorkspace(provider: String, cwd: String, model: String, mode: String, prompt: String,
        title: String, key: String, projectId: String? = null, worktree: Boolean = false, branch: String = ""): Workspace = withContext(dispatcher) {
        val agent = creationAgent(provider, cwd, model, mode, prompt, title)
        val source = if (worktree) jsonObject("kind" to "worktree", "cwd" to cwd, "projectId" to projectId,
            "action" to "branch-off", "branchName" to branch.ifBlank { null })
            else jsonObject("kind" to "directory", "path" to cwd, "projectId" to projectId)
        val result = socket.request("workspace.create.request", "source" to source,
            "agent" to if (creationLifecycle) agent else null, "title" to title.ifBlank { null },
            "idempotencyKey" to if (creationLifecycle) key else null, "subscribe" to false)
        val complete = finishCreation("workspace", key, result)
        val workspace = wireJson.decodeFromJsonElement<Workspace>(complete["workspace"] ?: throw IOException("主机未返回工作区"))
        upsertWorkspace(workspace)
        _state.update { it.copy(selectedWorkspaceId = workspace.id) }
        if (creationLifecycle) {
            complete["agent"]?.takeIf { it is JsonObject }?.let { upsert(wireJson.decodeFromJsonElement<Agent>(it)); selectAgent(it.jsonObject.string("id")) }
        } else createAgent(workspace, provider, model, mode, prompt, title, "$key:agent")
        workspace
    }
    suspend fun createAgent(workspace: Workspace, provider: String, model: String, mode: String,
        prompt: String, title: String, key: String): Agent = withContext(dispatcher) {
        val fields = creationAgent(provider, workspace.cwd, model, mode, prompt, title)
        val result = socket.request(if (creationLifecycle) "agent.create.request" else "create_agent_request",
            "config" to fields["config"], "workspaceId" to workspace.id, "initialPrompt" to fields["initialPrompt"],
            "clientMessageId" to key, "idempotencyKey" to if (creationLifecycle) key else null, "subscribe" to false)
        val complete = finishCreation("agent", key, result)
        val agent = wireJson.decodeFromJsonElement<Agent>(complete["agent"] ?: throw IOException("主机未返回代理"))
        upsert(agent)
        _state.update { it.copy(selectedWorkspaceId = workspace.id) }
        selectAgent(agent.id)
        agent
    }
    private fun creationAgent(provider: String, cwd: String, model: String, mode: String, prompt: String, title: String): JsonObject {
        require(provider.isNotBlank() && cwd.startsWith('/')) { "请选择代理并填写主机上的绝对目录" }
        return jsonObject("config" to jsonObject("provider" to provider, "cwd" to cwd,
            "model" to model.ifBlank { null }, "modeId" to mode.ifBlank { null }, "title" to title.ifBlank { null }),
            "initialPrompt" to prompt.ifBlank { null })
    }
    private suspend fun finishCreation(kind: String, key: String, receipt: JsonObject): JsonObject {
        var snapshot = receipt.obj("creation")
        if (snapshot.isEmpty()) return receipt
        // Recovery is read-only; never submit a second create after a timeout.
        return withTimeout(120_000) {
            while (snapshot.string("phase") !in setOf("completed", "failed")) {
                delay(300)
                snapshot = socket.request("creation.subscribe.request", "kind" to kind, "idempotencyKey" to key,
                    "subscribe" to false).obj("snapshot")
                if (snapshot.isEmpty()) throw IOException("无法恢复创建状态，请刷新工作区后重试")
            }
            if (snapshot.string("phase") == "failed") {
                snapshot["workspace"]?.takeIf { it is JsonObject }?.let { upsertWorkspace(wireJson.decodeFromJsonElement<Workspace>(it)) }
                throw IOException(snapshot.string("error").ifBlank { "创建失败" })
            }
            snapshot
        }
    }
    suspend fun modes(provider: String, cwd: String): List<ModeOption> = socket.request("list_provider_modes_request",
        "provider" to provider, "cwd" to cwd.ifBlank { null }).array("modes").map { wireJson.decodeFromJsonElement<ModeOption>(it) }
    suspend fun setModel(id: String, model: String) { socket.request("set_agent_model_request", "agentId" to id, "modelId" to model) }
    suspend fun setMode(id: String, mode: String) { socket.request("set_agent_mode_request", "agentId" to id, "modeId" to mode) }
    suspend fun commands(id: String): List<JsonObject> = socket.request("list_commands_request", "agentId" to id).array("commands").map { it.jsonObject }
    suspend fun directorySuggestions(query: String): List<JsonObject> = socket.request("directory_suggestions_request",
        "query" to query, "includeDirectories" to true, "includeFiles" to false, "limit" to 8).array("entries").map { it.jsonObject }
    suspend fun forgeSearch(cwd: String, query: String): List<JsonObject> = socket.request(
        if (state.value.features.bool("forgeSearch")) "forge.search.request" else "github_search_request",
        "cwd" to cwd, "query" to query, "limit" to 20).array("items").map { it.jsonObject }
    suspend fun fileSuggestions(cwd: String, query: String): List<JsonObject> = socket.request("directory_suggestions_request",
        "cwd" to cwd, "query" to query, "includeFiles" to true, "includeDirectories" to true, "matchMode" to "fuzzy", "limit" to 8).array("entries").map { it.jsonObject }
    suspend fun models(provider: String, cwd: String): List<ModelOption> {
        val result = socket.request("list_provider_models_request", "provider" to provider, "cwd" to cwd.ifBlank { null })
        return result.array("models").map { wireJson.decodeFromJsonElement<ModelOption>(it) }
    }
    suspend fun sendMessage(agentId: String, text: String, messageId: String, behavior: String = "steer",
        images: List<JsonObject> = emptyList(), attachments: List<JsonObject> = emptyList(), expectedHostId: String? = null): String = withContext(dispatcher) {
        check(expectedHostId == null || state.value.host?.id == expectedHostId) { "当前主机已切换，请返回原会话重试" }
        require(text.isNotBlank() || images.isNotEmpty() || attachments.isNotEmpty())
        require(behavior in setOf("steer", "interrupt"))
        val result = socket.request("send_agent_message_request", "agentId" to agentId, "text" to text,
            "messageId" to messageId, "activeTurnBehavior" to behavior, "images" to images, "attachments" to attachments)
        if (result.string("deliveryStatus") == "queued") "queued" else "accepted"
    }
    suspend fun setFeature(id: String, feature: String, value: JsonElement) { socket.request("set_agent_feature_request", "agentId" to id, "featureId" to feature, "value" to value) }
    suspend fun setThinking(id: String, option: String?) { socket.request("set_agent_thinking_request", "agentId" to id, "thinkingOptionId" to (option?.let(::JsonPrimitive) ?: JsonNull)) }
    suspend fun rewindAgent(id: String, messageId: String, mode: String) {
        require(mode in setOf("conversation", "files", "both"))
        val result = socket.request("agent.rewind.request", "agentId" to id, "messageId" to messageId, "mode" to mode)
        check(result.bool("ok")) { result.string("error").ifBlank { "回退失败" } }
        if (mode != "files" && state.value.selectedAgentId == id) selectAgent(id)
    }
    fun startDictation(id: String) = socket.send("dictation_stream_start", "dictationId" to id, "format" to PCM_FORMAT)
    fun dictationChunk(id: String, seq: Int, bytes: ByteArray) = socket.send("dictation_stream_chunk", "dictationId" to id, "seq" to seq, "audio" to b64(bytes), "format" to PCM_FORMAT)
    fun finishDictation(id: String, finalSeq: Int) = socket.send("dictation_stream_finish", "dictationId" to id, "finalSeq" to finalSeq)
    fun cancelDictation(id: String) = socket.send("dictation_stream_cancel", "dictationId" to id)
    suspend fun setVoiceMode(enabled: Boolean, id: String) { socket.request("set_voice_mode", "enabled" to enabled, "agentId" to id) }
    fun voiceChunk(bytes: ByteArray, last: Boolean = false) = socket.send("voice_audio_chunk", "audio" to b64(bytes), "format" to PCM_FORMAT, "isLast" to last)
    fun audioPlayed(id: String) = socket.send("audio_played", "id" to id)
    fun abortVoice() = socket.send("abort_request")
    suspend fun uploadFile(attachment: ChatAttachment, expectedHostId: String): JsonObject = withContext(dispatcher) {
        check(state.value.host?.id == expectedHostId) { "当前主机已切换" }
        val generation = socket.connectionToken()
        withContext(Dispatchers.IO) { java.io.File(attachment.localPath).inputStream().use { socket.uploadFile(attachment.name, attachment.mimeType, attachment.size, it, generation) } }
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
    suspend fun respondToPermission(agentId: String, request: PermissionRequest, behavior: String, actionId: String? = null, updatedInput: JsonObject? = null) {
        // The permission ID is the correlation ID; this message has no separate RPC receipt.
        socket.send("agent_permission_response", "agentId" to agentId, "requestId" to request.id,
            "response" to jsonObject("behavior" to behavior, "selectedActionId" to actionId, "updatedInput" to updatedInput))
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
    suspend fun terminals(agent: WorkspaceContext): List<JsonObject> = socket.request("list_terminals_request", "cwd" to agent.cwd,
        "workspaceId" to agent.id).array("terminals").map { it.jsonObject }
    suspend fun createTerminal(agent: WorkspaceContext): JsonObject = socket.request("create_terminal_request", "cwd" to agent.cwd,
        "workspaceId" to agent.id, "name" to "Android").obj("terminal")
    suspend fun closeTerminal(id: String) { socket.request("kill_terminal_request", "terminalId" to id) }
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
            "workspace_update" -> if (payload.string("kind") == "upsert") upsertWorkspace(wireJson.decodeFromJsonElement<Workspace>(payload.obj("workspace")))
                else {
                    val id = payload.string("id")
                    if (state.value.selectedWorkspaceId == id) leaveAgent()
                    _state.update { current -> current.copy(workspaces = current.workspaces.filterNot { it.id == id || it.projectId == payload.string("removedProjectId") },
                        selectedWorkspaceId = current.selectedWorkspaceId.takeUnless { it == id },
                        emptyProjects = (current.emptyProjects.filterNot { it.projectId == payload.string("removedProjectId") } +
                            listOfNotNull(payload["emptyProject"]?.takeIf { it is JsonObject }?.let { wireJson.decodeFromJsonElement<Project>(it) })).distinctBy { it.projectId }) }
                }
            "agent_update" -> if (payload.string("kind") == "upsert") upsert(wireJson.decodeFromJsonElement<Agent>(payload.obj("agent")))
                else {
                    val removed = agentId.ifBlank { payload.string("id") }
                    if (state.value.selectedAgentId == removed) leaveAgent()
                    _state.update { it.copy(agents = it.agents.filterNot { agent -> agent.id == removed }) }
                }
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
                            }, activeTurn = null, activeTurnId = event.string("turnId").takeIf { _ -> event.string("type") == "turn_started" })
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
