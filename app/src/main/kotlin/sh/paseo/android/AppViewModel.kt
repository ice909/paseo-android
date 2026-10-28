package sh.paseo.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.paseo.android.core.*
import sh.paseo.android.data.HostStore

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = PaseoRepository()
    val state = repository.state
    private val store = HostStore(application)
    private val storageMutex = Mutex()
    val hosts = MutableStateFlow<List<Host>>(emptyList())
    val screen = MutableStateFlow("hosts")
    val initialized = MutableStateFlow(false)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sending = MutableStateFlow(false)
    val creating = MutableStateFlow(false)
    val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
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
        repository.connect(host)
        screen.value = "sessions"
        action { persist(host.id) }
    }
    fun saveHost(id: String?, name: String, address: String, password: String, onSaved: () -> Unit) = action {
        val endpoint = Endpoint.parse(address)
        val host = Host(id ?: newId(), name.trim().ifBlank { java.net.URI(endpoint.url).host }, endpoint.url, password.ifEmpty { endpoint.password.orEmpty() })
        val updated = hosts.value.filterNot { it.id == host.id } + host
        storageMutex.withLock { store.save(updated, host.id); hosts.value = updated }
        connect(host)
        onSaved()
    }
    fun deleteHost(host: Host) = action {
        val updated = hosts.value.filterNot { it.id == host.id }
        storageMutex.withLock { store.save(updated, state.value.host?.id?.takeUnless { it == host.id }); hosts.value = updated }
        if (state.value.host?.id == host.id) { repository.disconnect(); screen.value = "hosts" }
    }
    private suspend fun persist(id: String?) = storageMutex.withLock { store.save(hosts.value, id) }
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
    fun create(provider: String, cwd: String, model: String, prompt: String, title: String, onCreated: () -> Unit) {
        if (creating.value) return
        creating.value = true
        action {
            try {
                repository.createAgent(provider, cwd.trim(), model, prompt, title)
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
