package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WorkspaceRestorationTest {
    private class Fixture(
        val missingWorkspace: Boolean = false,
        val archivedAgent: Boolean = false,
        val emptyWorkspace: Boolean = false,
        val holdAgents: Boolean = false,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val heldAgents = CompletableDeferred<Pair<WebSocket, JsonObject>>()
        private fun workspace(id: String) = jsonObject("id" to id, "projectId" to "p1", "projectDisplayName" to "Project",
            "projectRootPath" to "/project", "workspaceDirectory" to "/project/$id", "name" to id)
        private fun agent(id: String, workspace: String = "w2") = jsonObject("id" to id,
            "workspaceId" to workspace, "cwd" to "/project/$workspace", "provider" to "claude",
            "archivedAt" to if (id == "saved" && archivedAgent) "2026-01-01T00:00:00Z" else null)
        val server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = wireJson.parseToJsonElement(text).jsonObject
                    if (frame.string("type") == "hello") {
                        webSocket.send(DaemonSocketTest.session("status", jsonObject("status" to "server_info")))
                        return
                    }
                    val message = frame.obj("message")
                    requests.add(message)
                    if (holdAgents && message.string("type") == "fetch_agents_request" &&
                        message.obj("page").string("cursor") == "agents-2") {
                        heldAgents.complete(webSocket to message)
                    } else reply(webSocket, message)
                }
            }))
        }
        val host = Host(id = "host1", name = "Fixture", url = server.url("/ws").toString())
        val selection = WorkspaceSelection(host.id, "w2", "saved")
        val repository = PaseoRepository()

        private fun reply(peer: WebSocket, message: JsonObject) {
            val secondPage = message.obj("page").string("cursor").isNotEmpty()
            val payload = when (message.string("type")) {
                "fetch_workspaces_request" -> jsonObject(
                    "entries" to if (!secondPage) listOf(workspace("w1")) else if (missingWorkspace) emptyList() else listOf(workspace("w2")),
                    "pageInfo" to jsonObject("nextCursor" to if (secondPage) null else "workspaces-2"))
                "fetch_agents_request" -> jsonObject(
                    "entries" to (if (emptyWorkspace) listOf(agent("other", "w1"))
                        else if (secondPage) listOf(agent("saved")) else listOf(agent("first"), agent("other", "w1")))
                        .map { jsonObject("agent" to it) },
                    "pageInfo" to jsonObject("nextCursor" to if (secondPage) null else "agents-2"))
                "agent.timeline.set_subscription.request" -> jsonObject("subscriptionId" to "timeline")
                "fetch_agent_timeline_request" -> jsonObject("epoch" to "e1", "entries" to listOf(
                    jsonObject("seqStart" to 1, "item" to jsonObject("type" to "assistant_message", "text" to message.string("agentId")))))
                else -> jsonObject()
            }
            peer.send(DaemonSocketTest.session(message.string("type") + ".reply",
                JsonObject(payload + ("requestId" to JsonPrimitive(message.string("requestId"))))))
        }
        suspend fun releaseAgents() { val (peer, message) = withTimeout(5_000) { heldAgents.await() }; reply(peer, message) }
        suspend fun restored() = withTimeout(5_000) {
            repository.state.first { it.catalogLoaded && it.restoringWorkspace == null && !it.loadingTimeline }
        }
        fun assertReadOnly() = assertFalse(requests.any { it.string("type").contains("create") || it.string("type") == "send_agent_message_request" })
        override fun close() { repository.close(); server.close() }
    }

    @Test fun restoresExactAgentAfterBothCatalogsFinishPaging() = runBlocking {
        Fixture(holdAgents = true).use { f ->
            // Simulates an opaque selection persisted by a previous process.
            val saved = wireJson.decodeFromString<WorkspaceSelection>(wireJson.encodeToString(f.selection))
            f.repository.connect(f.host, saved)
            withTimeout(5_000) { f.heldAgents.await() }
            assertEquals(saved, f.repository.state.value.restoringWorkspace)
            assertNull(f.repository.state.value.selectedAgentId)
            assertFalse(f.requests.any { it.string("type") == "fetch_agent_timeline_request" })
            f.releaseAgents()
            val restored = f.restored()
            assertEquals(saved, restored.workspaceSelection())
            assertEquals("saved", restored.timeline.single().text)
            assertEquals(listOf("saved"), f.requests.filter { it.string("type") == "fetch_agent_timeline_request" }.map { it.string("agentId") })
            assertTrue(f.requests.any { it.obj("page").string("cursor") == "workspaces-2" })
            f.assertReadOnly()
        }
    }

    @Test fun missingOrArchivedAgentFallsBackWithinItsWorkspace() = runBlocking {
        for (archived in listOf(false, true)) Fixture(archivedAgent = archived).use { f ->
            f.repository.connect(f.host, f.selection.copy(agentId = if (archived) "saved" else "deleted"))
            val restored = f.restored()
            assertEquals("w2", restored.selectedWorkspaceId)
            assertEquals("first", restored.selectedAgentId)
            f.assertReadOnly()
        }
    }

    @Test fun missingWorkspaceAndEmptyWorkspaceDoNotCreateSessions() = runBlocking {
        Fixture(missingWorkspace = true).use { f ->
            f.repository.connect(f.host, f.selection)
            val restored = f.restored()
            assertNull(restored.selectedWorkspaceId)
            assertNull(restored.selectedAgentId)
            assertNull(restored.workspaceSelection())
            f.assertReadOnly()
        }
        Fixture(emptyWorkspace = true).use { f ->
            f.repository.connect(f.host, f.selection)
            val restored = f.restored()
            assertEquals(WorkspaceSelection(f.host.id, "w2"), restored.workspaceSelection())
            assertNull(restored.selectedAgentId)
            f.assertReadOnly()
        }
    }

    @Test fun manualSelectionWinsOverDelayedRestoration() = runBlocking {
        Fixture(holdAgents = true).use { f ->
            f.repository.connect(f.host, f.selection)
            withTimeout(5_000) { f.heldAgents.await() }
            withTimeout(5_000) { f.repository.state.first { it.catalogLoaded } }
            f.repository.openWorkspace(f.repository.state.value.workspaces.single())
            assertEquals(WorkspaceSelection(f.host.id, "w1", "other"), f.repository.state.value.workspaceSelection())
            f.releaseAgents()
            withTimeout(5_000) { f.repository.state.first { it.nextAgentCursor == null && it.timeline.isNotEmpty() } }
            assertEquals("other", f.repository.state.value.selectedAgentId)
            assertFalse(f.requests.any { it.string("agentId") == "saved" })
        }
    }

    @Test fun navigationAwayCancelsDelayedRestoration() = runBlocking {
        Fixture(holdAgents = true).use { f ->
            f.repository.connect(f.host, f.selection)
            withTimeout(5_000) { f.heldAgents.await() }
            f.repository.cancelWorkspaceRestore()
            f.releaseAgents()
            withTimeout(5_000) { f.repository.state.first { it.nextAgentCursor == null } }
            assertNull(f.restored().selectedWorkspaceId)
            assertFalse(f.requests.any { it.string("type") == "fetch_agent_timeline_request" })
        }
    }

    @Test fun selectionFromAnotherHostIsIgnored() = runBlocking {
        Fixture().use { f ->
            f.repository.connect(f.host, f.selection.copy(hostId = "other-host"))
            assertNull(f.restored().selectedAgentId)
            assertNull(f.repository.state.value.restoringWorkspace)
            f.assertReadOnly()
        }
    }

    @Test fun unavailableHostKeepsPendingSelection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val host = Host(name = "Unavailable", url = server.url("/ws").toString())
            val selection = WorkspaceSelection(host.id, "w2", "saved")
            val repository = PaseoRepository()
            try {
                repository.connect(host, selection)
                val offline = withTimeout(5_000) { repository.state.first { it.connection == ConnectionStatus.Reconnecting } }
                assertEquals(selection, offline.restoringWorkspace)
                assertFalse(offline.catalogLoaded)
                assertNull(offline.workspaceSelection())
            } finally { repository.close() }
        }
    }
}
