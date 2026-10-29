package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ConversationKey(val hostId: String, val agentId: String)
enum class SendBehavior(val label: String) { Steer("引导当前任务"), Interrupt("中断并发送"), Queue("加入队列") }
@Serializable
data class ChatAttachment(
    val id: String = newId(), val name: String, val mimeType: String = "text/plain",
    val localPath: String = "", val size: Long = 0, val payload: JsonObject? = null,
) { val isImage get() = mimeType.startsWith("image/") && localPath.isNotBlank() }
@Serializable
data class ChatDraft(val text: String = "", val attachments: List<ChatAttachment> = emptyList()) {
    val empty get() = text.isBlank() && attachments.isEmpty()
}
@Serializable
data class OutgoingMessage(val id: String = newId(), val draft: ChatDraft, val delivery: String = "sending", val turnId: String = "")
@Serializable
data class ConversationMemory(val draft: ChatDraft = ChatDraft(), val sentAttachments: Map<String, List<ChatAttachment>> = emptyMap(), val pending: List<OutgoingMessage> = emptyList(), val failed: OutgoingMessage? = null)
data class ConversationState(
    val draft: ChatDraft = ChatDraft(), val queue: List<OutgoingMessage> = emptyList(),
    val optimistic: List<OutgoingMessage> = emptyList(), val sending: Boolean = false,
    val sentAttachments: Map<String, List<ChatAttachment>> = emptyMap(),
    val cancelling: Boolean = false, val error: String? = null, val failed: OutgoingMessage? = null,
)

