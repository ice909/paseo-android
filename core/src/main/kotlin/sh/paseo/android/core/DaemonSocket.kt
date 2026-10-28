package sh.paseo.android.core

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import okhttp3.*

class AuthenticationException(message: String) : IOException(message)

/** Wire transport only. Callers own reconnect policy and subscription restoration. */
class DaemonSocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(),
) {
    private val generation = AtomicLong()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val incoming = Channel<Pair<Long, JsonObject>>(Channel.UNLIMITED)
    val messages = incoming.receiveAsFlow().filter { it.first == generation.get() }.map { it.second }
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private var socket: WebSocket? = null
    private var handshake: CompletableDeferred<JsonObject>? = null

    suspend fun open(host: Host): JsonObject {
        disconnect()
        val attempt = generation.incrementAndGet()
        val ready = CompletableDeferred<JsonObject>()
        handshake = ready
        val request = Request.Builder().url(host.url).apply {
            // Compatibility with daemons that authenticate during the HTTP upgrade.
            if (host.password.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))
                header("Authorization", "Bearer ${host.password}")
        }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (attempt != generation.get()) { webSocket.cancel(); return }
                webSocket.send(jsonObject(
                    "type" to "hello", "clientId" to "paseo-android-${newId()}",
                    "clientType" to "mobile", "protocolVersion" to 1, "appVersion" to "0.1.0",
                    "capabilities" to jsonObject(
                        "hello_rejection" to true, "all_providers" to true,
                        "owned_subscriptions" to true, "selective_agent_timeline" to true,
                        "timeline_replacement_invalidation" to true, "timeline_notifications" to true,
                    ),
                    "auth" to host.password.takeIf { it.isNotEmpty() }?.let {
                        jsonObject("kind" to "password", "password" to it)
                    },
                ).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (attempt != generation.get()) return
                try {
                    val frame = wireJson.parseToJsonElement(text).jsonObject
                    when (frame.string("type")) {
                        "ping" -> webSocket.send(jsonObject("type" to "pong").toString())
                        "hello.rejected" -> {
                            val reason = when (frame.string("reason")) {
                                "password_required" -> "主机需要密码"
                                "incorrect_password" -> "主机密码不正确"
                                else -> "客户端与主机协议不兼容"
                            }
                            fail(AuthenticationException(reason))
                            webSocket.close(1000, "Authentication failed")
                        }
                        "session" -> {
                            val message = frame.obj("message")
                            val payload = message.obj("payload")
                            if (message.string("type") == "status" && payload.string("status") == "server_info") {
                                _connected.value = true
                                ready.complete(payload)
                            }
                            val requestId = payload.string("requestId")
                            pending.remove(requestId)?.let { deferred ->
                                val error = payload["error"]
                                when {
                                    message.string("type") == "rpc_error" -> deferred.completeExceptionally(IOException(payload.string("error")))
                                    error is JsonPrimitive && error.contentOrNull != null -> deferred.completeExceptionally(IOException(error.content))
                                    error is JsonObject -> deferred.completeExceptionally(IOException(error.string("message").ifBlank { error.toString() }))
                                    payload["accepted"] == JsonPrimitive(false) -> deferred.completeExceptionally(IOException("主机拒绝了操作"))
                                    else -> deferred.complete(payload)
                                }
                            }
                            incoming.trySend(attempt to message)
                        }
                    }
                } catch (error: Exception) {
                    fail(IOException("无法解析主机协议消息", error))
                    webSocket.close(1002, "Invalid protocol message")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (attempt == generation.get()) fail(when (reason) {
                    "Password required" -> AuthenticationException("主机需要密码")
                    "Incorrect password" -> AuthenticationException("主机密码不正确")
                    else -> IOException(reason.ifBlank { "连接已关闭 ($code)" })
                })
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (attempt == generation.get()) fail(if (response?.code == 401 || response?.code == 403)
                    AuthenticationException("主机拒绝连接，请检查密码和访问权限") else IOException("连接失败：${response?.code ?: t.message}"))
            }
        })
        return try {
            withTimeout(15_000) { ready.await() }
        } catch (error: Exception) {
            disconnect()
            throw error
        }
    }

    suspend fun request(type: String, vararg fields: Pair<String, Any?>): JsonObject {
        check(_connected.value) { "主机未连接" }
        val id = newId()
        val result = CompletableDeferred<JsonObject>()
        val attempt = generation.get()
        var deferredCleanup = false
        val createsSubscription = type == "agent.timeline.set_subscription.request" || fields.any { it.first == "subscribe" && it.second != null }
        pending[id] = result
        try {
            send(type, *fields, "requestId" to id)
            return withTimeout(30_000) { result.await() }
        } catch (cancelled: CancellationException) {
            if (createsSubscription) {
                deferredCleanup = true
                // A cancelled UI request may still have allocated a subscription on the host.
                cleanupScope.launch {
                    try {
                        val payload = withTimeout(30_000) { result.await() }
                        val subscription = payload.string("subscriptionId")
                        if (subscription.isNotBlank() && attempt == generation.get() && _connected.value)
                            send("subscription.release.request", "subscriptionId" to subscription, "requestId" to newId())
                    } catch (_: Exception) { /* Socket closure releases all remaining subscriptions. */ }
                    finally { pending.remove(id) }
                }
            }
            throw cancelled
        } finally { if (!deferredCleanup) pending.remove(id) }
    }

    fun send(type: String, vararg fields: Pair<String, Any?>) {
        check(_connected.value) { "主机未连接" }
        val sent = socket?.send(jsonObject("type" to "session", "message" to jsonObject("type" to type, *fields)).toString())
        check(sent == true) { "连接已断开" }
    }

    fun disconnect() {
        generation.incrementAndGet()
        socket?.cancel()
        socket = null
        fail(IOException("连接已断开"))
    }
    private fun fail(error: Exception) {
        _connected.value = false
        handshake?.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
    fun close() {
        disconnect()
        cleanupScope.cancel()
        incoming.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
