package sh.paseo.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.paseo.android.core.Host

class LocalNetworkAccessTest {
    private fun host(address: String) = Host(name = "test", url = "ws://$address:6767/ws")

    @Test fun privateAndLinkLocalHostsNeedLanAccess() = runBlocking {
        for (address in listOf("10.0.2.2", "172.16.0.1", "192.168.1.10", "169.254.1.1", "100.64.0.1", "100.127.255.254", "[fd00::1]", "[fe80::1]")) {
            assertTrue(address, LocalNetworkAccess.isLocalHost(host(address)))
        }
    }
    @Test fun publicAndLoopbackHostsDoNotNeedLanAccess() = runBlocking {
        for (address in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "127.0.0.1", "localhost", "[::1]", "[2606:4700:4700::1111]")) {
            assertFalse(address, LocalNetworkAccess.isLocalHost(host(address)))
        }
    }
    @Test fun localNamesCanRequestPermissionBeforeMdnsResolution() = runBlocking {
        for (name in listOf("devbox", "devbox.local", "devbox.local.")) {
            assertTrue(name, LocalNetworkAccess.isLocalHost(host(name)))
        }
    }
}
