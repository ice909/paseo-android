package sh.paseo.android.core

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun normalizesDirectAddressesAndProxyPrefixes() {
        assertEquals("ws://192.168.1.2:6767/ws", Endpoint.parse("192.168.1.2:6767").url)
        assertEquals("wss://dev.example.com/paseo/ws", Endpoint.parse("https://dev.example.com/paseo/").url)
        assertEquals("ws://[::1]:6767/ws", Endpoint.parse("ws://[::1]:6767/ws").url)
        assertEquals("wss://dev.example.com/ws?token=abc", Endpoint.parse("wss://dev.example.com/ws?token=abc").url)
    }
    @Test fun extractsPasswordsWithoutKeepingThemInUrls() {
        val endpoint = Endpoint.parse("tcp://dev.example.com:6767?ssl=true&password=a%20b%2B%26")
        assertEquals("wss://dev.example.com:6767/ws", endpoint.url)
        assertEquals("a b+&", endpoint.password)
        val pasted = Endpoint.parse("https://paseo.sh/#connect=tcp%3A%2F%2F192.168.1.2%3A6767%3Fpassword%3Dhello")
        assertEquals("ws://192.168.1.2:6767/ws", pasted.url)
        assertEquals("hello", pasted.password)
    }
    @Test fun rejectsUnsupportedOrMalformedLinks() {
        listOf("", "relay://host:443/server?key=abc", "ftp://host", "ws://me:secret@host", "ws://host:99999", "not a host").forEach {
            assertTrue("Should reject $it", runCatching { Endpoint.parse(it) }.isFailure)
        }
    }
}
