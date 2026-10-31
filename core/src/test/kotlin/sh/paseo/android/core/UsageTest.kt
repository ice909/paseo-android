package sh.paseo.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class UsageTest {
    private fun report(id: String, percent: Int) = jsonObject("id" to id, "sourceId" to "codex", "sourceLabel" to "Codex", "icon" to "codex",
        "report" to jsonObject("status" to "available", "planLabel" to "Plus", "windows" to listOf(
            jsonObject("id" to "weekly", "label" to "Weekly", "shortLabel" to "wk", "summary" to true, "usedPct" to percent))))
    @Test fun normalizesLegacyAndMissingPercentWithoutInventingQuota() {
        val legacy = parseLegacyUsage(jsonObject("providerId" to "codex", "displayName" to "Codex", "status" to "available", "windows" to listOf(
            jsonObject("id" to "weekly", "label" to "Weekly", "remainingPct" to 24),
            jsonObject("id" to "unknown", "label" to "Unknown", "shortLabel" to ""),
            jsonObject("id" to "over", "usedPct" to 120))))
        assertEquals(76.0, legacy.windows[0].percent()!!, .001)
        assertEquals(24.0, legacy.windows[0].percent(true)!!, .001)
        assertNull(legacy.windows[1].percent())
        assertEquals("", legacy.windows[1].shortLabel)
        assertEquals(100.0, legacy.windows[2].percent()!!, .001)
        assertEquals("weekly", legacy.summaryWindows.single().id)
        val unavailable = parseUsageEntry(jsonObject("id" to "codex", "report" to jsonObject("status" to "unavailable", "problem" to jsonObject("kind" to "not_authenticated"))))
        assertTrue(unavailable.summaryWindows.isEmpty())
        assertEquals("not_authenticated", unavailable.problem)
    }
    @Test fun usesSummaryWindowAndDoesNotPresentLastSuccessfulAsLive() {
        val available = report("a", 76)
        val parsed = parseUsageEntry(available)
        assertEquals("wk", parsed.summaryWindows.single().shortLabel)
        val failed = JsonObject(available + ("report" to jsonObject("status" to "error", "error" to "Expired")) +
            ("lastSuccessful" to jsonObject("report" to available.obj("report"))))
        assertTrue(parseUsageEntry(failed).summaryWindows.isEmpty())
        assertEquals("Expired", parseUsageEntry(failed).problem)
    }
    private fun server(modern: Boolean, requests: MutableList<JsonObject>, failFinal: Boolean = false): MockWebServer = MockWebServer().apply {
        enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = wireJson.parseToJsonElement(text).jsonObject
                fun send(type: String, payload: JsonObject) { webSocket.send(DaemonSocketTest.session(type, payload)) }
                if (frame.string("type") == "hello") {
                    send("status", jsonObject("status" to "server_info", "serverId" to "test", "features" to jsonObject(if (modern) "usageSources" to true else "providerUsageList" to true, "projectList" to modern)))
                    return
                }
                val message = frame.obj("message"); requests.add(message)
                val id = message.string("requestId")
                if (message.string("type") == "usage.list_reports.request") {
                    send("usage.list_reports.update", jsonObject("requestId" to id, "report" to report("personal", 10)))
                    send("usage.list_reports.update", jsonObject("requestId" to id, "report" to report("work", 22)))
                    send("usage.list_reports.update", jsonObject("requestId" to id, "report" to report("personal", 76)))
                    send("usage.list_reports.response", jsonObject("requestId" to id, "error" to if (failFinal) "Usage failed" else null))
                } else {
                    val result = when (message.string("type")) {
                        "project.list.request" -> jsonObject("projects" to listOf(jsonObject("projectId" to "p1", "projectKey" to "shared-project", "projectDisplayName" to "Project", "projectRootPath" to "/project")))
                        "provider.usage.list.request" -> jsonObject("providers" to listOf(jsonObject("providerId" to "codex", "displayName" to "Codex", "status" to "available", "windows" to listOf(jsonObject("id" to "weekly", "remainingPct" to 24)))))
                        "fetch_agent_history_request" -> jsonObject("entries" to listOf(jsonObject("agent" to jsonObject("id" to "archived", "archivedAt" to "2026-01-01", "title" to "History"))), "pageInfo" to jsonObject("nextCursor" to "next"))
                        "fetch_agent_timeline_request" -> jsonObject("epoch" to "e", "entries" to listOf(jsonObject("seqStart" to 1, "item" to jsonObject("type" to "assistant_message", "text" to "Archived answer"))))
                        else -> jsonObject()
                    }
                    send(message.string("type") + ".response", JsonObject(result + ("requestId" to JsonPrimitive(id))))
                }
            }
        }))
    }
    @Test fun waitsForFinalUsageResponseAndMergesAllAccountsAndUpdates() = runBlocking {
        val requests = CopyOnWriteArrayList<JsonObject>(); val server = server(true, requests); val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "test", url = server.url("/ws").toString()))
            withTimeout(5_000) { repository.state.first { it.connected } }
            val project = repository.sidebarProjects().single()
            assertEquals("shared-project", project.identityKey("test"))
            assertEquals(projectIdentityColor("shared-project"), projectIdentityColor(project.identityKey("another-host")))
            val reports = repository.usageReports(true)
            assertEquals(2, reports.size)
            assertEquals(76.0, reports.first { it.id == "personal" }.summaryWindows.single().usedPercent!!, .001)
            assertEquals(22.0, reports.first { it.id == "work" }.summaryWindows.single().usedPercent!!, .001)
            assertTrue(requests.first { it.string("type") == "usage.list_reports.request" }.bool("forceRefresh"))
        } finally { repository.close(); server.close() }
    }
    @Test fun finalStreamErrorDoesNotSilentlyReturnPartialQuota() = runBlocking {
        val server = server(true, CopyOnWriteArrayList(), true); val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "test", url = server.url("/ws").toString()))
            withTimeout(5_000) { repository.state.first { it.connected } }
            assertEquals("Usage failed", runCatching { repository.usageReports() }.exceptionOrNull()?.message)
        } finally { repository.close(); server.close() }
    }
    @Test fun supportsLegacyDaemonAndReadsSearchableArchivedHistory() = runBlocking {
        val requests = CopyOnWriteArrayList<JsonObject>(); val server = server(false, requests); val repository = PaseoRepository()
        try {
            repository.connect(Host(name = "test", url = server.url("/ws").toString()))
            withTimeout(5_000) { repository.state.first { it.connected } }
            assertTrue(repository.sidebarProjects().isEmpty())
            assertFalse(requests.any { it.string("type") == "project.list.request" })
            assertEquals(76.0, repository.usageReports().single().summaryWindows.single().usedPercent!!, .001)
            assertFalse(requests.any { it.string("type") == "usage.list_reports.request" })
            val history = repository.historyPage("History", "previous")
            assertEquals("archived", history.first.single().id)
            assertEquals("next", history.second)
            val request = requests.first { it.string("type") == "fetch_agent_history_request" }
            assertEquals("History", request.string("search"))
            assertEquals("previous", request.obj("page").string("cursor"))
            assertEquals("Archived answer", repository.historyTranscript("archived").single().text)
        } finally { repository.close(); server.close() }
    }
}
