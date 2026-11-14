package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class HostConnectionsTest {
    private val relay = RelayOffer("server", "key", "relay.example:443", true)
    private fun direct(id: String = "direct") = Host(id, "Computer", "ws://computer:6767/ws", serverId = "server")

    @Test fun directAndRelayMergeInEitherOrderWithoutChangingSavedIdentity() {
        val direct = direct().copy(color = "sky", badgeDisplay = "icon")
        val remote = Host("remote", "Server", "wss://relay.example/ws", relay = relay)
        for ((first, second) in listOf(direct to remote, remote to direct)) {
            val (hosts, merged) = mergeVerifiedHost(listOf(first), second)
            assertEquals(1, hosts.size)
            assertEquals(first.id, merged.id)
            assertEquals(first.name, merged.name)
            assertEquals(first.color, merged.color)
            assertEquals(first.badgeDisplay, merged.badgeDisplay)
            assertEquals("server", merged.serverId)
            assertEquals(setOf(direct.url, remote.url), merged.savedConnections().map { it.url }.toSet())
            val (again, _) = mergeVerifiedHost(hosts, second)
            assertEquals(hosts, again)
        }
    }

    @Test fun distinctDirectAddressesAreRetainedAndDifferentServersStaySeparate() {
        val first = direct()
        val second = direct("vpn").copy(url = "ws://vpn:6767/ws")
        val (hosts, merged) = mergeVerifiedHost(listOf(first), second)
        assertEquals(2, merged.savedConnections().size)
        val (separate, _) = mergeVerifiedHost(hosts, second.copy(id = "other", url = "ws://other/ws", serverId = "other"))
        assertEquals(2, separate.size)
    }

    @Test fun legacyFallbackMigratesWithoutDroppingEitherConnectionOrCredentials() {
        val legacy = Host("saved", "Computer", "wss://relay.example/ws", "secret", relay, directUrl = "ws://lan/ws")
        val encoded = wireJson.encodeToString(legacy)
        val migrated = wireJson.decodeFromString<Host>(encoded).normalized()
        assertNull(migrated.directUrl)
        assertEquals("saved", migrated.id)
        assertEquals("secret", migrated.password)
        assertEquals("server", migrated.serverId)
        assertEquals(2, migrated.connections.size)
        assertEquals(migrated, wireJson.decodeFromString<Host>(wireJson.encodeToString(migrated)))
    }

    @Test fun anEndpointCannotSilentlyChangeServerIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            mergeVerifiedHost(listOf(direct()), direct().copy(serverId = "impostor"))
        }
        assertThrows(AuthenticationException::class.java) { verifyHostIdentity(direct(), "impostor") }
    }

    @Test fun updatingRelayOfferPreservesProtectedSecurityPin() {
        val original = Host("saved", "Computer", "wss://relay.example/ws", relay = relay.copy(minimumSecurityMode = "protected"))
        val (_, updated) = mergeVerifiedHost(listOf(original), original.copy(relay = relay))
        assertEquals("protected", updated.savedConnections().single().relay?.minimumSecurityMode)
    }

    @Test fun switchingRequiresThreeConsecutiveSignificantImprovements() {
        val policy = ConnectionSwitchPolicy()
        val faster = mapOf("active" to 100L, "other" to 50L)
        assertNull(policy.next("active", faster))
        assertNull(policy.next("active", faster))
        assertNull(policy.next("active", mapOf("active" to 60L, "other" to 50L)))
        assertNull(policy.next("active", faster))
        assertNull(policy.next("active", faster))
        assertEquals("other", policy.next("active", faster))
        assertEquals("other", ConnectionSwitchPolicy().next("active", mapOf("other" to 20L)))
    }

    @Test fun probeCadenceMatchesOfficialActiveInactiveAndOfflineIntervals() {
        assertEquals(10_000L, connectionProbeInterval(0, true, true))
        assertEquals(120_000L, connectionProbeInterval(0, false, true))
        assertEquals(2_000L, connectionProbeInterval(0, false, false))
        assertEquals(5_000L, connectionProbeInterval(10_000, false, false))
        assertEquals(10_000L, connectionProbeInterval(30_000, false, false))
        assertEquals(30_000L, connectionProbeInterval(60_000, false, false))
    }

    @Test fun removingInactiveConnectionDoesNotReuseItOnReconnect() = runBlocking {
        fun fixture(peers: MutableList<WebSocket>) = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(peer: WebSocket, text: String) {
                        val frame = wireJson.parseToJsonElement(text).jsonObject
                        val payload = if (frame.string("type") == "hello") {
                            peers.add(peer)
                            jsonObject("status" to "server_info", "serverId" to "server")
                        } else jsonObject("requestId" to frame.obj("message").string("requestId"))
                        peer.send(DaemonSocketTest.session("status", payload))
                    }
                })
            }
        }
        val firstPeers = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val secondPeers = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        fixture(firstPeers).use { first -> fixture(secondPeers).use { second ->
            val firstUrl = first.url("/ws").toString()
            val secondUrl = second.url("/ws").toString()
            val host = direct().copy(connections = listOf(HostConnection(firstUrl), HostConnection(secondUrl)))
            val repository = PaseoRepository()
            try {
                repository.connect(host)
                val ready = withTimeout(5_000) { repository.state.first { it.connected && it.catalogLoaded } }
                delay(100)
                val active = ready.activeConnectionUrl!!
                val activeServer = if (active == firstUrl) first else second
                val inactiveServer = if (active == firstUrl) second else first
                val activePeers = if (active == firstUrl) firstPeers else secondPeers
                val activeBefore = activeServer.requestCount
                val inactiveBefore = inactiveServer.requestCount
                repository.updateHostProfile(host.copy(connections = listOf(HostConnection(active))))
                assertTrue(repository.state.value.connected)
                activePeers.forEach { it.close(1000, "test reconnect") }
                withTimeout(5_000) { repository.state.first { it.connection == ConnectionStatus.Reconnecting } }
                withTimeout(5_000) { repository.state.first { it.connected && activeServer.requestCount > activeBefore } }
                delay(200)
                assertEquals(active, repository.state.value.activeConnectionUrl)
                assertEquals(inactiveBefore, inactiveServer.requestCount)
            } finally { repository.close() }
        } }
    }

    @Test fun anUnresponsiveRouteDoesNotDelayAnotherAvailableConnection() = runBlocking {
        MockWebServer().use { slow -> MockWebServer().use { fast ->
            slow.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            fast.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = wireJson.parseToJsonElement(text).jsonObject
                    val payload = if (frame.string("type") == "hello") jsonObject("status" to "server_info", "serverId" to "server")
                        else jsonObject("requestId" to frame.obj("message").string("requestId"), "answer" to "retained")
                    webSocket.send(DaemonSocketTest.session("status", payload))
                }
            }))
            val fastUrl = fast.url("/ws").toString()
            val host = direct().copy(connections = listOf(HostConnection(slow.url("/ws").toString()), HostConnection(fastUrl)))
            val previous = DaemonSocket()
            try {
                val chosen = withTimeout(3_000) { openHostConnection(host, previous) { _, _ -> } }
                try {
                    assertEquals(fastUrl, chosen.host.url)
                    assertTrue(chosen.socket.connected.value)
                    assertEquals("retained", chosen.socket.request("fixture.request").string("answer"))
                    assertEquals(1, fast.requestCount)
                } finally { chosen.socket.close() }
            } finally { previous.close() }
        } }
    }
}
