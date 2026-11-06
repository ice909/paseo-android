package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCatalogSortTest {
    @Test fun loadsEveryPageSoCreationSortCanSurfaceAnInactiveWorkspace() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                fun send(type: String, payload: JsonObject) { webSocket.send(DaemonSocketTest.session(type, payload)) }
                if (frame.string("type") == "hello") {
                    send("status", jsonObject("status" to "server_info", "serverId" to "test", "features" to jsonObject("workspaceSortTimes" to true)))
                    return
                }
                val message = frame.obj("message")
                val type = message.string("type")
                val result = if (type == "fetch_workspaces_request") {
                    val second = message.obj("page").string("cursor") == "second"
                    jsonObject("entries" to listOf(jsonObject(
                        "id" to if (second) "new-inactive" else "old-active", "projectId" to "p",
                        "projectDisplayName" to "P", "projectRootPath" to "/p", "name" to "Workspace",
                        "createdAt" to if (second) "2026-02-01T00:00:00Z" else "2026-01-01T00:00:00Z")),
                        "pageInfo" to jsonObject("nextCursor" to if (second) null else "second"))
                } else jsonObject()
                send("$type.response", JsonObject(result + ("requestId" to JsonPrimitive(message.string("requestId")))))
            }
        }))
        val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "test", url = server.url("/ws").toString()))
            val state = withTimeout(5_000) { repository.state.first { it.workspaces.size == 2 && it.nextWorkspaceCursor == null } }
            assertEquals("new-inactive", sortSidebarWorkspaces(state.workspaces, WorkspaceSort.Created, true).first().id)
            repository.refreshWorkspaces()
            assertEquals(2, repository.state.value.workspaces.size)
        } finally { repository.close(); server.close() }
    }
}
