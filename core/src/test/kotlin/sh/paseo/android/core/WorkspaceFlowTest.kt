package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WorkspaceFlowTest {
    private val workspace = jsonObject("id" to "w1", "projectId" to "p1", "projectDisplayName" to "Project", "projectRootPath" to "/project", "workspaceDirectory" to "/project/tree", "name" to "feature", "gitRuntime" to JsonNull)
    private val agent = jsonObject("id" to "a1", "workspaceId" to "w1", "cwd" to "/project/tree", "provider" to "claude", "title" to "Agent")
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private lateinit var peer: WebSocket
    private fun server(failed: Boolean = false): MockWebServer = MockWebServer().apply {
        enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                fun reply(type: String, payload: JsonObject) { webSocket.send(DaemonSocketTest.session(type, payload)) }
                if (frame.string("type") == "hello") {
                    reply("status", jsonObject("status" to "server_info", "features" to jsonObject("creationLifecycle" to true))); return
                }
                val m = frame.obj("message"); val id = m.string("requestId"); requests.add(m)
                val payload = when (m.string("type")) {
                    "fetch_workspaces_request" -> jsonObject("entries" to listOf(workspace), "emptyProjects" to listOf(jsonObject("projectId" to "empty", "projectDisplayName" to "Empty", "projectRootPath" to "/empty")), "subscriptionId" to "workspaces")
                    "fetch_agents_request" -> jsonObject("entries" to listOf(jsonObject("agent" to agent)), "subscriptionId" to "agents")
                    "list_available_providers_request" -> jsonObject("providers" to listOf(jsonObject("provider" to "claude", "available" to true)))
                    "workspace.create.request", "agent.create.request" -> jsonObject("creation" to jsonObject("phase" to "accepted"))
                    "creation.subscribe.request" -> jsonObject("snapshot" to jsonObject("phase" to if (failed) "failed" else "completed", "workspace" to workspace, "agent" to agent, "error" to if (failed) "Agent unavailable" else null))
                    "agent.timeline.set_subscription.request" -> jsonObject("subscriptionId" to "timeline")
                    "fetch_agent_timeline_request" -> jsonObject("agent" to agent, "entries" to emptyList<Any>(), "epoch" to "e1")
                    "list_terminals_request" -> jsonObject("terminals" to emptyList<Any>())
                    else -> jsonObject()
                }
                reply(m.string("type") + ".reply", JsonObject(payload + ("requestId" to JsonPrimitive(id))))
            }
        }))
    }
    private suspend fun awaitCatalog(repository: PaseoRepository) = withTimeout(5_000) {
        repository.state.first { it.catalogLoaded && it.agents.isNotEmpty() }
    }
    @Test fun catalogCreationAndAgentMembershipUseWorkspaceIdentity() = runBlocking {
        val server = server(); val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "Test", url = server.url("/ws").toString()))
            val initial = awaitCatalog(repository)
            assertEquals(2, initial.projects.size)
            assertNull(initial.workspaces.single().gitRuntime)
            val created = repository.createWorkspace("claude", "/project", "sonnet", "plan", "Review this", "Review", "intent-1", "p1", true, "feature")
            assertEquals("w1", created.id)
            val request = requests.first { it.string("type") == "workspace.create.request" }
            assertEquals("intent-1", request.string("idempotencyKey"))
            assertEquals("worktree", request.obj("source").string("kind"))
            assertEquals("plan", request.obj("agent").obj("config").string("modeId"))
            assertEquals("Review this", request.obj("agent").string("initialPrompt"))
            val selected = withTimeout(5_000) { repository.state.first { it.selectedAgentId == "a1" } }
            assertEquals(1, selected.workspaceAgents.size)
            assertFalse(created.contains(Agent("different-workspace", cwd = created.cwd, workspaceId = "w2")))
            repository.createAgent(created, "claude", "", "", "Second", "Second", "intent-2")
            assertEquals("w1", requests.first { it.string("type") == "agent.create.request" }.string("workspaceId"))
            repository.terminals(created.context)
            assertEquals("w1", requests.last { it.string("type") == "list_terminals_request" }.string("workspaceId"))
            peer.send(DaemonSocketTest.session("workspace_update", jsonObject("kind" to "upsert", "workspace" to JsonObject(workspace + ("name" to JsonPrimitive("renamed"))))))
            withTimeout(5_000) { repository.state.first { it.selectedWorkspace?.name == "renamed" } }
            peer.send(DaemonSocketTest.session("workspace_update", jsonObject("kind" to "remove", "id" to "w1", "emptyProject" to jsonObject("projectId" to "p1", "projectDisplayName" to "Project", "projectRootPath" to "/project"))))
            val removed = withTimeout(5_000) { repository.state.first { it.workspaces.isEmpty() } }
            assertEquals(2, removed.emptyProjects.size)
            assertNull(removed.selectedWorkspaceId)
        } finally { repository.close(); server.close() }
    }
    @Test fun failedCreationKeepsProvisionedWorkspaceAndDoesNotSelectAnAgent() = runBlocking {
        val server = server(failed = true); val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "Test", url = server.url("/ws").toString())); awaitCatalog(repository)
            try { repository.createWorkspace("claude", "/project", "", "", "", "", "failed-intent"); fail("Should fail") }
            catch (e: java.io.IOException) { assertEquals("Agent unavailable", e.message) }
            assertEquals("w1", repository.state.value.workspaces.single().id)
            assertNull(repository.state.value.selectedAgentId)
        } finally { repository.close(); server.close() }
    }
}
