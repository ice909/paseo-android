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
    val sending = MutableStateFlow(false)
    val creating = MutableStateFlow(false)
    val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val localNetworkPermissionRequest = MutableStateFlow<Host?>(null)
    private var connectionRequestJob: Job? = null
    private var networkPermissionResult: CompletableDeferred<Boolean>? = null
    private var pairingJob: Job? = null
    private var pairingGeneration = 0
    val pairingBusy = MutableStateFlow(false)
    val pairingError = MutableStateFlow<String?>(null)
    val pairingPasswordNeeded = MutableStateFlow(false)
    private val messageIds = mutableMapOf<String, Pair<String, String>>()

    init {
        viewModelScope.launch {
            try {
                val saved = store.load()
                hosts.value = saved.hosts
                saved.hosts.find { it.id == saved.lastHostId }?.let { connect(it) }
            } catch (error: Exception) { notices.emit("无法读取主机配置：${error.message}") }
            finally { initialized.value = true }
        }
    }
    fun connect(host: Host) {
        connectionRequestJob?.cancel()
        connectionRequestJob = action {
            if (networkAccess(host)) connectNow(host)
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
    private fun connectNow(host: Host) {
        repository.connect(host)
        screen.value = "sessions"
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
        if (state.value.host?.id == host.id) { repository.disconnect(); screen.value = "hosts" }
    }
    private suspend fun persist(id: String?) = storageMutex.withLock { store.save(hosts.value, id) }
    fun openWorkspace(workspace: Workspace) { repository.openWorkspace(workspace); screen.value = "chat" }
    fun openAgent(agent: Agent) { repository.selectAgent(agent.id); screen.value = "chat" }
    fun backToSessions() { repository.leaveAgent(); screen.value = "sessions" }
    fun setDraft(id: String, text: String) { drafts.update { it + (id to text) } }
    fun send() {
        val agentId = state.value.selectedAgentId ?: return
        val text = drafts.value[agentId].orEmpty()
        if (text.isBlank() || sending.value) return
        // Reuse the deduplication ID if the receipt was lost and the user retries.
        val id = messageIds[agentId]?.takeIf { it.first == text }?.second ?: newId()
        messageIds[agentId] = text to id
        sending.value = true
        action {
            try {
                repository.sendMessage(agentId, text, id)
                if (drafts.value[agentId] == text) setDraft(agentId, "")
                messageIds.remove(agentId)
            } finally { sending.value = false }
        }
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
    fun action(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (timeout: TimeoutCancellationException) { notices.emit("主机响应超时，请检查连接后重试") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notices.emit(error.message ?: "操作失败") }
    }
    override fun onCleared() { repository.close() }
}
