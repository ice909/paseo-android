package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HostLifecycleTest {
    private class Fixture(val cleanupUnconfirmed: Boolean = false, val wrongUpdateVersion: Boolean = false) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        var pid = 1
        val server = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = wireJson.parseToJsonElement(text).jsonObject
                    if (frame.string("type") == "hello") {
                        webSocket.send(DaemonSocketTest.session("status", jsonObject("status" to "server_info", "serverId" to "server",
                            "features" to jsonObject("daemonStatusRpc" to true, "daemonSelfUpdate" to true, "terminalsSurviveRestart" to true))))
                        return
                    }
                    val message = frame.obj("message")
                    requests.add(message)
                    val payload = when (message.string("type")) {
                        "daemon.get_status.request" -> jsonObject("serverId" to "server", "pid" to pid,
                            "version" to if (wrongUpdateVersion) "old" else "new",
                            "terminalCleanupUnconfirmed" to if (cleanupUnconfirmed && pid > 1) listOf(jsonObject("pid" to 42)) else emptyList())
                        "restart_server_request" -> { pid++; jsonObject("status" to "restart_requested") }
                        "daemon.update.request" -> { pid++; jsonObject("success" to true, "newVersion" to "new") }
                        else -> jsonObject()
                    }
                    webSocket.send(DaemonSocketTest.session(message.string("type") + ".reply",
                        JsonObject(payload + ("requestId" to JsonPrimitive(message.string("requestId"))))))
                }
            }))
        }
        val repository = PaseoRepository()
        suspend fun connect() {
            repository.connect(Host(name = "Fixture", url = server.url("/ws").toString(), serverId = "server"))
            withTimeout(5_000) { repository.state.first { it.catalogLoaded } }
        }
        override fun close() { repository.close(); server.close() }
    }

    @Test fun restartConfirmsNewWorkerAndDoesNotKillTerminalsByDefault() = runBlocking {
        Fixture().use { f ->
            f.connect()
            withTimeout(5_000) { f.repository.performHostLifecycle("restart") }
            assertEquals(2, f.requests.count { it.string("type") == "daemon.get_status.request" })
            assertFalse(f.requests.single { it.string("type") == "restart_server_request" }.containsKey("killTerminals"))
        }
    }
    @Test fun closingTerminalsReportsUnconfirmedProcesses() = runBlocking {
        Fixture(cleanupUnconfirmed = true).use { f ->
            f.connect()
            val error = runCatching { withTimeout(5_000) { f.repository.performHostLifecycle("killTerminals") } }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("42"))
            assertTrue(f.requests.single { it.string("type") == "restart_server_request" }.bool("killTerminals"))
        }
    }
    @Test fun updatingRequiresTheInstalledVersionToBeObserved() = runBlocking {
        for (wrong in listOf(false, true)) Fixture(wrongUpdateVersion = wrong).use { f ->
            f.connect()
            val result = runCatching { withTimeout(5_000) { f.repository.performHostLifecycle("update") } }
            assertEquals(!wrong, result.isSuccess)
            assertEquals(1, f.requests.count { it.string("type") == "daemon.update.request" })
        }
    }
}
