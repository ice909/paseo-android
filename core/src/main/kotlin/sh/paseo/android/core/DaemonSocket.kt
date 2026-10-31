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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.Instant

enum class AuthFailure { PasswordRequired, IncorrectPassword, Incompatible, Security }
open class AuthenticationException(message: String, val reason: AuthFailure = AuthFailure.Incompatible) : IOException(message)

/** Direct and official relay transports share the same authenticated daemon session protocol. */
class DaemonSocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(),
) {
    private val generation = AtomicLong()
    private val outbound = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val pendingUpdates = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val incoming = Channel<Pair<Long, JsonObject>>(Channel.UNLIMITED)
    val messages = incoming.receiveAsFlow().filter { it.first == generation.get() }.map { it.second }
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private var socket: WebSocket? = null
    private var secure: RelayChannel? = null
    private var retryHandshake: Job? = null
    private var handshake: CompletableDeferred<JsonObject>? = null

    suspend fun open(host: Host): JsonObject {
        disconnect()
        val attempt = generation.incrementAndGet()
        val ready = CompletableDeferred<JsonObject>()
        handshake = ready
        val request = Request.Builder().url(host.url).apply {
            // A relay receives routing only. Passwords travel inside the encrypted hello.
            if (host.relay == null && host.password.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))
                header("Authorization", "Bearer ${host.password}")
        }.build()
        val channel = try { host.relay?.let(::RelayChannel) }
            catch (_: Exception) { throw AuthenticationException("配对公钥无法验证，请从电脑重新复制链接", AuthFailure.Security) }
        secure = channel
        fun transmit(peer: WebSocket, text: String) = synchronized(outbound) {
            if (attempt != generation.get()) return@synchronized
            check(peer.send(channel?.encrypt(text) ?: text)) { "连接已断开" }
        }
        fun hello(peer: WebSocket) = transmit(peer, jsonObject(
            "type" to "hello", "clientId" to "paseo-android-${newId()}",
            "clientType" to "mobile", "protocolVersion" to 1, "appVersion" to "0.6.0",
            "capabilities" to jsonObject("hello_rejection" to true, "all_providers" to true,
                "owned_subscriptions" to true, "selective_agent_timeline" to true,
                "timeline_replacement_invalidation" to true, "timeline_notifications" to true),
            "auth" to host.password.takeIf { it.isNotEmpty() }?.let { jsonObject("kind" to "password", "password" to it) },
        ).toString())
        fun failAttempt(error: Exception) = synchronized(outbound) {
            if (attempt == generation.get()) fail(error)
        }
        fun protocol(peer: WebSocket, text: String) = synchronized(outbound) {
            if (attempt != generation.get()) return@synchronized
            val frame = wireJson.parseToJsonElement(text).jsonObject
            when (frame.string("type")) {
                "ping" -> transmit(peer, jsonObject("type" to "pong").toString())
                "hello.rejected" -> {
                    val error = when (frame.string("reason")) {
                        "password_required" -> AuthenticationException("主机需要密码", AuthFailure.PasswordRequired)
                        "incorrect_password" -> AuthenticationException("主机密码不正确", AuthFailure.IncorrectPassword)
                        else -> AuthenticationException("客户端与主机协议不兼容")
                    }
                    fail(error); peer.close(1000, "Authentication failed")
                }
                "session" -> {
                    val message = frame.obj("message")
                    val payload = message.obj("payload")
                    if (message.string("type") == "status" && payload.string("status") == "server_info") {
                        if (channel != null && !channel.protected && payload.obj("features").bool("relayProtectedSecurity")) {
                            throw AuthenticationException("中继连接未通过安全校验，请重新复制配对链接", AuthFailure.Security)
                        }
                        _connected.value = true; ready.complete(payload)
                    }
                    val requestId = payload.string("requestId")
                    if (message.string("type") == "usage.list_reports.update") {
                        pendingUpdates[requestId]?.invoke(payload)
                    } else pending.remove(requestId)?.let { deferred ->
                        pendingUpdates.remove(requestId)
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
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (attempt != generation.get()) { webSocket.cancel(); return }
                try {
                    if (channel == null) hello(webSocket) else {
                        webSocket.send(channel.hello())
                        retryHandshake = cleanupScope.launch {
                            while (isActive) {
                                delay(1_000)
                                if (attempt != generation.get() || channel.open) return@launch
                                synchronized(outbound) {
                                    if (attempt == generation.get() && !channel.open) webSocket.send(channel.hello())
                                }
                            }
                        }
                    }
                } catch (e: Exception) { failAttempt(e); webSocket.close(4002, "Handshake failed") }
            }
            private fun receive(peer: WebSocket, text: String?, bytes: ByteArray?) {
                if (attempt != generation.get()) return
                try {
                    if (channel == null) { if (text != null) protocol(peer, text); return }
                    when (val result = channel.receive(text, bytes)) {
                        RelayResult.Ready -> { retryHandshake?.cancel(); hello(peer) }
                        RelayResult.Ignore -> Unit
                        is RelayResult.Frame -> if (!result.frame.binary) protocol(peer, strictUtf8(result.frame.data))
                    }
                } catch (e: Exception) {
                    val error = if (e is AuthenticationException) e else if (channel != null)
                        AuthenticationException("配对连接校验失败，请从电脑重新复制配对链接", AuthFailure.Security)
                        else IOException("无法解析主机协议消息", e)
                    failAttempt(error)
                    if (attempt == generation.get()) retryHandshake?.cancel()
                    channel?.close(); peer.close(4003, "Invalid secure frame")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = receive(webSocket, text, null)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(webSocket, null, bytes.toByteArray())
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (attempt != generation.get()) { channel?.close(); return }
                retryHandshake?.cancel(); channel?.close()
                failAttempt(when {
                    reason == "Password required" -> AuthenticationException("主机需要密码", AuthFailure.PasswordRequired)
                    reason == "Incorrect password" -> AuthenticationException("主机密码不正确", AuthFailure.IncorrectPassword)
                    code == 4002 || reason.contains("protected endpoint security") -> AuthenticationException("主机要求安全配对，请重新复制配对链接", AuthFailure.Security)
                    else -> IOException("连接已关闭 ($code)")
                })
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (attempt != generation.get()) { channel?.close(); return }
                retryHandshake?.cancel(); channel?.close()
                failAttempt(if (channel == null && (response?.code == 401 || response?.code == 403))
                    AuthenticationException("请检查主机密码", if (host.password.isEmpty()) AuthFailure.PasswordRequired else AuthFailure.IncorrectPassword)
                    else IOException("连接失败，请检查网络或主机是否在线", t))
            }
        })
        return try { withTimeout(20_000) { ready.await() } }
        catch (error: Exception) { disconnect(); throw error }
    }

    suspend fun request(type: String, vararg fields: Pair<String, Any?>, expectedGeneration: Long? = null, onUpdate: ((JsonObject) -> Unit)? = null): JsonObject {
        check(_connected.value) { "主机未连接" }
        val id = newId()
        val result = CompletableDeferred<JsonObject>()
        val attempt = generation.get()
        var deferredCleanup = false
        val createsSubscription = type == "agent.timeline.set_subscription.request" || fields.any { it.first == "subscribe" && it.second != null }
        pending[id] = result
        if (onUpdate != null) pendingUpdates[id] = onUpdate
        try {
            synchronized(outbound) {
                check(expectedGeneration == null || expectedGeneration == generation.get()) { "主机连接已改变" }
                send(type, *fields, "requestId" to id)
            }
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
        } finally { pendingUpdates.remove(id); if (!deferredCleanup) pending.remove(id) }
    }

    fun send(type: String, vararg fields: Pair<String, Any?>) = synchronized(outbound) {
        check(_connected.value) { "主机未连接" }
        val text = jsonObject("type" to "session", "message" to jsonObject("type" to type, *fields)).toString()
        val sent = socket?.send(secure?.encrypt(text) ?: text)
        check(sent == true) { "连接已断开" }
    }
    fun connectionToken(): Long = generation.get()
    suspend fun uploadFile(name: String, mime: String, size: Long, input: InputStream, expectedGeneration: Long? = null): JsonObject = withContext(Dispatchers.IO) {
        require(size in 0..50L * 1024 * 1024) { "文件不能超过 50 MB" }
        check(_connected.value) { "主机未连接" }
        val attempt = expectedGeneration ?: generation.get()
        val id = newId()
        val result = CompletableDeferred<JsonObject>()
        val modified = Instant.now().toString()
        pending[id] = result
        fun frame(opcode: Int, payload: ByteArray = byteArrayOf(), metadata: JsonObject? = null): ByteArray {
            val request = id.toByteArray()
            val body = metadata?.toString()?.toByteArray() ?: payload
            return ByteBuffer.allocate(2 + request.size + (if (metadata != null) 2 else 0) + body.size)
                .put(opcode.toByte()).put(request.size.toByte()).put(request).apply { if (metadata != null) putShort(body.size.toShort()) }.put(body).array()
        }
        suspend fun transmit(bytes: ByteArray) {
            while (true) {
                val sent = synchronized(outbound) {
                    check(attempt == generation.get() && _connected.value) { "附件上传期间连接已变化，请重试" }
                    val peer = socket ?: error("主机未连接")
                    if (peer.queueSize() > 1_048_576) false else {
                        val encrypted = secure?.encryptBinary(bytes)
                        val ok = when {
                            encrypted == null -> peer.send(bytes.toByteString())
                            encrypted.first -> peer.send(encrypted.second.toByteString())
                            else -> peer.send(encrypted.second.decodeToString())
                        }
                        check(ok) { "连接已断开" }; true
                    }
                }
                if (sent) break
                delay(10)
            }
        }
        try {
            withTimeout(120_000) {
                synchronized(outbound) {
                    check(attempt == generation.get()) { "附件上传期间连接已变化，请重试" }
                    send("file.upload.request", "requestId" to id, "fileName" to name, "mimeType" to mime, "size" to size, "modifiedAt" to modified)
                }
                transmit(frame(0x10, metadata = jsonObject("mime" to mime, "size" to size, "encoding" to "binary", "modifiedAt" to modified, "fileName" to name)))
                val chunk = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    if (result.isCompleted) break
                    val count = input.read(chunk)
                    if (count < 0) break
                    total += count
                    require(total <= size) { "文件大小已变化，请重新选择" }
                    transmit(frame(0x11, chunk.copyOf(count)))
                    yield()
                }
                if (!result.isCompleted) {
                    require(total == size) { "文件读取不完整，请重新选择" }
                    transmit(frame(0x12))
                }
                result.await().obj("file").also { check(it.string("type") == "uploaded_file") { "主机未返回上传文件" } }
            }
        } finally { pending.remove(id) }
    }

    fun disconnect() = synchronized(outbound) {
        generation.incrementAndGet(); retryHandshake?.cancel(); retryHandshake = null
        socket?.cancel(); socket = null; secure?.close(); secure = null
        fail(IOException("连接已断开"))
    }
    private fun fail(error: Exception) {
        _connected.value = false
        handshake?.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        pendingUpdates.clear()
    }
    fun close() {
        disconnect(); cleanupScope.cancel(); incoming.close()
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}
