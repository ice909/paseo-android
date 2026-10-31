package sh.paseo.android.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolPresentationTest {
    private fun tool(id: String, type: String = "shell", turn: String = "one", status: String = "completed") = TimelineRow(id, "tool_call", title = "exec_command", turnId = turn, status = status,
        detail = jsonObject("type" to type, "command" to "git status", "filePath" to "/repo/Main.kt"))

    @Test fun consecutiveCallsGroupButMessagesPlansAndTurnBoundariesSeparateThem() {
        val rows = listOf(tool("a"), tool("b"), TimelineRow("message", "assistant_message", "Check"), tool("c"), tool("d", turn = "two"), tool("p", "plan"), tool("e"))
        val entries = groupToolRows(rows)
        assertEquals(listOf("a", "message", "c", "d", "p", "e"), entries.map { it.key })
        assertEquals(listOf("a", "b"), entries.first().calls.map { it.key })
        assertEquals(rows.size, groupToolRows(rows, false).size)
        assertEquals(2, groupToolRows(listOf(tool("a"), tool("h").copy(title = "heartbeat"))).size)
    }
    @Test fun completedTurnCollapseDoesNotAggregateItsIndividualToolRows() {
        val rows = listOf(
            TimelineRow("u", "user_message", turnId = "one"),
            tool("a", turn = "one"),
            tool("b", turn = "one"),
            tool("c", turn = "one"),
            TimelineRow("reply", "assistant_message", "Done", turnId = "one"),
        )
        val activeTurn = conversationPresentation(rows, running = true, expanded = emptySet(), collapse = true)
        val activeEntries = groupToolRows(activeTurn, enabled = false)
        assertEquals(rows.map { it.key }, activeEntries.map { it.key })
        assertTrue(activeEntries.none { it.grouped })
        val turnCollapsed = conversationPresentation(rows, running = false, expanded = emptySet(), collapse = true)
        val collapsedEntries = groupToolRows(turnCollapsed, enabled = false)
        assertEquals(listOf("u", "summary:u", "reply"), collapsedEntries.map { it.key })
        val expandedTurn = conversationPresentation(rows, running = false, expanded = setOf("u"), collapse = true)
        val expandedEntries = groupToolRows(expandedTurn, enabled = false)
        assertEquals(listOf("u", "summary:u", "a", "b", "c", "reply"), expandedEntries.map { it.key })
        assertTrue(expandedEntries.none { it.grouped })
    }
    @Test fun conversationDisplayModesAreIndependent() {
        val rows = listOf(
            TimelineRow("u", "user_message", turnId = "one"),
            tool("a", turn = "one"),
            tool("b", turn = "one"),
            TimelineRow("reply", "assistant_message", "Done", turnId = "one"),
        )
        val full = groupToolRows(
            conversationPresentation(rows, running = false, expanded = emptySet(), collapse = false),
            enabled = false,
        )
        assertEquals(rows.map { it.key }, full.map { it.key })
        assertTrue(full.none { it.grouped })

        val callsCollapsed = groupToolRows(
            conversationPresentation(rows, running = false, expanded = emptySet(), collapse = false),
            enabled = true,
        )
        assertEquals(listOf("u", "a", "reply"), callsCollapsed.map { it.key })
        assertEquals(listOf("a", "b"), callsCollapsed[1].calls.map { it.key })
    }
    @Test fun expandedTurnsRevealIndividualCallsWithoutRegrouping() {
        val rows = listOf(TimelineRow("u", "user_message", turnId = "one"), tool("a"), tool("b"), TimelineRow("reply", "assistant_message", "Done", turnId = "one"))
        val nested = expandedTurnWorkKeys(rows, setOf("u"))
        assertEquals(setOf("a", "b"), nested)
        val presented = conversationPresentation(rows, false, setOf("u"))
        assertTrue(groupToolRows(presented, ungroupedKeys = nested).none { it.grouped })
    }
    @Test fun summaryCountsUniqueFilesAndDoesNotCountReasoningAsAnAction() {
        val label = toolSummary(listOf(tool("e", "edit"), tool("e2", "edit"), tool("r", "read"), tool("r2", "read"), tool("s"), TimelineRow("t", "reasoning", "Thinking")))
        assertEquals("编辑了 1 个文件, 运行了 1 个命令, 并 读取了 1 个文件", label)
        assertEquals("已思考", toolSummary(listOf(TimelineRow("t", "reasoning"))))
    }
    @Test fun displayShowsCanonicalNameCommandRelativePathAndRealFailure() {
        val shell = toolDisplay(tool("a"), "/repo")
        assertEquals("Shell", shell.name)
        assertEquals("git status", shell.summary)
        assertTrue(shell.canOpen)
        assertEquals("Main.kt", toolDisplay(tool("r", "read"), "/repo").summary)
        assertEquals("denied", toolDisplay(tool("e", status = "failed").copy(error = JsonPrimitive("denied")), "").error)
        assertFalse(toolDisplay(tool("w", "worktree_setup"), "").canOpen)
        val pending = tool("pending", "unknown", status = "executing").copy(detail = jsonObject("type" to "unknown"))
        assertTrue(toolDisplay(pending, "").loadingDetails)
        assertFalse(toolDisplay(pending.copy(status = "completed"), "").canOpen)
    }
    @Test fun hiddenPlansAndStableFirstCallIdentitySurviveLiveUpdates() {
        val running = tool("first", status = "running")
        assertEquals("first", groupToolRows(listOf(running)).single().key)
        val group = groupToolRows(listOf(running.copy(status = "completed"), tool("next", status = "executing"))).single()
        assertEquals("first", group.key)
        assertTrue(group.calls.any { it.toolRunning() })
        assertTrue(groupToolRows(listOf(tool("exit").copy(title = "ExitPlanMode"), tool("plan", status = "running").copy(title = "plan_approval"))).isEmpty())
    }
    @Test fun canonicalTurnIdsPreventSteeringFromSplittingAnActiveTurn() {
        val user = TimelineRow("u", "user_message", turnId = "one")
        val steer = TimelineRow("s", "user_message", turnId = "one")
        assertTrue(continuesTimelineTurn(user, steer))
        assertFalse(continuesTimelineTurn(tool("a"), tool("b", turn = "two")))
        assertFalse(continuesTimelineTurn(user.copy(turnId = ""), steer))
    }
    @Test fun reopeningKeepsStartAndStaleCompletionDoesNotEndTheNewTurn() {
        val start = jsonObject("type" to "turn_started", "turnId" to "new")
        val running = Agent("a").applyTurnLifecycle(start, "2026-10-31T00:00:00Z")
        assertEquals(turnStartedAt(running), turnStartedAt(running.applyTurnLifecycle(start, "2026-10-31T01:00:00Z")))
        assertEquals(running, running.applyTurnLifecycle(jsonObject("type" to "turn_completed", "turnId" to "old"), ""))
        val done = running.applyTurnLifecycle(jsonObject("type" to "turn_canceled", "turnId" to "new"), "")
        assertFalse(done.running)
        assertNull(turnStartedAt(done))
    }
    @Test fun durationUsesServerStartAndClampsFutureDates() {
        val agent = Agent("a", activeTurn = jsonObject("startedAt" to "2026-10-31T00:00:00Z"))
        assertEquals(1793404800000L, turnStartedAt(agent))
        assertNull(turnStartedAt(Agent("b")))
        assertEquals("0s", formatTurnDuration(-100))
        assertEquals("59s", formatTurnDuration(59_999))
        assertEquals("1m", formatTurnDuration(60_000))
        assertEquals("1m 2s", formatTurnDuration(62_000))
        assertEquals("1h 2m", formatTurnDuration(3_720_000))
    }
}
