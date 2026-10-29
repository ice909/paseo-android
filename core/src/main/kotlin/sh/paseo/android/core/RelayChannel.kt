package sh.paseo.android.core

import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.serialization.json.*

internal const val MAX_RELAY_WIRE_BYTES = 32 * 1024 * 1024
private const val CHUNK_DATA = 65535 - 16 - 6
internal data class RelayFrame(val binary: Boolean, val data: ByteArray)
internal sealed interface RelayResult {
    data object Ready : RelayResult
    data object Ignore : RelayResult
    data class Frame(val frame: RelayFrame) : RelayResult
}

/** Per-WebSocket state; callers serialize encrypt + send to preserve record/nonce order. */
internal class RelayChannel(private val offer: RelayOffer) {
    private val daemonKey = publicKeyBytes(offer.daemonPublicKeyB64)
    private val legacySecret = RelayCrypto.randomBytes(32)
    private var legacyKey = RelayCrypto.boxKey(legacySecret, daemonKey)
    private val noise = NoiseInitiator(daemonKey, "paseo-relay-e2ee/1".toByteArray())
    private val hello = jsonObject("type" to "e2ee_hello", "key" to b64(RelayCrypto.publicKey(legacySecret)),
        "capabilities" to jsonObject("binaryCiphertext" to true),
        "protected" to jsonObject("v" to 1, "message" to b64(noise.first(byteArrayOf(1, 1))))).toString()
    private var sender: NoiseCipher? = null
    private var receiver: NoiseCipher? = null
    private var binaryCiphertext = false
    private var closed = false
    @Volatile var protected: Boolean = false; private set
    @Volatile var open: Boolean = false; private set
    fun hello(): String = hello

    @Synchronized fun receive(text: String?, bytes: ByteArray? = null): RelayResult {
        check(!closed) { "Channel closed" }
        val envelope = text?.takeIf { it.trimStart().startsWith('{') }?.let { wireJson.parseToJsonElement(it).jsonObject }
        if (!open) {
            if (envelope?.string("type") != "e2ee_ready") return RelayResult.Ignore
            val caps = envelope["capabilities"]
            require(caps == null || caps is JsonObject) { "Invalid ready capabilities" }
            if (caps is JsonObject && caps.containsKey("binaryCiphertext")) require(caps["binaryCiphertext"] in listOf(JsonPrimitive(true), JsonPrimitive(false))) { "Invalid binary capability" }
            if (envelope.containsKey("protected")) {
                val protected = envelope["protected"] as? JsonObject ?: error("Invalid protected handshake")
                require(protected["v"] == JsonPrimitive(1)) { "Unsupported protected handshake version" }
                val params = noise.second(unb64(protected.string("message")))
                require(params.size == 2 && params[0] == 1.toByte() && (params[1].toInt() and 0xfe) == 0) { "Invalid protected handshake parameters" }
                binaryCiphertext = (params[1].toInt() and 1) == 1
                val keys = noise.split(); sender = keys.first; receiver = keys.second
                legacyKey.fill(0); legacySecret.fill(0); this.protected = true
            } else {
                check(offer.minimumSecurityMode != "protected") { "This host requires protected endpoint security" }
                binaryCiphertext = envelope.obj("capabilities").bool("binaryCiphertext")
                noise.close(); legacySecret.fill(0)
            }
            open = true
            return RelayResult.Ready
        }
        if (envelope != null) {
            require(envelope.string("type") in setOf("e2ee_ready", "e2ee_hello")) { "Plaintext received on encrypted channel" }
            return RelayResult.Ignore
        }
        val wire = bytes ?: unb64(text ?: error("Missing encrypted data"))
        require(wire.size <= MAX_RELAY_WIRE_BYTES) { "Encrypted frame too large" }
        val frame = if (protected) ProtectedRecord.decode(wire, receiver!!) else RelayFrame(
            binary = binaryCiphertext && bytes != null, data = RelayCrypto.boxDecrypt(legacyKey, wire))
        return RelayResult.Frame(frame)
    }
    @Synchronized fun encrypt(text: String): String {
        check(open && !closed) { "Encrypted channel not ready" }
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_RELAY_WIRE_BYTES) { "Message too large" }
        // Reject oversize payloads before reserving any Noise nonces. A rejected
        // application send must leave the current channel usable.
        val rawWireBytes = if (protected) bytes.size.toLong() + maxOf(1, (bytes.size + CHUNK_DATA - 1) / CHUNK_DATA) * 24L else bytes.size.toLong() + 40L
        require(4L * ((rawWireBytes + 2) / 3) <= MAX_RELAY_WIRE_BYTES) { "Message too large" }
        val encrypted = if (protected) ProtectedRecord.encode(bytes, sender!!) else RelayCrypto.boxEncrypt(legacyKey, bytes)
        val encoded = b64(encrypted)
        require(encoded.length <= MAX_RELAY_WIRE_BYTES) { "Message too large" }
        return encoded
    }
    @Synchronized fun close() {
        closed = true; open = false
        sender?.clear(); receiver?.clear(); noise.close(); legacyKey.fill(0); legacySecret.fill(0)
    }
}

