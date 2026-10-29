package sh.paseo.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519

/** Wire-compatible primitives. Bouncy Castle supplies the crypto, without replacing Android's providers. */
internal object RelayCrypto {
    private val random = SecureRandom()
    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    fun publicKey(secret: ByteArray) = ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) }
    fun dh(secret: ByteArray, public: ByteArray): ByteArray {
        require(secret.size == 32 && public.size == 32) { "Invalid X25519 key" }
        return ByteArray(32).also {
            X25519.scalarMult(secret, 0, public, 0, it, 0)
            require(it.any { b -> b != 0.toByte() }) { "Invalid X25519 public key" }
        }
    }
    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    }
    fun hkdf(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(key, input)
        val first = hmac(temp, byteArrayOf(1))
        val second = hmac(temp, first + byteArrayOf(2))
        temp.fill(0)
        return first to second
    }
    fun chacha(key: ByteArray, nonce: ULong, ad: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putLong(nonce.toLong()).array()
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, iv, ad))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val count = cipher.processBytes(data, 0, data.size, out, 0)
        val total = count + cipher.doFinal(out, count)
        return out.copyOf(total)
    }
    // NaCl box.before = HSalsa20(X25519 secret, 16 zero bytes). Salsa core includes
    // feed-forward, which HSalsa20 omits for its selected output words.
    fun boxKey(secret: ByteArray, public: ByteArray): ByteArray {
        val shared = dh(secret, public)
        val input = IntArray(16)
        input[0] = 0x61707865; input[5] = 0x3320646e; input[10] = 0x79622d32; input[15] = 0x6b206574
        val little = ByteBuffer.wrap(shared).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..3) input[i + 1] = little.int
        for (i in 0..3) input[i + 11] = little.int
        val output = IntArray(16)
        Salsa20Engine.salsaCore(20, input, output)
        val key = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for (i in intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)) key.putInt(output[i] - input[i])
        shared.fill(0); input.fill(0); output.fill(0)
        return key.array()
    }
    fun boxEncrypt(key: ByteArray, plaintext: ByteArray, nonce: ByteArray = randomBytes(24)): ByteArray {
        val stream = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(32).also { stream.processBytes(it, 0, it.size, it, 0) }
        val encrypted = ByteArray(plaintext.size)
        stream.processBytes(plaintext, 0, plaintext.size, encrypted, 0)
        val tag = poly(polyKey, encrypted)
        polyKey.fill(0)
        return nonce + tag + encrypted
    }
    fun boxDecrypt(key: ByteArray, bundle: ByteArray): ByteArray {
        require(bundle.size >= 40) { "Encrypted record truncated" }
        val stream = XSalsa20Engine().apply { init(false, ParametersWithIV(KeyParameter(key), bundle.copyOfRange(0, 24))) }
        val polyKey = ByteArray(32).also { stream.processBytes(it, 0, it.size, it, 0) }
        val encrypted = bundle.copyOfRange(40, bundle.size)
        val tag = poly(polyKey, encrypted)
        polyKey.fill(0)
        require(MessageDigest.isEqual(tag, bundle.copyOfRange(24, 40))) { "Encrypted record authentication failed" }
        return ByteArray(encrypted.size).also { stream.processBytes(encrypted, 0, encrypted.size, it, 0) }
    }
    private fun poly(key: ByteArray, data: ByteArray): ByteArray = ByteArray(16).also { tag ->
        Poly1305().apply { init(KeyParameter(key)); update(data, 0, data.size); doFinal(tag, 0) }
    }
}

internal class NoiseCipher(private var key: ByteArray, private var nonce: ULong = 0uL) {
    fun encrypt(ad: ByteArray, data: ByteArray): ByteArray {
        check(nonce != ULong.MAX_VALUE) { "Noise nonce exhausted" }
        val reserved = nonce++
        return RelayCrypto.chacha(key, reserved, ad, data, true)
    }
    fun decrypt(ad: ByteArray, data: ByteArray): ByteArray {
        check(nonce != ULong.MAX_VALUE) { "Noise nonce exhausted" }
        val plain = RelayCrypto.chacha(key, nonce, ad, data, false)
        nonce++ // Authentication failure must not advance a receive counter.
        return plain
    }
    fun clear() { key.fill(0); nonce = ULong.MAX_VALUE }
}

/** Noise NK initiator, pinned to the daemon static key supplied by the pairing link. */
internal class NoiseInitiator(private val remote: ByteArray, prologue: ByteArray,
    private val ephemeral: ByteArray = RelayCrypto.randomBytes(32)) {
    private var h = "Noise_NK_25519_ChaChaPoly_SHA256".toByteArray().let { if (it.size <= 32) it.copyOf(32) else RelayCrypto.hash(it) }
    private var ck = h.copyOf()
    private var cipher: NoiseCipher? = null
    private var phase = 0
    init { mixHash(prologue); mixHash(remote) }
    private fun mixHash(data: ByteArray) { h = RelayCrypto.hash(h + data) }
    private fun mixKey(data: ByteArray) {
        val keys = RelayCrypto.hkdf(ck, data)
        ck.fill(0); ck = keys.first; cipher?.clear(); cipher = NoiseCipher(keys.second); data.fill(0)
    }
    fun first(payload: ByteArray): ByteArray {
        check(phase == 0); phase = 1
        val public = RelayCrypto.publicKey(ephemeral)
        mixHash(public); mixKey(RelayCrypto.dh(ephemeral, remote))
        val encrypted = cipher!!.encrypt(h, payload)
        mixHash(encrypted)
        return public + encrypted
    }
    fun second(message: ByteArray): ByteArray {
        check(phase == 1); require(message.size in 48..65535) { "Noise handshake truncated" }
        val public = message.copyOfRange(0, 32)
        mixHash(public); mixKey(RelayCrypto.dh(ephemeral, public))
        val encrypted = message.copyOfRange(32, message.size)
        val payload = cipher!!.decrypt(h, encrypted)
        mixHash(encrypted); phase = 2
        return payload
    }
    fun split(): Pair<NoiseCipher, NoiseCipher> {
        check(phase == 2); phase = 3
        val keys = RelayCrypto.hkdf(ck, byteArrayOf())
        close()
        return NoiseCipher(keys.first) to NoiseCipher(keys.second)
    }
    fun close() { ephemeral.fill(0); ck.fill(0); cipher?.clear(); cipher = null }
}
