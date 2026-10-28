package sh.paseo.android.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class DaemonSocketTest {
    @Test fun authenticatesAndCorrelatesRequestsAndErrors() = runBlocking {
        val frames = LinkedBlockingQueue<JsonObject>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                frames.put(frame)
                if (frame.string("type") == "hello") webSocket.send(session("status", jsonObject("status" to "server_info", "serverId" to "test")))
                else {
                    val message = frame.obj("message")
                    webSocket.send(session("rpc_error", jsonObject("requestId" to message.string("requestId"), "error" to "Unknown agent")))
                }
            }
        }))
        val socket = DaemonSocket()
        try {
            val info = socket.open(Host(name = "test", url = server.url("/ws").toString(), password = "密码 with spaces"))
            assertEquals("test", info.string("serverId"))
            val hello = frames.poll(5, TimeUnit.SECONDS)
            assertEquals("mobile", hello.string("clientType"))
            assertEquals("密码 with spaces", hello.obj("auth").string("password"))
            val failure = runCatching { socket.request("fetch_agent_request", "agentId" to "missing") }.exceptionOrNull()
            assertEquals("Unknown agent", failure?.message)
            assertEquals("fetch_agent_request", frames.poll(5, TimeUnit.SECONDS).obj("message").string("type"))
        } finally { socket.close(); server.close() }
    }
    @Test fun reportsAuthenticationRejection() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(jsonObject("type" to "hello.rejected", "reason" to "incorrect_password", "accepts" to listOf("password")).toString())
            }
        }))
        val socket = DaemonSocket()
        try {
            val error = runCatching { socket.open(Host(name = "test", url = server.url("/ws").toString(), password = "wrong")) }.exceptionOrNull()
            assertTrue(error is AuthenticationException)
            assertFalse(socket.connected.value)
        } finally { socket.close(); server.close() }
    }
    @Test fun releasesSubscriptionAllocatedAfterCallerCancellation() = runBlocking {
        val server = MockWebServer()
        val requested = CompletableDeferred<JsonObject>()
        val released = CompletableDeferred<JsonObject>()
        val peer = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer.complete(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                if (frame.string("type") == "hello") webSocket.send(session("status", jsonObject("status" to "server_info", "serverId" to "test")))
                else when (frame.obj("message").string("type")) {
                    "agent.timeline.set_subscription.request" -> requested.complete(frame.obj("message"))
                    "subscription.release.request" -> released.complete(frame.obj("message"))
                }
            }
        }))
        val socket = DaemonSocket()
        try {
            socket.open(Host(name = "test", url = server.url("/ws").toString()))
            val caller = launch { socket.request("agent.timeline.set_subscription.request", "agentIds" to listOf("a1")) }
            val request = withTimeout(5_000) { requested.await() }
            caller.cancelAndJoin()
            peer.await().send(session("agent.timeline.set_subscription.response", jsonObject("requestId" to request.string("requestId"), "subscriptionId" to "late-subscription")))
            assertEquals("late-subscription", withTimeout(5_000) { released.await() }.string("subscriptionId"))
        } finally { socket.close(); server.close() }
    }
    companion object {
        fun session(type: String, payload: JsonObject): String = jsonObject("type" to "session", "message" to jsonObject("type" to type, "payload" to payload)).toString()
    }
}
