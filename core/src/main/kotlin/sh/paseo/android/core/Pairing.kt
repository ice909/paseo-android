package sh.paseo.android.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class RelayOffer(val serverId: String, val daemonPublicKeyB64: String, val endpoint: String,
    val useTls: Boolean, val minimumSecurityMode: String? = null) {
    fun pairingLink(): String {
        val payload = jsonObject("v" to 2, "serverId" to serverId, "daemonPublicKeyB64" to daemonPublicKeyB64,
            "relay" to jsonObject("endpoint" to endpoint, "useTls" to useTls), "minimumSecurityMode" to minimumSecurityMode)
        return "https://app.paseo.sh/#offer=" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
    }
}

data class Endpoint(val url: String, val password: String? = null, val relay: RelayOffer? = null) {
    companion object {
        /** Official v2 offers, legacy relay links, and direct addresses. No credentials go to a relay. */
        fun parse(input: String): Endpoint {
            val text = input.trim()
            require(text.isNotEmpty()) { "请粘贴配对链接或输入主机地址" }
            if ("#offer=" in text) {
                val encoded = text.substringAfter("#offer=").trim()
                require(encoded.isNotEmpty() && encoded.length <= 16_384) { "配对链接不完整，请重新复制" }
                val offer = try {
                    val bytes = Base64.getUrlDecoder().decode(encoded)
                    wireJson.parseToJsonElement(strictUtf8(bytes)).jsonObject
                } catch (_: Exception) { throw IllegalArgumentException("配对链接无效，请重新复制完整链接") }
                require(offer["v"] == JsonPrimitive(2)) { "此配对链接版本暂不支持，请在电脑上重新生成链接" }
                val relay = offer.obj("relay")
                return relayEndpoint(offer.string("serverId"), offer.string("daemonPublicKeyB64"),
                    relay.string("endpoint"), relay["useTls"], offer["minimumSecurityMode"], null)
            }
            val raw = if ("#connect=" in text) decodeComponent(text.substringAfter("#connect=")) else text
            val uri = try { URI(if ("://" in raw) raw else "ws://$raw") }
                catch (_: Exception) { throw IllegalArgumentException("主机地址或配对链接格式不正确") }
            val query = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            if (uri.scheme.equals("relay", true)) {
                require(uri.host != null && uri.port in 1..65535 && uri.userInfo == null && uri.fragment == null) { "中继配对链接格式不正确" }
                val id = decodeComponent(uri.rawPath.orEmpty().removePrefix("/"))
                return relayEndpoint(id, query["key"].orEmpty(), uri.rawAuthority,
                    JsonPrimitive(query["ssl"] == "true"), query["security"]?.let(::JsonPrimitive), query["password"])
            }
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                "tcp" -> if (query["ssl"] == "true") "wss" else "ws"
                else -> error("请使用 Paseo 配对链接或 ws / wss / tcp 地址")
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "主机地址格式不正确" }
            require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
            val path = uri.rawPath.orEmpty().trimEnd('/').let { if (it.endsWith("/ws")) it else "$it/ws" }
            val retainedQuery = uri.rawQuery?.split('&')?.filter {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") !in setOf("password", "ssl")
            }?.joinToString("&")?.ifBlank { null }
            return Endpoint("$scheme://${uri.rawAuthority}$path" + (retainedQuery?.let { "?$it" } ?: ""), query["password"])
        }
        private fun relayEndpoint(id: String, key: String, endpoint: String, tls: JsonElement?, minimum: JsonElement?, password: String?): Endpoint {
            require(id.isNotBlank() && id.length <= 512 && '/' !in id && id.none(Char::isISOControl)) { "配对链接中的主机标识无效" }
            publicKeyBytes(key)
            val address = try { URI("ws://$endpoint") } catch (_: Exception) { throw IllegalArgumentException("中继地址无效") }
            require(address.host != null && address.port in 1..65535 && address.userInfo == null && address.rawPath.isNullOrEmpty() && address.rawQuery == null && address.fragment == null) { "中继地址无效" }
            require(tls == null || tls == JsonNull || tls == JsonPrimitive(true) || tls == JsonPrimitive(false)) { "配对链接中的 TLS 设置无效" }
            val protected = if (minimum == null || minimum == JsonNull) null else {
                require(minimum == JsonPrimitive("protected")) { "配对链接使用了暂不支持的安全模式" }
                "protected"
            }
            val useTls = tls?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean ?: (address.port == 443)
            val offer = RelayOffer(id, key, address.rawAuthority, useTls, protected)
            val server = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
            val url = "${if (useTls) "wss" else "ws"}://${address.rawAuthority}/ws?serverId=$server&role=client&v=2"
            return Endpoint(url, password, offer)
        }
    }
}

internal fun publicKeyBytes(value: String): ByteArray {
    val decoded = try { Base64.getDecoder().decode(value) } catch (_: Exception) { throw IllegalArgumentException("配对链接中的主机公钥无效") }
    require(decoded.size == 32 && Base64.getEncoder().encodeToString(decoded) == value && decoded.any { it != 0.toByte() }) { "配对链接中的主机公钥无效" }
    return decoded
}
internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
private fun decodeComponent(value: String): String = try { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
    catch (_: Exception) { throw IllegalArgumentException("配对链接编码无效") }
