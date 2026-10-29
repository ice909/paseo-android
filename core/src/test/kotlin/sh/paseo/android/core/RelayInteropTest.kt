package sh.paseo.android.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Enable with PASEO_RELAY_FIXTURE_FILE after starting tools/relay_pairing_fixture.mjs. */
class RelayInteropTest {
    private fun fixture(): kotlinx.serialization.json.JsonObject {
        val path=System.getenv("PASEO_RELAY_FIXTURE_FILE").orEmpty()
        assumeTrue("Start the official relay library fixture", path.isNotBlank())
        return wireJson.parseToJsonElement(File(path).readText()) as kotlinx.serialization.json.JsonObject
    }
    private fun host(mode: String, password: String=""): Host {
        val endpoint=Endpoint.parse(fixture().obj("links").string(mode))
        return Host(name="Fixture",url=endpoint.url,password=password,relay=endpoint.relay)
    }
    @Test fun protectedAndLegacyPeersExchangeEncryptedLargeMessages() = runBlocking {
        for(mode in listOf("protected","legacy")) {
            val socket=DaemonSocket()
            try {
                assertEquals("Relay fixture", socket.open(host(mode)).string("hostname"))
                val text="你好 Paseo\n".repeat(25_000)
                assertEquals(text,socket.request("echo_request","text" to text).string("text"))
                try { socket.request("echo_request", "text" to "x".repeat(25 * 1024 * 1024)); fail("Oversize send should fail") }
                catch (_: IllegalArgumentException) { /* No nonce may be consumed by a rejected send. */ }
                assertEquals("still usable", socket.request("echo_request", "text" to "still usable").string("text"))
            } finally { socket.close() }
        }
        val audit=fixture().obj("audit")
        assertEquals(0,audit.long("authorizationHeaders")!!.toInt())
        assertEquals(0,audit.long("plaintextApplications")!!.toInt())
    }
    @Test fun fileFramesCrossOfficialProtectedAndLegacyRelayChannels() = runBlocking {
        for (mode in listOf("protected", "legacy")) {
            val socket = DaemonSocket()
            try {
                socket.open(host(mode))
                val bytes = ByteArray(400_001) { (it % 251).toByte() }
                val file = bytes.inputStream().use { socket.uploadFile("relay.bin", "application/octet-stream", bytes.size.toLong(), it) }
                assertEquals(bytes.size.toLong(), file.long("size"))
                assertEquals("relay.bin", file.string("fileName"))
            } finally { socket.close() }
        }
    }
    @Test fun passwordsAreRequestedInsideTheChannelAndWrongKeysOrDowngradesFail() = runBlocking {
        for((password,expected) in listOf("" to AuthFailure.PasswordRequired,"wrong" to AuthFailure.IncorrectPassword)) {
            val socket=DaemonSocket()
            try { socket.open(host("password",password)); fail("Password should be rejected") }
            catch(e: AuthenticationException){ assertEquals(expected,e.reason) }
            finally{socket.close()}
        }
        val admitted=DaemonSocket()
        try { assertEquals("Relay fixture",admitted.open(host("password","test")).string("hostname")) } finally{admitted.close()}
        val downgraded=DaemonSocket()
        try { downgraded.open(host("downgrade")); fail("Should reject security downgrade") }
        catch(e: AuthenticationException){assertEquals(AuthFailure.Security,e.reason)} finally{downgraded.close()}
        val wrong=DaemonSocket(); val original=host("protected")
        val wrongHost=original.copy(relay=original.relay!!.copy(daemonPublicKeyB64=b64(RelayCrypto.publicKey(ByteArray(32){23}))))
        try { wrong.open(wrongHost);fail("Should reject wrong daemon key") }
        catch(_:Exception){assertFalse(wrong.connected.value)} finally{wrong.close()}
    }
}
