package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ForegroundRecoveryTest {
    private class Fixture(val holdAfter: Boolean = false, val overflow: Boolean = false) : AutoCloseable {
        @Volatile var running = false
        val requests = CopyOnWriteArrayList<JsonObject>()
        val held = CompletableDeferred<Pair<WebSocket, JsonObject>>()
        lateinit var peer: WebSocket
        private fun agent() = jsonObject("id" to "a", "status" to if (running) "running" else "idle",
            "activeTurn" to if (running) jsonObject("turnId" to "background-turn") else null)
        val server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    peer = webSocket
                    val frame = wireJson.parseToJsonElement(text).jsonObject
                    if (frame.string("type") == "hello") {
                        webSocket.send(DaemonSocketTest.session("status", jsonObject("status" to "server_info", "serverId" to "s")))
                        return
                    }
                    if (frame.string("type") == "ping") { webSocket.send(jsonObject("type" to "pong").toString()); return }
                    val message = frame.obj("message")
                    requests.add(message)
                    val direction = message.string("direction")
                    val payload = when (message.string("type")) {
                        "fetch_agents_request" -> jsonObject("entries" to listOf(jsonObject("agent" to agent())))
                        "fetch_agent_timeline_request" -> {
                            val after = direction == "after"
                            jsonObject("agent" to agent(), "epoch" to "e", "direction" to direction,
                                "hasNewer" to (overflow && after), "window" to jsonObject("maxSeq" to if (requests.count { it.string("type") == "fetch_agent_timeline_request" } > 1) 2 else 1),
                                "entries" to if (after || requests.count { it.string("type") == "fetch_agent_timeline_request" } > 1)
                                    listOf(jsonObject("seqStart" to 2, "item" to jsonObject("type" to "assistant_message", "messageId" to "reply", "text" to "background reply")))
                                else listOf(jsonObject("seqStart" to 1, "item" to jsonObject("type" to "assistant_message", "messageId" to "old", "text" to "old reply"))))
                        }
                        else -> jsonObject()
                    }
                    val response = JsonObject(payload + ("requestId" to JsonPrimitive(message.string("requestId"))))
                    if (holdAfter && direction == "after") held.complete(webSocket to response)
                    else webSocket.send(DaemonSocketTest.session(message.string("type") + ".reply", response))
                }
            }))
        }
        val repository = PaseoRepository()
        suspend fun connect() {
            repository.connect(Host(id = "h", name = "Fixture", url = server.url("/ws").toString()))
            withTimeout(5_000) { repository.state.first { it.agents.isNotEmpty() && it.catalogLoaded } }
            repository.selectAgent("a")
            withTimeout(5_000) { repository.state.first { !it.loadingTimeline && it.timeline.isNotEmpty() } }
        }
        override fun close() { repository.close(); server.close() }
    }

    @Test fun healthySocketStillRecoversMissedTurnStateAndMessagesOnForeground() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.running = true // No stream event: the app missed it while backgrounded.
            withTimeout(5_000) { f.repository.recoverForeground() }
            val state = f.repository.state.value
            assertTrue(state.selectedAgent!!.running)
            assertEquals(listOf("old reply", "background reply"), state.timeline.map { it.text })
            assertFalse(state.recoveringForeground)
            assertEquals("after", f.requests.last { it.string("type") == "fetch_agent_timeline_request" }.string("direction"))
            f.running = false
            withTimeout(5_000) { f.repository.recoverForeground() }
            assertFalse(f.repository.state.value.selectedAgent!!.running)
            assertEquals(2, f.repository.state.value.timeline.size)
            assertEquals(1, f.requests.count { it.string("type") == "agent.timeline.set_subscription.request" })
            assertFalse(f.requests.any { it.string("type") == "send_agent_message_request" })
        }
    }

    @Test fun staleForegroundSnapshotCannotOverwriteANewerTurnStartedEvent() = runBlocking {
        Fixture(holdAfter = true).use { f ->
            f.connect()
            val recovery = async { f.repository.recoverForeground() }
            val (peer, payload) = withTimeout(5_000) { f.held.await() }
            assertTrue(f.repository.state.value.recoveringForeground)
            assertFalse(f.repository.state.value.syncingTimeline)
            peer.send(DaemonSocketTest.session("agent_stream", jsonObject("agentId" to "a", "event" to jsonObject("type" to "turn_started", "turnId" to "background-turn"))))
            withTimeout(5_000) { f.repository.state.first { it.selectedAgent?.running == true } }
            peer.send(DaemonSocketTest.session("fetch_agent_timeline_response", payload))
            withTimeout(5_000) { recovery.await() }
            assertTrue(f.repository.state.value.selectedAgent!!.running)
            assertFalse(f.repository.state.value.recoveringForeground)
        }
    }

    @Test fun largeForegroundGapFallsBackToLatestTailInsteadOfPagingAllHistory() = runBlocking {
        Fixture(overflow = true).use { f ->
            f.connect()
            withTimeout(5_000) { f.repository.recoverForeground() }
            assertEquals(listOf("tail", "after", "tail"), f.requests.filter { it.string("type") == "fetch_agent_timeline_request" }.map { it.string("direction") })
            assertEquals("background reply", f.repository.state.value.timeline.single().text)
        }
    }
}
