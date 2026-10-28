package sh.paseo.android.core

import org.junit.Assert.*
import org.junit.Test

class TimelineBufferTest {
    private fun event(seq: Long, item: kotlinx.serialization.json.JsonObject, epoch: String = "e1") = jsonObject(
        "epoch" to epoch, "seq" to seq, "event" to jsonObject("type" to "timeline", "turnId" to "turn1", "item" to item))
    private fun text(value: String) = jsonObject("type" to "assistant_message", "text" to value, "messageId" to "msg1")

    @Test fun replaysAndDeduplicatesStreamChunksByCanonicalSequence() {
        val buffer = TimelineBuffer()
        buffer.append(event(1, text("hello")))
        buffer.append(event(2, text(" world")))
        buffer.append(event(2, text(" world")))
        assertEquals("hello world", buffer.rows().single().text)
        val key = buffer.rows().single().key
        buffer.append(event(3, text("!")))
        assertEquals(key, buffer.rows().single().key)
    }
    @Test fun reconcilesToolLifecycleWithoutDuplicatingCards() {
        val buffer = TimelineBuffer()
        buffer.append(event(1, jsonObject("type" to "tool_call", "name" to "shell", "callId" to "c1", "status" to "running")))
        buffer.append(event(2, text("working")))
        buffer.append(event(3, jsonObject("type" to "tool_call", "name" to "shell", "callId" to "c1", "status" to "completed", "detail" to jsonObject("output" to "ok"))))
        assertEquals(2, buffer.rows().size)
        assertEquals("completed", buffer.rows().first().status)
        assertEquals("ok", buffer.rows().first().detail.string("output"))
    }
    @Test fun historySnapshotRetainsLaterLiveEvents() {
        val buffer = TimelineBuffer()
        buffer.append(event(3, text("!")))
        buffer.hydrate(jsonObject("epoch" to "e1", "direction" to "tail", "window" to jsonObject("maxSeq" to 2),
            "entries" to listOf(
                jsonObject("seqStart" to 1, "item" to text("hello"), "turnId" to "turn1"),
                jsonObject("seqStart" to 2, "item" to text(" world"), "turnId" to "turn1")),
            "startCursor" to jsonObject("seq" to 1), "hasOlder" to true))
        assertEquals("hello world!", buffer.rows().single().text)
        assertEquals(1L, buffer.startSeq)
        assertTrue(buffer.hasOlder)
        assertTrue(buffer.append(event(4, text("new epoch"), "e2")))
        assertEquals("hello world!", buffer.rows().single().text)
    }
    @Test fun aLongStreamRemainsOneMessage() {
        val buffer = TimelineBuffer()
        repeat(5_000) { buffer.append(event(it.toLong(), text("x"))) }
        assertEquals(5_000, buffer.rows().single().text.length)
        repeat(100) {
            buffer.append(event((5_000 + it).toLong(), text("y")))
            assertEquals(5_001 + it, buffer.rows().single().text.length)
        }
    }
    @Test fun lateEventsRebuildInSequenceOrder() {
        val buffer = TimelineBuffer()
        buffer.append(event(1, text("a")))
        buffer.append(event(3, text("c")))
        assertEquals("ac", buffer.rows().single().text)
        buffer.append(event(2, text("b")))
        assertEquals("abc", buffer.rows().single().text)
    }
}
