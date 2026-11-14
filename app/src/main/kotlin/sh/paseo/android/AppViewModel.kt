package sh.paseo.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.paseo.android.appearance.AppearanceStore
import sh.paseo.android.appearance.ThemePreference
import sh.paseo.android.core.*
import sh.paseo.android.data.ConversationAudio
import sh.paseo.android.data.ChatStore
import sh.paseo.android.data.ChatAttachments
import sh.paseo.android.data.HostStore
import sh.paseo.android.data.LocalNetworkAccess
import sh.paseo.android.data.WorkspaceSelectionStore
import sh.paseo.android.data.PreviewUpdateState
import sh.paseo.android.data.PreviewUpdates

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = PaseoRepository()
    val state = repository.state
    private val appearance = AppearanceStore(application)
    val theme = appearance.theme
    fun setTheme(value: ThemePreference) {
        appearance.select(value)
        action { appearance.persist() }
    }
    private val store = HostStore(application)
    private val workspaceSelectionStore = WorkspaceSelectionStore(application)
    private val storageMutex = Mutex()
    val hosts = MutableStateFlow<List<Host>>(emptyList())
    private val _screen = MutableStateFlow("hosts")
    val screen = _screen.asStateFlow()
    val initialized = MutableStateFlow(false)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val creating = MutableStateFlow(false)
    val localNetworkPermissionRequest = MutableStateFlow<Host?>(null)
    private var connectionRequestJob: Job? = null
    private var foregroundVerificationJob: Job? = null
    private var navigationRevision = 0L
    private var networkPermissionResult: CompletableDeferred<Boolean>? = null
    private var pairingJob: Job? = null
    private var pairingGeneration = 0
    val pairingBusy = MutableStateFlow(false)
    val pairingError = MutableStateFlow<String?>(null)
    val pairingPasswordNeeded = MutableStateFlow(false)
    private val chatStore = ChatStore(application)
    private val previewUpdates = PreviewUpdates(application)
    val previewUpdateState = MutableStateFlow(PreviewUpdateState())
    val appVersionName: String = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
    val dismissedPreviewUpdateVersion = MutableStateFlow(-1L)
    fun dismissPreviewUpdate(versionCode: Long) {
        dismissedPreviewUpdateVersion.value = versionCode
    }
    val sendBehavior = MutableStateFlow(chatStore.behavior)
    val conversationDisplayMode = MutableStateFlow(chatStore.conversationDisplayMode)
    fun setConversationDisplayMode(value: ConversationDisplayMode) {
        conversationDisplayMode.value = value
        chatStore.conversationDisplayMode = value
    }
    val modEnter = MutableStateFlow(chatStore.modEnter)
    val homeSubmitRequests = MutableSharedFlow<ConversationKey>(extraBufferCapacity = 1)
    private val uploadedAttachments = mutableMapOf<Pair<String, String>, kotlinx.serialization.json.JsonObject>()
    private fun conversationMemory() = conversations.sessions.value.mapValues { ConversationMemory(it.value.draft, it.value.sentAttachments, it.value.optimistic, it.value.failed) }
    val conversations: ConversationController = ConversationController(viewModelScope, sendMessage = { key, message, behavior ->
        // Persist the outbox before any network effect; canonical echoes clear it on reconnect.
        chatStore.save(conversationMemory())
        val (images, attachments) = prepareAttachments(key, message.draft.attachments)
        repository.sendMessage(key.agentId, message.draft.text, message.id, behavior, images, attachments, key.hostId)
    }, cancelAgent = { key ->
        check(state.value.host?.id == key.hostId) { "当前主机已切换" }
        repository.cancelAgent(key.agentId)
    }).apply { behavior = sendBehavior.value }
    private suspend fun prepareAttachments(key: ConversationKey, values: List<ChatAttachment>): Pair<List<kotlinx.serialization.json.JsonObject>, List<kotlinx.serialization.json.JsonObject>> {
        val images = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val attachments = mutableListOf<kotlinx.serialization.json.JsonObject>()
        for (attachment in values) {
            when {
                attachment.isImage -> images += ChatAttachments.encodeImage(attachment)
                attachment.payload != null -> attachments += requireNotNull(attachment.payload)
                else -> {
                    val cacheKey = key.hostId to attachment.id
                    attachments += uploadedAttachments[cacheKey] ?: repository.uploadFile(attachment, key.hostId).also { uploadedAttachments[cacheKey] = it }
                }
            }
        }
        return images to attachments
    }
    val audio = ConversationAudio(application, viewModelScope, repository) { key, transcript, send ->
        if (transcript.isNotBlank()) {
            val current = conversations.session(key).draft.text
            conversations.setDraft(key, current + (if (current.isNotEmpty() && !current.last().isWhitespace()) " " else "") + transcript.trim())
            if (send) {
                if (key.agentId.startsWith("workspace-draft:")) homeSubmitRequests.tryEmit(key)
                else conversations.send(key)
            }
        }
    }
    fun setSendBehavior(value: SendBehavior) { sendBehavior.value = value; conversations.behavior = value; chatStore.behavior = value }
    fun setModEnter(value: Boolean) { modEnter.value = value; chatStore.modEnter = value }
    fun conversationKey(id: String): ConversationKey? = state.value.host?.let { ConversationKey(it.id, id) }
    val importingAttachments = MutableStateFlow<Set<ConversationKey>>(emptySet())
    private val attachmentImports = mutableMapOf<ConversationKey, Job>()
    fun attachFiles(key: ConversationKey, uris: List<android.net.Uri>) {
        importingAttachments.update { it + key }
        val previous = attachmentImports[key]
        val job = action {
            previous?.join()
            val imported = mutableListOf<ChatAttachment>()
            try { uris.forEach { imported += ChatAttachments.import(getApplication(), it) } }
            finally { if (imported.isNotEmpty()) conversations.attach(key, imported) }
        }
        attachmentImports[key] = job
        job.invokeOnCompletion {
            if (attachmentImports[key] === job) { attachmentImports.remove(key); importingAttachments.update { it - key } }
        }
    }
    fun rewind(key: ConversationKey, row: TimelineRow, mode: String) = action {
        check(state.value.host?.id == key.hostId) { "当前主机已切换" }
        repository.rewindAgent(key.agentId, row.messageId, mode)
        if (mode != "files") conversations.setDraft(key, row.text)
    }

    init {
        viewModelScope.launch { state.collect(conversations::update) }
        viewModelScope.launch {
            state.map { it.host?.id to it.serverId }.distinctUntilChanged().collect { (hostId, serverId) ->
                if (hostId == null || serverId.isNullOrBlank()) return@collect
                try {
                    storageMutex.withLock {
                        val current = hosts.value.find { it.id == hostId } ?: return@withLock
                        val (updated, merged) = mergeVerifiedHost(hosts.value, current.copy(serverId = serverId))
                        if (updated == hosts.value) return@withLock
                        store.save(updated, merged.id)
                        val removed = hosts.value.map { it.id }.toSet() - updated.map { it.id }.toSet()
                        workspaceSelectionStore.selection?.takeIf { it.hostId in removed }?.let {
                            workspaceSelectionStore.remember(it.copy(hostId = merged.id))
                        }
                        hosts.value = updated
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notices.emit("无法更新主机连接：${error.message}") }
            }
        }
        viewModelScope.launch {
            combine(state, screen, initialized) { current, destination, ready -> Triple(current, destination, ready) }
                .collect { (current, destination, ready) ->
                    if (!ready || current.restoringWorkspace != null || hosts.value.none { it.id == current.host?.id }) return@collect
                    val remembered = workspaceSelectionStore.selection
                    // Offline/partial catalogs do not prove that the saved workspace was removed.
                    if (current.connected && current.catalogLoaded && current.nextWorkspaceCursor == null &&
                        remembered?.hostId == current.host?.id && current.workspaces.none { it.id == remembered?.workspaceId }) {
                        workspaceSelectionStore.clear()
                    }
                    if (destination == "chat") {
                        current.workspaceSelection()?.let(workspaceSelectionStore::remember)
                        if (current.catalogLoaded && current.selectedWorkspaceId == null) _screen.value = "sessions"
                    }
                }
        }
        viewModelScope.launch {
            try { conversations.restoreMemory(chatStore.load()) }
            catch (error: Exception) { notices.emit("无法读取草稿：${error.message}") }
            conversations.sessions.map { sessions -> sessions.mapValues { ConversationMemory(it.value.draft, it.value.sentAttachments, it.value.optimistic, it.value.failed) } }.distinctUntilChanged().collectLatest { drafts ->
                delay(250)
                try { chatStore.save(drafts) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notices.emit("草稿保存失败：${error.message}") }
            }
        }
        val startupRevision = navigationRevision
        viewModelScope.launch {
            try {
                val saved = store.load()
                hosts.value = saved.hosts
                val remembered = workspaceSelectionStore.selection
                if (remembered != null && saved.hosts.none { it.id == remembered.hostId }) workspaceSelectionStore.clear()
                if (navigationRevision == startupRevision) {
                    val hostId = workspaceSelectionStore.selection?.hostId ?: saved.lastHostId
                    val host = saved.hosts.find { it.id == hostId } ?: saved.hosts.firstOrNull()
                    val restore = workspaceSelectionStore.selection?.takeIf { it.hostId == host?.id }
                    if (restore != null) _screen.value = "chat"
                    host?.let { repository.connect(it, restore) }
                }
            } catch (error: Exception) { notices.emit("无法读取主机配置：${error.message}") }
            finally { initialized.value = true }
        }
        checkForPreviewUpdate(manual = false)
    }
    fun checkForPreviewUpdate(manual: Boolean = true) {
        val current = previewUpdateState.value
        if (current.checking || current.downloading) return
        if (!manual && System.currentTimeMillis() - getApplication<Application>()
                .getSharedPreferences("preview_updates", 0).getLong("last_check", 0L) < 6 * 60 * 60 * 1000L) return
        previewUpdateState.value = current.copy(checking = true, message = null)
        viewModelScope.launch {
            try {
                val packageInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
                val installedVersion = PackageInfoCompat.getLongVersionCode(packageInfo)
                val update = previewUpdates.check(installedVersion)
                val downloaded = current.downloadedApk?.takeIf { it.isFile && update?.versionCode == current.update?.versionCode }
                previewUpdateState.value = PreviewUpdateState(checked = true, update = update, downloadedApk = downloaded,
                    progress = if (downloaded != null) 1f else 0f,
                    message = when {
                        downloaded != null -> "下载完成，文件校验通过"
                        manual && update == null -> "当前已是最新版本"
                        else -> null
                    })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previewUpdateState.value = PreviewUpdateState(checked = true, message = error.message ?: "检查更新失败")
            } finally {
                getApplication<Application>().getSharedPreferences("preview_updates", 0).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply()
            }
        }
    }
    fun downloadPreviewUpdate() {
        val update = previewUpdateState.value.update ?: return
        if (previewUpdateState.value.downloading) return
        previewUpdateState.value = previewUpdateState.value.copy(downloading = true, progress = 0f, message = null)
        viewModelScope.launch {
            try {
                val file = previewUpdates.download(update) { progress ->
                    previewUpdateState.value = previewUpdateState.value.copy(progress = progress)
                }
                previewUpdateState.value = previewUpdateState.value.copy(downloading = false, downloadedApk = file, progress = 1f, message = "下载完成，文件校验通过")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previewUpdateState.value = previewUpdateState.value.copy(downloading = false, message = error.message ?: "下载更新失败")
            }
        }
    }
    fun installPreviewUpdate() {
        try {
            val apk = previewUpdateState.value.downloadedApk ?: error("请先下载更新")
            val launched = previewUpdates.install(apk)
            if (!launched) previewUpdateState.value = previewUpdateState.value.copy(message = "请允许 Paseo Native 安装未知应用，然后再次点击“安装更新”。")
        } catch (error: Exception) {
            previewUpdateState.value = previewUpdateState.value.copy(message = error.message ?: "无法启动安装程序")
        }
    }
    fun connect(host: Host) {
        val restore = workspaceSelectionStore.selection?.takeIf { it.hostId == host.id }
        connectTo(host, if (restore == null) "sessions" else "chat", restore)
    }
    fun onAppForegrounded() {
        val current = state.value
        current.host?.let { active -> hosts.value.find { it.id == active.id } ?: active } ?: return
        when (current.connection) {
            ConnectionStatus.Reconnecting -> repository.reconnectNow()
            ConnectionStatus.Connected -> {
                if (foregroundVerificationJob?.isActive != true) {
                    foregroundVerificationJob = viewModelScope.launch { repository.recoverForeground() }
                }
            }
            else -> Unit
        }
    }
    fun connectWorkspaceHost(host: Host) = connectTo(host, "new")
    private fun cancelPendingNavigation() {
        navigationRevision++
        connectionRequestJob?.cancel()
        repository.cancelWorkspaceRestore()
    }
    fun navigate(destination: String) {
        cancelPendingNavigation()
        _screen.value = destination
    }
    private fun connectTo(host: Host, destination: String, restore: WorkspaceSelection? = null) {
        cancelPendingNavigation()
        foregroundVerificationJob?.cancel()
        val revision = navigationRevision
        connectionRequestJob = action {
            if (networkAccess(host)) {
                if (revision == navigationRevision && hosts.value.any { it.id == host.id }) connectNow(host, destination, restore)
            }
            else notices.emit("连接局域网主机需要附近设备权限，请允许后重试。")
        }
    }
    private suspend fun networkAccess(host: Host): Boolean {
        val hasLocalRoute = host.connectionCandidates().any { LocalNetworkAccess.isLocalHost(it) }
        if (!LocalNetworkAccess.permissionMissing(getApplication()) || !hasLocalRoute) return true
        networkPermissionResult?.cancel()
        val result = CompletableDeferred<Boolean>()
        networkPermissionResult = result
        localNetworkPermissionRequest.value = host
        return try { result.await() || host.connectionCandidates().any { !LocalNetworkAccess.isLocalHost(it) } }
        finally {
            if (networkPermissionResult === result) { networkPermissionResult = null; localNetworkPermissionRequest.value = null }
        }
    }
    fun onLocalNetworkPermissionResult(granted: Boolean) { networkPermissionResult?.complete(granted) }
    fun clearPairingError(resetPassword: Boolean = false) {
        pairingError.value = null
        if (resetPassword) pairingPasswordNeeded.value = false
    }
    fun cancelPairing() { pairingGeneration++; pairingJob?.cancel(); pairingJob = null; pairingBusy.value = false }
    private fun connectNow(host: Host, destination: String, restore: WorkspaceSelection?) {
        repository.connect(host, restore)
        _screen.value = destination
        action { persist(host.id) }
    }
    fun saveHost(id: String?, name: String, address: String, password: String, onSaved: () -> Unit, onHostSaved: ((Host) -> Unit)? = null): Job {
        val attempt = ++pairingGeneration
        pairingJob?.cancel()
        pairingBusy.value = true
        pairingJob = viewModelScope.launch {
            pairingError.value = null
            var probe: DaemonSocket? = null
            try {
                val endpoint = Endpoint.parse(address)
                val relay = endpoint.relay
                val draft = Host(id = id ?: newId(), name = name.trim(), url = endpoint.url,
                    password = password.ifEmpty { endpoint.password.orEmpty() }, relay = relay,
                    automaticName = name.isBlank(), serverId = relay?.serverId)
                if (!networkAccess(draft)) {
                    pairingError.value = "连接本地网络需要附近设备权限，请允许后重试。"
                    return@launch
                }
                val candidate = DaemonSocket()
                probe = candidate
                val info = withContext(Dispatchers.IO) { candidate.open(draft) }
                val serverId = info.string("serverId")
                require(serverId.isNotBlank()) { "主机未返回 serverId，无法保存连接" }
                require(relay == null || relay.serverId == serverId) { "配对链接与主机身份不一致" }
                val target = hosts.value.find { it.id == id }
                require(target == null || (target.serverId ?: target.relay?.serverId)?.let { it == serverId } != false) {
                    "连接不属于此主机，请使用添加主机"
                }
                val verified = draft.copy(serverId = serverId,
                    name = name.trim().ifBlank { info.string("hostname").ifBlank { draft.displayName } })
                var host = verified
                storageMutex.withLock {
                    val (updated, merged) = mergeVerifiedHost(hosts.value, verified)
                    store.save(updated, merged.id)
                    val removedIds = hosts.value.map { it.id }.toSet() - updated.map { it.id }.toSet()
                    workspaceSelectionStore.selection?.takeIf { it.hostId in removedIds }?.let {
                        workspaceSelectionStore.remember(it.copy(hostId = merged.id))
                    }
                    hosts.value = updated
                    host = merged
                }
                connect(host)
                onHostSaved?.invoke(host)
                onSaved()
            } catch (_: TimeoutCancellationException) {
                pairingError.value = "连接超时，请确认电脑上的 Paseo 已启动，并检查网络。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AuthenticationException) {
                pairingPasswordNeeded.value = error.reason in setOf(AuthFailure.PasswordRequired, AuthFailure.IncorrectPassword)
                pairingError.value = error.message
            } catch (error: Exception) { pairingError.value = error.message ?: "无法连接此主机" }
            finally { probe?.close(); if (attempt == pairingGeneration) pairingBusy.value = false }
        }
        return pairingJob!!
    }
    suspend fun editHostName(host: Host, name: String) {
        require(name.isNotBlank()) { "请输入主机名称" }
        updateSavedHost(host.id) { it.copy(name = name.trim(), automaticName = false) }
    }
    fun setHostAppearance(host: Host, color: String = host.color, badgeDisplay: String = host.badgeDisplay) = action {
        updateSavedHost(host.id) { it.copy(color = color, badgeDisplay = badgeDisplay) }
    }
    private suspend fun updateSavedHost(id: String, change: (Host) -> Host) {
        storageMutex.withLock {
            val updated = hosts.value.map { if (it.id == id) change(it) else it }
            store.save(updated, state.value.host?.id)
            hosts.value = updated
            updated.find { it.id == id }?.let(repository::updateHostProfile)
        }
    }
    suspend fun probeHostConnections(host: Host): Map<String, Long?> = coroutineScope {
        if (!networkAccess(host)) return@coroutineScope host.savedConnections().associate { it.id to null }
        host.connectionCandidates().map { candidate -> async(Dispatchers.IO) {
            val probe = DaemonSocket()
            val latency = try {
                val info = probe.open(candidate, timeoutMs = 6_000)
                val expected = host.serverId ?: host.relay?.serverId
                require(expected == null || expected == info.string("serverId")) { "主机身份不一致" }
                val start = System.nanoTime()
                probe.ping()
                (System.nanoTime() - start) / 1_000_000
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            finally { probe.close() }
            candidate.url to latency
        } }.awaitAll().toMap()
    }
    suspend fun performHostLifecycle(host: Host, operation: String) {
        require(state.value.host?.id == host.id) { "请先连接此主机" }
        repository.performHostLifecycle(operation)
    }
    suspend fun removeSavedConnection(host: Host, connectionId: String) {
        val current = hosts.value.find { it.id == host.id } ?: return
        val remaining = current.savedConnections().filterNot { it.id == connectionId }
        if (remaining.isEmpty()) { removeSavedHost(current); return }
        val primary = remaining.first()
        val updatedHost = current.normalized().copy(url = primary.url, relay = primary.relay, connections = remaining)
        updateSavedHost(current.id) { it.copy(url = primary.url, relay = primary.relay, directUrl = null, connections = remaining) }
        if (state.value.host?.id == current.id && state.value.activeConnectionUrl == connectionId)
            repository.connect(updatedHost, state.value.workspaceSelection())
    }
    suspend fun removeSavedHost(host: Host) {
        storageMutex.withLock {
            val updated = hosts.value.filterNot { it.id == host.id }
            store.save(updated, state.value.host?.id?.takeUnless { it == host.id })
            hosts.value = updated
        }
        if (workspaceSelectionStore.selection?.hostId == host.id) workspaceSelectionStore.clear()
        conversations.forgetHost(host.id)
        if (state.value.host?.id == host.id) { navigate("hosts"); repository.disconnect() }
    }
    fun deleteHost(host: Host) = action { removeSavedHost(host) }
    private suspend fun persist(id: String?) = storageMutex.withLock { store.save(hosts.value, id) }
    fun openWorkspace(workspace: Workspace) {
        cancelPendingNavigation()
        repository.openWorkspace(workspace)
        _screen.value = "chat"
        state.value.workspaceSelection()?.let(workspaceSelectionStore::remember)
    }
    fun openAgent(agent: Agent) {
        cancelPendingNavigation()
        repository.selectAgent(agent.id)
        _screen.value = "chat"
        state.value.workspaceSelection()?.let(workspaceSelectionStore::remember)
    }
    fun backToSessions() { navigate("sessions"); repository.leaveAgent() }
    fun setDraft(id: String, text: String) { conversationKey(id)?.let { conversations.setDraft(it, text) } }
    fun send(alternate: Boolean = false) {
        val id = state.value.selectedAgentId ?: return
        conversationKey(id)?.let { conversations.send(it, alternate) }
    }
    fun create(provider: String, cwd: String, model: String, mode: String, prompt: String, title: String,
        key: String, workspace: Workspace?, projectId: String?, worktree: Boolean, branch: String, onCreated: () -> Unit) {
        if (creating.value) return
        creating.value = true
        action {
            try {
                if (workspace != null) repository.createAgent(workspace, provider, model, mode, prompt, title, key)
                else repository.createWorkspace(provider, cwd.trim(), model, mode, prompt, title, key, projectId, worktree, branch)
                _screen.value = "chat"
                onCreated()
            } finally { creating.value = false }
        }
    }
    fun createFromHome(provider: String, cwd: String, model: String, mode: String, draftKey: ConversationKey,
        key: String, workspace: Workspace?, projectId: String?, worktree: Boolean, baseRef: String,
        terminal: Boolean, onCreated: (Boolean) -> Unit) {
        if (creating.value || draftKey in importingAttachments.value) return
        val draft = conversations.session(draftKey).draft
        val hostId = state.value.host?.id ?: return
        creating.value = true
        action {
            try {
                check(hostId == draftKey.hostId && state.value.connected) { "主机未连接" }
                val (images, attachments) = if (terminal) emptyList<kotlinx.serialization.json.JsonObject>() to emptyList()
                    else prepareAttachments(draftKey, draft.attachments)
                check(state.value.host?.id == hostId && state.value.connected) { "主机连接已改变，请重试" }
                val target = if (workspace != null) {
                    repository.createAgent(workspace, provider, model, mode, draft.text.trim(), "", key, images, attachments)
                    workspace
                } else repository.createWorkspace(provider, cwd.trim(), model, mode, draft.text.trim(), "", key,
                    projectId, worktree, withInitialAgent = !terminal && !draft.empty, baseRef = baseRef, images = images, attachments = attachments)
                if (terminal) {
                    val result = repository.createTerminal(target.context)
                    if (draft.text.isNotBlank()) repository.terminalInput(result.string("id"), draft.text.trimEnd() + "\r")
                }
                conversations.setDraft(draftKey, "")
                draft.attachments.forEach { conversations.removeAttachment(draftKey, it.id) }
                _screen.value = "chat"
                onCreated(terminal)
            } finally { creating.value = false }
        }
    }
    fun action(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (timeout: TimeoutCancellationException) { notices.emit("主机响应超时，请检查连接后重试") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notices.emit(error.message ?: "操作失败") }
    }
    override fun onCleared() { audio.cancel(); repository.close() }
}