/** Confined to the supplied UI scope. Keys include the host so drafts/receipts cannot cross hosts. */
class ConversationController(
    private val scope: CoroutineScope,
    private val sendMessage: suspend (ConversationKey, OutgoingMessage, String) -> String,
    private val cancelAgent: suspend (ConversationKey) -> Unit,
) {
    private val mutable = MutableStateFlow<Map<ConversationKey, ConversationState>>(emptyMap())
    val sessions = mutable.asStateFlow()
    private var snapshot = PaseoState()
    private var previousRunning = emptySet<ConversationKey>()
    private var previousConnectedHost: String? = null
    private val forgottenHosts = mutableSetOf<String>()
    private val confirmedMessages = mutableMapOf<ConversationKey, Set<String>>()
    private val pendingDrains = mutableSetOf<ConversationKey>()
    private var knownAgents = emptySet<ConversationKey>()
    var behavior = SendBehavior.Steer
    fun session(key: ConversationKey) = mutable.value[key] ?: ConversationState()
    private fun change(key: ConversationKey, update: (ConversationState) -> ConversationState) {
        if (key.hostId in forgottenHosts) return
        mutable.update { it + (key to update(it[key] ?: ConversationState())) }
    }
    fun restore(drafts: Map<ConversationKey, ChatDraft>) {
        drafts.forEach { (key, draft) -> if (session(key).draft.empty) change(key) { it.copy(draft = draft) } }
    }
    fun restoreMemory(memories: Map<ConversationKey, ConversationMemory>) {
        restore(memories.mapValues { it.value.draft })
        memories.forEach { (key, memory) -> change(key) { it.copy(sentAttachments = memory.sentAttachments + it.sentAttachments,
            optimistic = (it.optimistic + memory.pending.map { message -> message.copy(delivery = "unconfirmed") }).distinctBy { message -> message.id },
            failed = it.failed ?: memory.failed, error = it.error ?: memory.failed?.let { "上次发送未完成，可重试" }) } }
        update(snapshot)
    }
    fun setDraft(key: ConversationKey, text: String) = change(key) {
        it.copy(draft = it.draft.copy(text = text), failed = it.failed.takeIf { _ -> text == it.draft.text }, error = it.error.takeIf { _ -> text == it.draft.text || it.failed == null })
    }
    fun attach(key: ConversationKey, attachments: List<ChatAttachment>) = change(key) {
        it.copy(draft = it.draft.copy(attachments = (it.draft.attachments + attachments).distinctBy { a -> a.id }))
    }
    fun removeAttachment(key: ConversationKey, id: String) = change(key) { it.copy(draft = it.draft.copy(attachments = it.draft.attachments.filterNot { a -> a.id == id })) }
    fun dismissError(key: ConversationKey) = change(key) { it.copy(error = null) }
    fun forgetHost(hostId: String) {
        forgottenHosts += hostId; confirmedMessages.keys.removeAll { it.hostId == hostId }; pendingDrains.removeAll { it.hostId == hostId }
        mutable.update { map -> map.filterKeys { it.hostId != hostId } }
    }
    fun update(value: PaseoState) {
        snapshot = value
        val host = value.host?.id ?: return
        val running = value.agents.filter { it.running }.map { ConversationKey(host, it.id) }.toSet()
        val reconnected = value.connected && previousConnectedHost != host
        val discovered = value.agents.map { ConversationKey(host, it.id) }.filter { it !in knownAgents }.toSet()
        knownAgents = knownAgents.filter { it.hostId != host }.toSet() + value.agents.map { ConversationKey(host, it.id) }
        val stopped = previousRunning.filter { it.hostId == host && it !in running }.toSet()
        previousRunning = previousRunning.filter { it.hostId != host }.toSet() + running
        previousConnectedHost = host.takeIf { value.connected }
        value.selectedAgentId?.let { id ->
            val key = ConversationKey(host, id)
            val confirmed = value.timeline.filter { it.type == "user_message" && it.messageId.isNotEmpty() }.map { it.messageId }.toSet()
            confirmedMessages[key] = ((confirmedMessages[key].orEmpty() + confirmed).toList().takeLast(2000)).toSet()
            if (session(key).optimistic.any { it.id in confirmed } || session(key).failed?.id in confirmed) change(key) { current ->
                val deliveredFailure = current.failed?.takeIf { it.id in confirmed }
                current.copy(optimistic = current.optimistic.filterNot { it.id in confirmed },
                    draft = if (deliveredFailure?.draft == current.draft) ChatDraft() else current.draft,
                    failed = current.failed.takeUnless { it?.id in confirmed }, error = if (deliveredFailure != null) null else current.error)
            }
        }
        if (!value.connected) return
        value.agents.filterNot { it.running }.forEach { agent ->
            val key = ConversationKey(host, agent.id)
            if (session(key).cancelling) change(key) { it.copy(cancelling = false) }
        }
        value.agents.forEach { agent ->
            val key = ConversationKey(host, agent.id)
            if (!agent.running && agent.pendingPermissions.isEmpty() &&
                (key in stopped || reconnected || key in discovered) && session(key).queue.isNotEmpty()) sendQueued(key, session(key).queue.first().id, automatic = true)
        }
    }
    private fun agent(key: ConversationKey): Agent? = snapshot.agents.find { snapshot.host?.id == key.hostId && it.id == key.agentId }
    private fun connected(key: ConversationKey) = snapshot.connected && snapshot.host?.id == key.hostId
    fun send(key: ConversationKey, alternate: Boolean = false) {
        val current = session(key)
        val agent = agent(key) ?: return
        if (!connected(key) || current.sending || current.draft.empty) return
        val activeBehavior = if (behavior == SendBehavior.Queue && agent.pendingPermissions.isNotEmpty()) SendBehavior.Interrupt else behavior
        val queue = agent.running && (if (alternate) activeBehavior != SendBehavior.Queue else activeBehavior == SendBehavior.Queue)
        val draft = current.draft.copy(text = current.draft.text.trim())
        val failed = current.failed?.takeIf { it.draft == draft }
        val message = failed?.copy(delivery = "sending") ?: OutgoingMessage(draft = draft,
            turnId = if (activeBehavior == SendBehavior.Steer) agent.currentTurnId else "")
        change(key) { it.copy(draft = ChatDraft(), error = null, failed = null) }
        if (queue) { change(key) { it.copy(queue = it.queue + message) }; return }
        submit(key, message, if (activeBehavior == SendBehavior.Steer) "steer" else "interrupt", restoreDraft = true)
    }
    fun editQueued(key: ConversationKey, id: String) {
        val message = session(key).queue.find { it.id == id } ?: return
        change(key) { it.copy(queue = it.queue.filterNot { q -> q.id == id }, draft = merge(message.draft, it.draft), error = null) }
    }
    fun sendQueued(key: ConversationKey, id: String, automatic: Boolean = false) {
        if (!connected(key)) return
        if (session(key).sending) { if (automatic) pendingDrains += key; return }
        val message = session(key).queue.find { it.id == id } ?: return
        change(key) { it.copy(queue = it.queue.filterNot { q -> q.id == id }, error = null) }
        submit(key, message, if (automatic || behavior == SendBehavior.Steer) "steer" else "interrupt", restoreDraft = false)
    }
    fun retry(key: ConversationKey) {
        val failed = session(key).failed ?: return
        if (!connected(key) || session(key).sending) return
        change(key) {
            val text = when {
                it.draft.text == failed.draft.text -> ""
                failed.draft.text.isNotBlank() && it.draft.text.startsWith(failed.draft.text + "\n\n") -> it.draft.text.removePrefix(failed.draft.text + "\n\n")
                else -> it.draft.text
            }
            it.copy(draft = ChatDraft(text, it.draft.attachments.filterNot { a -> failed.draft.attachments.any { f -> a.id == f.id } }), error = null, failed = null)
        }
        submit(key, failed, if (behavior == SendBehavior.Steer) "steer" else "interrupt", restoreDraft = true)
    }
    fun retryPending(key: ConversationKey, id: String) {
        val message = session(key).optimistic.find { it.id == id && it.delivery == "unconfirmed" } ?: return
        if (!connected(key) || session(key).sending) return
        change(key) { it.copy(error = null) }
        submit(key, message, if (behavior == SendBehavior.Steer) "steer" else "interrupt", restoreDraft = true)
    }
    fun editPending(key: ConversationKey, id: String) {
        val message = session(key).optimistic.find { it.id == id && it.delivery == "unconfirmed" } ?: return
        change(key) { it.copy(optimistic = it.optimistic.filterNot { pending -> pending.id == id }, draft = merge(message.draft, it.draft), failed = message) }
    }
    private fun submit(key: ConversationKey, message: OutgoingMessage, behavior: String, restoreDraft: Boolean) {
        change(key) { it.copy(sending = true, sentAttachments = (it.sentAttachments + (message.id to message.draft.attachments)).entries.toList().takeLast(200).associate { entry -> entry.toPair() }, optimistic = it.optimistic.filterNot { m -> m.id == message.id } + message.copy(delivery = "sending")) }
        scope.launch {
            try {
                val delivery = sendMessage(key, message, behavior)
                change(key) { it.copy(optimistic = it.optimistic.map { m -> if (m.id == message.id) m.copy(delivery = delivery) else m }) }
            } catch (error: Exception) {
                // A canonical echo is proof of delivery even if its correlated receipt was lost.
                val confirmed = message.id in confirmedMessages[key].orEmpty()
                if (!confirmed) { pendingDrains.remove(key); change(key) {
                    it.copy(optimistic = it.optimistic.filterNot { m -> m.id == message.id }, sentAttachments = it.sentAttachments - message.id,
                        draft = if (restoreDraft) merge(message.draft, it.draft) else it.draft,
                        queue = if (restoreDraft) it.queue else listOf(message) + it.queue,
                        error = if (error is TimeoutCancellationException) "发送超时，请重试" else error.message ?: "发送失败",
                        failed = if (restoreDraft) message else null)
                } }
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
            } finally {
                change(key) { it.copy(sending = false) }
                if (pendingDrains.remove(key) && agent(key)?.running == false) session(key).queue.firstOrNull()?.let { sendQueued(key, it.id, automatic = true) }
            }
        }
    }
    fun stop(key: ConversationKey) {
        if (!connected(key) || agent(key)?.running != true || session(key).cancelling) return
        change(key) { it.copy(cancelling = true) }
        scope.launch {
            try { cancelAgent(key) }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                change(key) { it.copy(error = error.message ?: "停止失败", cancelling = false) }
            }
        }
    }
    private fun merge(older: ChatDraft, newer: ChatDraft) = if (newer.empty) older else ChatDraft(
        text = if (newer.text == older.text || newer.text.startsWith(older.text + "\n\n")) newer.text else listOf(older.text, newer.text).filter { it.isNotBlank() }.joinToString("\n\n"),
        attachments = (older.attachments + newer.attachments).distinctBy { it.id },
    )
}