internal object ProtectedRecord {
    private fun chunks(size: Int) = maxOf(1, (size + CHUNK_DATA - 1) / CHUNK_DATA)
    private fun length(size: Int): Long = size.toLong() + chunks(size) * 24L
    fun encode(data: ByteArray, cipher: NoiseCipher): ByteArray {
        val wire = length(data.size)
        require(wire <= MAX_RELAY_WIRE_BYTES) { "Message too large" }
        val out = ByteBuffer.allocate(wire.toInt())
        repeat(chunks(data.size)) { i ->
            val start = i * CHUNK_DATA
            val size = minOf(CHUNK_DATA, data.size - start)
            val flags = (if (i == 0) 1 else 0) or (if (i == chunks(data.size) - 1) 2 else 0)
            val plain = ByteBuffer.allocate(6 + size).put(0).put(flags.toByte()).putInt(data.size).put(data, start, size).array()
            val encrypted = cipher.encrypt(byteArrayOf(), plain)
            out.putShort(encrypted.size.toShort()).put(encrypted)
        }
        return out.array()
    }
    fun decode(data: ByteArray, cipher: NoiseCipher): RelayFrame {
        require(data.isNotEmpty() && data.size <= MAX_RELAY_WIRE_BYTES) { "Invalid protected record length" }
        val buffer = ByteBuffer.wrap(data)
        var frame: ByteArray? = null
        var kind = -1
        var index = 0
        var written = 0
        while (buffer.hasRemaining()) {
            require(buffer.remaining() >= 2) { "Protected record truncated" }
            val size = buffer.short.toInt() and 0xffff
            require(size in 22..65535 && buffer.remaining() >= size) { "Invalid protected chunk length" }
            val plain = ByteBuffer.wrap(cipher.decrypt(byteArrayOf(), ByteArray(size).also { buffer.get(it) }))
            val nextKind = plain.get().toInt()
            val flags = plain.get().toInt() and 0xff
            val total = plain.int.toLong() and 0xffffffffL
            require(nextKind in 0..1 && (flags and 0xfc) == 0 && total <= MAX_RELAY_WIRE_BYTES) { "Invalid protected record header" }
            if (frame == null) {
                require(length(total.toInt()) == data.size.toLong()) { "Protected record length mismatch" }
                frame = ByteArray(total.toInt()); kind = nextKind
            }
            require(kind == nextKind && total == frame.size.toLong() && index < chunks(frame.size)) { "Protected record framing changed" }
            val last = index == chunks(frame.size) - 1
            val expectedFlags = (if (index == 0) 1 else 0) or (if (last) 2 else 0)
            val expectedSize = if (last) frame.size - index * CHUNK_DATA else CHUNK_DATA
            require(flags == expectedFlags && plain.remaining() == expectedSize) { "Invalid protected record framing" }
            plain.get(frame, written, expectedSize)
            written += expectedSize; index++
        }
        require(frame != null && written == frame.size && index == chunks(frame.size)) { "Protected record incomplete" }
        return RelayFrame(kind == 1, frame)
    }
}
internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun unb64(text: String): ByteArray {
    require(text.length <= MAX_RELAY_WIRE_BYTES) { "Encrypted frame too large" }
    return Base64.getDecoder().decode(text)
}
