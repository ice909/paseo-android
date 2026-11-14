package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable

@Serializable
data class HostConnection(val url: String, val relay: RelayOffer? = null) {
    val id: String get() = url
    val label: String get() = if (relay == null) "直接连接" else "中继连接"
    val displayLabel: String get() = relay?.let { "中继 (${it.endpoint})" } ?: run {
        val endpoint = java.net.URI(url)
        "TCP (${endpoint.rawAuthority})"
    }
}

fun Host.savedConnections(): List<HostConnection> = (connections.ifEmpty {
    listOfNotNull(directUrl?.takeIf(String::isNotBlank)?.let { HostConnection(it) }, HostConnection(url, relay))
}).distinctBy { it.id }

fun Host.normalized(): Host = copy(serverId = serverId ?: relay?.serverId,
    connections = savedConnections(), directUrl = null)

/** Only verified server identities or identical endpoints may merge profiles. Keep the existing local ID. */
fun mergeVerifiedHost(hosts: List<Host>, incoming: Host): Pair<List<Host>, Host> {
    val verified = incoming.normalized()
    require(!verified.serverId.isNullOrBlank()) { "主机未返回 serverId" }
    val matches = hosts.filter { old -> old.id == verified.id ||
        (old.serverId ?: old.relay?.serverId) == verified.serverId ||
        old.savedConnections().any { route -> verified.savedConnections().any { it.id == route.id } } }
    require(matches.none { old -> (old.serverId ?: old.relay?.serverId)?.let { it != verified.serverId } == true }) {
        "连接地址已属于另一台主机，请先移除旧连接"
    }
    val previous = matches.firstOrNull { it.id == verified.id } ?: matches.firstOrNull()
    val routes = (matches.flatMap { it.savedConnections() } + verified.savedConnections())
        .groupBy { it.id }.map { (_, versions) ->
            val latest = versions.last()
            // A newly pasted offer must never downgrade an existing protected relay pin.
            if (versions.any { it.relay?.minimumSecurityMode == "protected" } && latest.relay != null)
                latest.copy(relay = latest.relay.copy(minimumSecurityMode = "protected")) else latest
        }
    val merged = verified.copy(id = previous?.id ?: verified.id,
        name = previous?.takeUnless { it.automaticName }?.name ?: verified.name,
        automaticName = previous?.automaticName ?: verified.automaticName, connections = routes,
        color = previous?.color ?: verified.color, badgeDisplay = previous?.badgeDisplay ?: verified.badgeDisplay)
    val result = hosts.filterNot { old -> matches.any { it.id == old.id } }.toMutableList()
    result.add((hosts.indexOfFirst { it.id == previous?.id }).coerceIn(0, result.size), merged)
    return result to merged
}

internal data class OpenedHostConnection(val host: Host, val info: kotlinx.serialization.json.JsonObject, val socket: DaemonSocket)

/** Keep the first authenticated client; cancelling losing probes must not close the winner. */
internal suspend fun openHostConnection(host: Host, currentSocket: DaemonSocket,
    onProbe: (String, Long?) -> Unit): OpenedHostConnection {
    val retained = java.util.concurrent.atomic.AtomicReference<DaemonSocket?>()
    return try {
        coroutineScope {
            val candidates = host.connectionCandidates()
            if (candidates.size == 1) {
                val candidate = candidates.single()
                val info = currentSocket.open(candidate)
                verifyHostIdentity(host, info.string("serverId"))
                return@coroutineScope OpenedHostConnection(candidate, info, currentSocket)
            }
            val results = Channel<Result<OpenedHostConnection>>(Channel.UNLIMITED)
            val jobs = candidates.map { candidate -> launch {
                val probe = DaemonSocket()
                try {
                    val start = System.nanoTime()
                    val info = probe.open(candidate, timeoutMs = 6_000)
                    verifyHostIdentity(host, info.string("serverId"))
                    onProbe(candidate.url, (System.nanoTime() - start) / 1_000_000)
                    results.send(Result.success(OpenedHostConnection(candidate, info, probe)))
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onProbe(candidate.url, null); results.send(Result.failure(error)) }
                finally { if (retained.get() !== probe) probe.close() }
            } }
            try {
                var failure: Throwable? = null
                repeat(candidates.size) {
                    val result = results.receive()
                    result.getOrNull()?.let { opened -> retained.set(opened.socket); return@coroutineScope opened }
                    failure = result.exceptionOrNull()
                }
                throw failure ?: IllegalStateException("没有可用的主机连接")
            } finally { jobs.forEach { it.cancel() }; results.close() }
        }
    } catch (cancelled: CancellationException) {
        retained.get()?.close()
        throw cancelled
    }
}

internal fun connectionProbeInterval(ageMs: Long, activeOnline: Boolean, hasActiveOnline: Boolean): Long = when {
    activeOnline -> 10_000
    hasActiveOnline -> 120_000
    ageMs < 10_000 -> 2_000
    ageMs < 30_000 -> 5_000
    ageMs < 60_000 -> 10_000
    else -> 30_000
}

internal fun verifyHostIdentity(host: Host, actual: String) {
    val expected = host.serverId ?: host.relay?.serverId ?: return
    if (actual != expected) throw AuthenticationException("连接到的不是已保存的主机，请检查连接地址", AuthFailure.Security)
}

/** Avoid bouncing between paths for small or transient latency changes. */
internal class ConnectionSwitchPolicy {
    private var candidate: String? = null
    private var hits = 0
    fun next(active: String, latencies: Map<String, Long>): String? {
        val fastest = latencies.minByOrNull { it.value }
        val current = latencies[active]
        if (fastest == null || fastest.key == active || (current != null && current - fastest.value < 40)) {
            candidate = null; hits = 0
            return null
        }
        hits = if (candidate == fastest.key) hits + 1 else 1
        candidate = fastest.key
        return fastest.key.takeIf { current == null || hits >= 3 }
    }
}
