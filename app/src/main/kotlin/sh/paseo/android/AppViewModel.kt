package sh.paseo.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val storageMutex = Mutex()
    val hosts = MutableStateFlow<List<Host>>(emptyList())
    val screen = MutableStateFlow("hosts")
    val initialized = MutableStateFlow(false)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val creating = MutableStateFlow(false)
    val localNetworkPermissionRequest = MutableStateFlow<Host?>(null)
    private var connectionRequestJob: Job? = null
    private var networkPermissionResult: CompletableDeferred<Boolean>? = null
    private var pairingJob: Job? = null
    private var pairingGeneration = 0
    val pairingBusy = MutableStateFlow(false)
    val pairingError = MutableStateFlow<String?>(null)
    val pairingPasswordNeeded = MutableStateFlow(false)
    private val chatStore = ChatStore(application)
    val sendBehavior = MutableStateFlow(chatStore.behavior)
    val collapseTurns = MutableStateFlow(chatStore.collapseTurns)
    fun setCollapseTurns(value: Boolean) { collapseTurns.value = value; chatStore.collapseTurns = value }
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
            try { conversations.restoreMemory(chatStore.load()) }
            catch (error: Exception) { notices.emit("无法读取草稿：${error.message}") }
            conversations.sessions.map { sessions -> sessions.mapValues { ConversationMemory(it.value.draft, it.value.sentAttachments, it.value.optimistic, it.value.failed) } }.distinctUntilChanged().collectLatest { drafts ->
                delay(250)
                try { chatStore.save(drafts) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { notices.emit("草稿保存失败：${error.message}") }
            }
        }
        viewModelScope.launch {
            try {
                val saved = store.load()
                hosts.value = saved.hosts
                saved.hosts.find { it.id == saved.lastHostId }?.let { connect(it) }
            } catch (error: Exception) { notices.emit("无法读取主机配置：${error.message}") }
            finally { initialized.value = true }
        }
    }
    fun connect(host: Host) = connectTo(host, "sessions")
    fun connectWorkspaceHost(host: Host) = connectTo(host, "new")
    private fun connectTo(host: Host, destination: String) {
        connectionRequestJob?.cancel()
        connectionRequestJob = action {
            if (networkAccess(host)) connectNow(host, destination)
            else notices.emit("连接局域网主机需要附近设备权限，请允许后重试。")
        }
    }
    private suspend fun networkAccess(host: Host): Boolean {
        if (!LocalNetworkAccess.permissionMissing(getApplication()) || !LocalNetworkAccess.isLocalHost(host)) return true
        networkPermissionResult?.cancel()
        val result = CompletableDeferred<Boolean>()
        networkPermissionResult = result
        localNetworkPermissionRequest.value = host
        return try { result.await() }
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
    private fun connectNow(host: Host, destination: String = "sessions") {
        repository.connect(host)
        screen.value = destination
        action { persist(host.id) }
    }
    fun saveHost(id: String?, name: String, address: String, password: String, onSaved: () -> Unit): Job {
        val attempt = ++pairingGeneration
        pairingJob?.cancel()
        pairingBusy.value = true
        pairingJob = viewModelScope.launch {
            pairingError.value = null
            var probe: DaemonSocket? = null
            try {
                val endpoint = Endpoint.parse(address)
                val relay = endpoint.relay
                val existing = hosts.value.find { old -> old.id == id || (id == null &&
                    if (relay != null) old.relay?.serverId == relay.serverId else old.relay == null && old.url == endpoint.url) }
                val preservedName = existing?.takeIf { id == null && !it.automaticName }?.name
                val draft = Host(id ?: existing?.id ?: newId(), name.trim(), endpoint.url,
                    password.ifEmpty { endpoint.password.orEmpty() }, relay, automaticName = name.isBlank() && preservedName == null)
                if (!networkAccess(draft)) {
                    pairingError.value = "连接局域网主机需要附近设备权限，请允许后重试。"
                    return@launch
                }
                // Probe before replacing or saving a host. Invalid links/passwords leave existing hosts untouched.
                val candidate = DaemonSocket()
                probe = candidate
                val info = withContext(Dispatchers.IO) { candidate.open(draft) }
                val label = name.trim().ifBlank { preservedName ?: info.string("hostname").ifBlank { draft.displayName } }
                val host = draft.copy(name = label)
                val updated = hosts.value.filterNot { it.id == host.id } + host
                storageMutex.withLock { store.save(updated, host.id); hosts.value = updated }
                connect(host)
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
    fun deleteHost(host: Host) = action {
        val updated = hosts.value.filterNot { it.id == host.id }
        storageMutex.withLock { store.save(updated, state.value.host?.id?.takeUnless { it == host.id }); hosts.value = updated }
        conversations.forgetHost(host.id)
        if (state.value.host?.id == host.id) { repository.disconnect(); screen.value = "hosts" }
    }
    private suspend fun persist(id: String?) = storageMutex.withLock { store.save(hosts.value, id) }
    fun openWorkspace(workspace: Workspace) { repository.openWorkspace(workspace); screen.value = "chat" }
    fun openAgent(agent: Agent) { repository.selectAgent(agent.id); screen.value = "chat" }
    fun backToSessions() { repository.leaveAgent(); screen.value = "sessions" }
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
                screen.value = "chat"
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
                screen.value = "chat"
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
