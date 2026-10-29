package sh.paseo.android.core

import java.util.TreeMap
import kotlinx.serialization.json.*

/** Canonical sequence numbers make history/live overlap and reconnect replay idempotent. */
class TimelineBuffer {
    private data class Entry(val seq: Long, val item: JsonObject, val turnId: String, val timestamp: String)
    private val entries = TreeMap<Long, Entry>()
    private val projected = mutableListOf<TimelineRow>()
    private val tools = mutableMapOf<String, Int>()
    private val userIds = mutableSetOf<String>()
    private var renderedThrough: Long? = null
    private var rebuild = true
    var epoch: String = ""; private set
    var startSeq: Long? = null; private set
    var hasOlder: Boolean = false; private set
    val endSeq: Long? get() = entries.lastKeyOrNull()

    fun clear() { entries.clear(); projected.clear(); tools.clear(); userIds.clear(); renderedThrough = null; rebuild = true; epoch = ""; startSeq = null; hasOlder = false }

    fun hydrate(payload: JsonObject) {
        val newEpoch = payload.string("epoch")
        val direction = payload.string("direction")
        if (newEpoch != epoch || (payload.bool("reset") && direction != "tail")) entries.clear()
        rebuild = true
        epoch = newEpoch
        if (direction == "tail") {
            // Retain events received after the history request's snapshot boundary.
            val max = payload.obj("window").long("maxSeq")
            if (max != null) entries.keys.removeAll { it <= max }
        }
        payload.array("entries").forEach { element ->
            val row = element.jsonObject
            val seq = row.long("seqStart") ?: return@forEach
            entries[seq] = Entry(seq, row.obj("item"), row.string("turnId"), row.string("timestamp"))
        }
        if (direction != "after") {
            startSeq = payload.obj("startCursor").long("seq")
            hasOlder = payload.bool("hasOlder")
        }
    }

    /** Returns true if an epoch change requires a new history snapshot. */
    fun append(payload: JsonObject): Boolean {
        val incomingEpoch = payload.string("epoch")
        if (epoch.isNotEmpty() && incomingEpoch.isNotEmpty() && incomingEpoch != epoch) return true
        if (epoch.isEmpty()) epoch = incomingEpoch
        val event = payload.obj("event")
        val seq = payload.long("seq") ?: ((endSeq ?: -1) + 1)
        val entry = Entry(seq, event.obj("item"), event.string("turnId"), payload.string("timestamp"))
        if (entries[seq] == entry) return false
        if (renderedThrough?.let { seq <= it } == true) rebuild = true
        entries[seq] = entry
        return false
    }

    fun rows(): List<TimelineRow> {
        if (rebuild) { projected.clear(); tools.clear(); userIds.clear(); renderedThrough = null; rebuild = false }
        val rows = projected
        val pending = renderedThrough?.let { entries.tailMap(it, false).values } ?: entries.values
        var merged: StringBuilder? = null
        var mergingIndex = -1
        fun flushText() {
            merged?.let { rows[mergingIndex] = rows[mergingIndex].copy(text = it.toString()) }
            merged = null; mergingIndex = -1
        }
        for (entry in pending) {
            val seq = entry.seq
            val item = entry.item
            val type = item.string("type")
            val messageId = item.string("messageId").ifBlank { item.string("clientMessageId") }
            val text = item.string("text")
            val last = rows.lastOrNull()
            if (type == "user_message" && messageId.isNotEmpty() && !userIds.add(messageId)) continue
            if ((type == "assistant_message" || type == "reasoning") && last?.type == type &&
                (messageId.isEmpty() || last.messageId == messageId) && last.turnId == entry.turnId) {
                if (mergingIndex != rows.lastIndex) { flushText(); merged = StringBuilder(last.text); mergingIndex = rows.lastIndex }
                merged!!.append(text)
                continue
            }
            flushText()
            val row = TimelineRow(
                key = "$epoch:$seq", type = type, text = text.ifEmpty { item.string("message") },
                title = item.string("name"), status = item.string("status"),
                detail = if (type == "todo") item else item.obj("detail"),
                messageId = messageId, turnId = entry.turnId, timestamp = entry.timestamp,
                images = item.array("images").mapNotNull { it as? JsonObject },
                attachments = item.array("attachments").mapNotNull { it as? JsonObject },
            )
            if (type == "tool_call") {
                val identity = "${entry.turnId}:${item.string("callId")}"
                val index = tools[identity]
                if (index != null) rows[index] = row.copy(key = rows[index].key)
                else { tools[identity] = rows.size; rows.add(row) }
            } else rows.add(row)
        }
        flushText()
        renderedThrough = entries.lastKeyOrNull()
        return rows.toList()
    }
    private fun <K, V> TreeMap<K, V>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()
}
