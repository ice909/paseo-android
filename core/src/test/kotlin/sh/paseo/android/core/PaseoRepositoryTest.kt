package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class PaseoRepositoryTest {
    @Test fun connectsLoadsHistorySendsAndHandlesPermissions() = runBlocking {
        val server = MockWebServer()
        var peer: WebSocket? = null
        var permissionResponse: JsonObject? = null
        val agent = jsonObject("id" to "agent1", "provider" to "claude", "cwd" to "/project", "title" to "Review code")
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                fun reply(type: String, payload: JsonObject) { webSocket.send(DaemonSocketTest.session(type, payload)) }
                if (frame.string("type") == "hello") { reply("status", jsonObject("status" to "server_info", "serverId" to "test", "hostname" to "dev")); return }
                val message = frame.obj("message")
                val requestId = message.string("requestId")
                when (message.string("type")) {
                    "fetch_workspaces_request" -> reply("fetch_workspaces_response", jsonObject("requestId" to requestId, "entries" to emptyList<Any>(), "pageInfo" to jsonObject("hasMore" to false)))
                    "fetch_agents_request" -> reply("fetch_agents_response", jsonObject("requestId" to requestId, "subscriptionId" to "catalog", "entries" to listOf(jsonObject("agent" to agent)), "pageInfo" to jsonObject("hasMore" to false)))
                    "list_available_providers_request" -> reply("list_available_providers_response", jsonObject("requestId" to requestId, "providers" to listOf(jsonObject("provider" to "claude", "available" to true))))
                    "agent.timeline.set_subscription.request" -> reply("agent.timeline.set_subscription.response", jsonObject("requestId" to requestId, "subscriptionId" to "timeline", "agentIds" to listOf("agent1")))
                    "fetch_agent_timeline_request" -> reply("fetch_agent_timeline_response", jsonObject("requestId" to requestId, "agentId" to "agent1", "agent" to agent, "epoch" to "epoch1", "direction" to "tail", "window" to jsonObject("maxSeq" to 1), "entries" to listOf(jsonObject("seqStart" to 1, "item" to jsonObject("type" to "assistant_message", "text" to "Hello")))))
                    "send_agent_message_request" -> {
                        reply("send_agent_message_response", jsonObject("requestId" to requestId, "accepted" to true))
                        reply("agent_stream", jsonObject("agentId" to "agent1", "epoch" to "epoch1", "seq" to 2, "event" to jsonObject("type" to "timeline", "item" to jsonObject("type" to "user_message", "text" to message.string("text"), "messageId" to message.string("messageId")))))
                    }
                    "agent_permission_response" -> permissionResponse = message
                    "subscription.release.request" -> reply("subscription.release.response", jsonObject("requestId" to requestId, "subscriptionId" to message.string("subscriptionId")))
                }
            }
        }))
        val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "Test", url = server.url("/ws").toString()))
            withTimeout(5_000) { repository.state.first { it.agents.isNotEmpty() && it.providers.isNotEmpty() } }
            repository.selectAgent("agent1")
            val loaded = withTimeout(5_000) { repository.state.first { it.timeline.isNotEmpty() } }
            assertEquals("Hello", loaded.timeline.single().text)
            repository.sendMessage("agent1", "Review this", "client-message")
            withTimeout(5_000) { repository.state.first { it.timeline.lastOrNull()?.text == "Review this" } }
            peer!!.send(DaemonSocketTest.session("agent_permission_request", jsonObject("agentId" to "agent1", "request" to jsonObject("id" to "perm1", "name" to "shell", "kind" to "tool"))))
            val waiting = withTimeout(5_000) { repository.state.first { it.selectedAgent?.pendingPermissions?.isNotEmpty() == true } }
            repository.respondToPermission("agent1", waiting.selectedAgent!!.pendingPermissions.single(), "deny")
            withTimeout(5_000) { while (permissionResponse == null) delay(10) }
            assertEquals("perm1", permissionResponse!!.string("requestId"))
            assertEquals("deny", permissionResponse!!.obj("response").string("behavior"))
        } finally { repository.close(); server.close() }
    }

    @Test fun foregroundLivenessFailureKeepsTheCurrentWorkspaceSelection() = runBlocking {
        val server = MockWebServer()
        val workspace = jsonObject(
            "id" to "workspace1", "projectId" to "project1", "projectDisplayName" to "Project",
            "projectRootPath" to "/project", "name" to "Workspace",
        )
        val agent = jsonObject(
            "id" to "agent1", "provider" to "claude", "cwd" to "/project",
            "workspaceId" to "workspace1", "title" to "Session",
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                if (frame.string("type") == "hello") {
                    webSocket.send(DaemonSocketTest.session("status", jsonObject("status" to "server_info", "serverId" to "test")))
                    return
                }
                if (frame.string("type") == "ping") return
                val message = frame.obj("message")
                val requestId = message.string("requestId")
                val (type, payload) = when (message.string("type")) {
                    "fetch_agents_request" -> "fetch_agents_response" to jsonObject(
                        "requestId" to requestId, "subscriptionId" to "agents",
                        "entries" to listOf(jsonObject("agent" to agent)),
                        "pageInfo" to jsonObject("hasMore" to false),
                    )
                    "fetch_workspaces_request" -> "fetch_workspaces_response" to jsonObject(
                        "requestId" to requestId, "subscriptionId" to "workspaces",
                        "entries" to listOf(workspace), "pageInfo" to jsonObject("hasMore" to false),
                    )
                    "list_available_providers_request" -> "list_available_providers_response" to jsonObject("requestId" to requestId, "providers" to emptyList<Any>())
                    "agent.timeline.set_subscription.request" -> "agent.timeline.set_subscription.response" to jsonObject("requestId" to requestId, "subscriptionId" to "timeline")
                    "fetch_agent_timeline_request" -> "fetch_agent_timeline_response" to jsonObject(
                        "requestId" to requestId, "agentId" to "agent1", "agent" to agent,
                        "epoch" to "epoch1", "direction" to "tail", "window" to jsonObject("maxSeq" to 0),
                        "entries" to emptyList<Any>(),
                    )
                    else -> return
                }
                webSocket.send(DaemonSocketTest.session(type, payload))
            }
        }))
        val repository = PaseoRepository()
        try {
            val host = Host(id = "test-host", name = "Test", url = server.url("/ws").toString())
            repository.connect(
                host,
                WorkspaceSelection("test-host", "workspace1", "agent1"),
            )
            withTimeout(5_000) {
                repository.state.first { it.connection == ConnectionStatus.Connected && it.selectedAgentId == "agent1" }
            }
            repository.verifyConnection(timeoutMs = 50)
            withTimeout(5_000) { repository.state.first { it.connection == ConnectionStatus.Reconnecting } }
            val recovered = repository.state.value
            assertEquals("workspace1", recovered.selectedWorkspaceId)
            assertEquals("agent1", recovered.selectedAgentId)
            assertNull(recovered.restoringWorkspace)
        } finally { repository.close(); server.close() }
    }
}
