package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ReconnectTimelineTest {
    private class Fixture(val coldPagesHeld: Boolean = false) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val peers = CopyOnWriteArrayList<WebSocket>()
        val heldCatalog = CompletableDeferred<Unit>()
        val heldSubscription = CompletableDeferred<Pair<WebSocket, String>>()
        val connections = AtomicInteger()
        private fun agent() = jsonObject("id" to "a", "workspaceId" to "w", "cwd" to "/project", "status" to "idle")
        private fun workspace() = jsonObject("id" to "w", "projectId" to "p", "projectDisplayName" to "Project",
            "projectRootPath" to "/project", "name" to "Workspace")
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    var connection = 0
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = wireJson.parseToJsonElement(text).jsonObject
                        if (frame.string("type") == "hello") {
                            connection = connections.incrementAndGet(); peers.add(webSocket)
                            webSocket.send(DaemonSocketTest.session("status", jsonObject("status" to "server_info", "serverId" to "s")))
                            return
                        }
                        if (frame.string("type") == "ping") { webSocket.send(jsonObject("type" to "pong").toString()); return }
                        val message = frame.obj("message")
                        requests.add(message)
                        val type = message.string("type")
                        if ((type == "fetch_agents_request" || type == "fetch_workspaces_request") &&
                            (connection > 1 || message.obj("page").string("cursor").isNotBlank())) {
                            heldCatalog.complete(Unit)
                            return
                        }
                        if (type == "agent.timeline.set_subscription.request" && connection > 1) {
                            heldSubscription.complete(webSocket to message.string("requestId"))
                            return
                        }
                        val payload = when (type) {
                            "fetch_agents_request" -> jsonObject("entries" to listOf(jsonObject("agent" to agent())),
                                "pageInfo" to jsonObject("nextCursor" to "later".takeIf { coldPagesHeld }))
                            "fetch_workspaces_request" -> jsonObject("entries" to listOf(workspace()),
                                "pageInfo" to jsonObject("nextCursor" to "later".takeIf { coldPagesHeld }))
                            "agent.timeline.set_subscription.request" -> jsonObject("subscriptionId" to "timeline")
                            "fetch_agent_timeline_request" -> jsonObject("agent" to agent(), "epoch" to "e", "direction" to message.string("direction"),
                                "window" to jsonObject("maxSeq" to connection), "entries" to listOf(jsonObject("seqStart" to connection,
                                    "item" to jsonObject("type" to "assistant_message", "messageId" to "m$connection", "text" to "reply-$connection"))))
                            else -> jsonObject()
                        }
                        webSocket.send(DaemonSocketTest.session("$type.reply", JsonObject(payload + ("requestId" to JsonPrimitive(message.string("requestId"))))))
                    }
                })
            }
        }
        val host = Host(id = "h", name = "Fixture", url = server.url("/ws").toString(), serverId = "s")
        val repository = PaseoRepository()
        suspend fun connect() {
            repository.connect(host, WorkspaceSelection("h", "w", "a"))
            withTimeout(5_000) { repository.state.first { it.timeline.isNotEmpty() && !it.loadingTimeline } }
        }
        suspend fun newMessages() = withTimeout(5_000) {
            repository.state.first { it.timeline.any { row -> row.text == "reply-2" } }
        }
        suspend fun releaseSubscription() {
            val (peer, requestId) = withTimeout(5_000) { heldSubscription.await() }
            peer.send(DaemonSocketTest.session("agent.timeline.set_subscription.response", jsonObject("requestId" to requestId, "subscriptionId" to "new-timeline")))
        }
        override fun close() { repository.close(); server.close() }
    }

    @Test fun reconnectFetchesCurrentMessagesBeforeCatalogAndSubscriptionReplies() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.peers.first().close(1000, "test disconnect")
            val state = f.newMessages()
            assertEquals(listOf("reply-1", "reply-2"), state.timeline.map { it.text })
            assertEquals("w", state.selectedWorkspaceId)
            withTimeout(5_000) { f.heldCatalog.await() }
            assertEquals("after", f.requests.last { it.string("type") == "fetch_agent_timeline_request" }.string("direction"))
            assertEquals(1L, f.requests.last { it.string("type") == "fetch_agent_timeline_request" }.obj("cursor").long("seq"))
            f.releaseSubscription()
            withTimeout(5_000) { f.repository.state.first { !it.syncingTimeline } }
            assertEquals(ConnectionStatus.Connected, f.repository.state.value.connection)
        }
    }

    @Test fun explicitSameHostReconnectKeepsTheVisibleConversationAndCursor() = runBlocking {
        Fixture().use { f ->
            f.connect()
            val before = f.repository.state.value
            f.repository.connect(f.host, before.workspaceSelection())
            assertEquals(before.timeline, f.repository.state.value.timeline)
            assertEquals(before.selectedWorkspaceId, f.repository.state.value.selectedWorkspaceId)
            assertNull(f.repository.state.value.restoringWorkspace)
            assertEquals(listOf("reply-1", "reply-2"), f.newMessages().timeline.map { it.text })
        }
    }

    @Test fun knownSavedConversationDoesNotWaitForUnrelatedCatalogPages() = runBlocking {
        Fixture(coldPagesHeld = true).use { f ->
            f.connect()
            withTimeout(5_000) { f.heldCatalog.await() }
            assertEquals("a", f.repository.state.value.selectedAgentId)
            assertNull(f.repository.state.value.restoringWorkspace)
            assertEquals("reply-1", f.repository.state.value.timeline.single().text)
        }
    }

    @Test fun switchingHostsDoesNotReuseAnotherHostsMessages() = runBlocking {
        Fixture().use { f ->
            f.connect()
            f.repository.connect(f.host.copy(id = "different-host"))
            assertTrue(f.repository.state.value.timeline.isEmpty())
            assertNull(f.repository.state.value.selectedWorkspaceId)
        }
    }
}
