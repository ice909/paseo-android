package sh.paseo.android.core

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private val key = b64(ByteArray(32) { (it + 1).toByte() })
    private fun link(offer: kotlinx.serialization.json.JsonObject): String = "https://app.paseo.sh/#offer=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(offer.toString().toByteArray())
    @Test fun officialV2OfferPinsKeyAndBuildsRoutingOnlyUrl() {
        val endpoint = Endpoint.parse(link(jsonObject("v" to 2, "serverId" to "server-id", "daemonPublicKeyB64" to key,
            "relay" to jsonObject("endpoint" to "relay.paseo.sh:443"), "minimumSecurityMode" to "protected")))
        assertEquals("wss://relay.paseo.sh:443/ws?serverId=server-id&role=client&v=2", endpoint.url)
        assertEquals("protected", endpoint.relay!!.minimumSecurityMode)
        assertEquals(key, endpoint.relay!!.daemonPublicKeyB64)
        assertFalse(endpoint.url.contains(key))
        assertEquals(endpoint, Endpoint.parse(endpoint.relay!!.pairingLink()))
    }
    @Test fun relayUrisAndEncodedConnectLinksKeepPasswordOutOfRouting() {
        val uri = "relay://localhost:6770/server-id?key=${java.net.URLEncoder.encode(key, "UTF-8")}&security=protected&password=a%20b"
        val endpoint = Endpoint.parse(uri)
        assertEquals("ws://localhost:6770/ws?serverId=server-id&role=client&v=2", endpoint.url)
        assertEquals("a b", endpoint.password)
        assertEquals(endpoint, Endpoint.parse("https://app.paseo.sh/#connect=" + java.net.URLEncoder.encode(uri,"UTF-8")))
    }
    @Test fun invalidOffersAndUnsupportedSecurityAreRejectedBeforeConnecting() {
        val valid = RelayOffer("server", key, "relay.paseo.sh:443", true)
        listOf("https://app.paseo.sh/#offer=", "https://app.paseo.sh/#offer=broken", valid.pairingLink().replace("#offer=", "#offer=%%%"),
            "relay://host:443/server?key=$key&security=legacy", "relay://host:443/server?key=${b64(ByteArray(32))}",
            link(jsonObject("v" to 1))).forEach { input -> assertTrue(runCatching { Endpoint.parse(input) }.isFailure) }
    }
}
