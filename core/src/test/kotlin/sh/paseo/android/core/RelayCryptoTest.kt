package sh.paseo.android.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelayCryptoTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun initiatorMatchesOfficialNoiseNkVectorsByteForByte() {
        val text = javaClass.getResourceAsStream("/noise-nk-vectors.json")!!.bufferedReader().use { it.readText() }
        val vectors = wireJson.parseToJsonElement(text).jsonObject.array("vectors")
        for (entry in vectors) {
            val v = entry.jsonObject
            val n = NoiseInitiator(bytes(v.string("init_remote_static")), bytes(v.string("init_prologue")), bytes(v.string("init_ephemeral")))
            val messages = v.array("messages").map { it.jsonObject }
            assertArrayEquals(bytes(messages[0].string("ciphertext")), n.first(bytes(messages[0].string("payload"))))
            assertArrayEquals(bytes(messages[1].string("payload")), n.second(bytes(messages[1].string("ciphertext"))))
            val (send, receive) = n.split()
            messages.drop(2).forEachIndexed { index, message ->
                if (index % 2 == 0) assertArrayEquals(bytes(message.string("ciphertext")), send.encrypt(byteArrayOf(),bytes(message.string("payload"))))
                else assertArrayEquals(bytes(message.string("payload")), receive.decrypt(byteArrayOf(),bytes(message.string("ciphertext"))))
            }
        }
    }
    @Test fun recordsRoundTripLargeMessagesAndRejectReplayTamperingAndTruncation() {
        val key = ByteArray(32) { it.toByte() }
        val message = ByteArray(200_000) { (it % 127).toByte() }
        val record = ProtectedRecord.encode(message, NoiseCipher(key.copyOf()))
        val receiver = NoiseCipher(key.copyOf())
        assertArrayEquals(message, ProtectedRecord.decode(record, receiver).data)
        assertTrue(runCatching { ProtectedRecord.decode(record, receiver) }.isFailure)
        val tampered = record.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { ProtectedRecord.decode(tampered, NoiseCipher(key.copyOf())) }.isFailure)
        assertTrue(runCatching { ProtectedRecord.decode(record.copyOf(record.size - 1), NoiseCipher(key.copyOf())) }.isFailure)
        assertArrayEquals(byteArrayOf(), ProtectedRecord.decode(ProtectedRecord.encode(byteArrayOf(), NoiseCipher(key.copyOf())), NoiseCipher(key.copyOf())).data)
    }
    @Test fun binaryProtectedRecordsKeepTheKindAndBytes() {
        val key = ByteArray(32) { 42 }
        val bytes = ByteArray(131072) { (it % 251).toByte() }
        val frame = ProtectedRecord.decode(ProtectedRecord.encode(bytes, NoiseCipher(key.copyOf()), binary = true), NoiseCipher(key.copyOf()))
        assertTrue(frame.binary)
        assertArrayEquals(bytes, frame.data)
    }
    @Test fun legacyNaClBoxSupportsAuthenticatedRoundTrips() {
        val alice = ByteArray(32) { (it + 1).toByte() }; val bob = ByteArray(32) { (it + 80).toByte() }
        val a = RelayCrypto.boxKey(alice, RelayCrypto.publicKey(bob)); val b = RelayCrypto.boxKey(bob, RelayCrypto.publicKey(alice))
        assertArrayEquals(a,b)
        val encrypted = RelayCrypto.boxEncrypt(a, "你好 Paseo".toByteArray())
        assertEquals("你好 Paseo", strictUtf8(RelayCrypto.boxDecrypt(b, encrypted)))
        assertTrue(runCatching { RelayCrypto.boxDecrypt(b, encrypted.copyOf().also { it[it.size-1]=(it.last().toInt() xor 1).toByte() }) }.isFailure)
    }
    @Test fun legacyBoxMatchesTheOfficialTweetNaClImplementation() {
        val v=wireJson.parseToJsonElement(javaClass.getResourceAsStream("/nacl-relay-vector.json")!!.bufferedReader().use { it.readText() }).jsonObject
        val key=RelayCrypto.boxKey(bytes(v.string("alice")),bytes(v.string("bobPublic")))
        assertArrayEquals(bytes(v.string("key")),key)
        assertArrayEquals(bytes(v.string("bundle")),RelayCrypto.boxEncrypt(key,bytes(v.string("plaintext")),bytes(v.string("nonce"))))
        assertArrayEquals(bytes(v.string("plaintext")),RelayCrypto.boxDecrypt(key,bytes(v.string("bundle"))))
    }
    @Test fun protectedLinksRefuseLegacyReadyAndMalformedProtectedNeverFallsBack() {
        val offer = RelayOffer("s", b64(RelayCrypto.publicKey(ByteArray(32) { 9 })), "localhost:6770", false, "protected")
        val channel = RelayChannel(offer)
        assertTrue(runCatching { channel.receive("{\"type\":\"e2ee_ready\"}") }.isFailure)
        val compatible = RelayChannel(offer.copy(minimumSecurityMode=null))
        assertTrue(runCatching { compatible.receive("{\"type\":\"e2ee_ready\",\"protected\":null}") }.isFailure)
        val legacy = RelayChannel(offer.copy(minimumSecurityMode=null))
        assertEquals(RelayResult.Ready, legacy.receive("{\"type\":\"e2ee_ready\"}"))
        assertTrue(runCatching { legacy.receive("{\"type\":\"session\"}") }.isFailure)
    }
}
