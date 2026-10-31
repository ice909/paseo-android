package sh.paseo.android.core

import org.junit.Assert.*
import org.junit.Test

class AssistantFooterTest {
    private fun row(key: String, type: String, text: String = key, turn: String = "one", seconds: Int = 0) =
        TimelineRow(key, type, text, turnId = turn, timestamp = "2026-10-31T10:00:${seconds.toString().padStart(2, '0')}Z")
    private val rows = listOf(row("user", "user_message"), row("intro", "assistant_message", "**First**", seconds = 1),
        row("thought", "reasoning", "private reasoning", seconds = 2), row("tool", "tool_call", "tool output", seconds = 3),
        row("final", "assistant_message", "Last answer", seconds = 8), row("tail", "tool_call", seconds = 10))

    @Test fun completedResponseHasOneFooterAfterTrailingToolsAndCopiesOnlyAssistantMarkdown() {
        val footers = completedAssistantFooters(rows, false)
        assertEquals(setOf("tail"), footers.keys)
        assertEquals("**First**\n\nLast answer", footers["tail"]?.content)
        assertEquals(10000L, footers["tail"]?.durationMs)
    }
    @Test fun liveResponseHasNoCopyButOlderCompletedResponsesKeepTheirs() {
        assertTrue(completedAssistantFooters(rows, true).isEmpty())
        val next = rows + listOf(row("u2", "user_message", turn = "two"), row("a2", "assistant_message", turn = "two"))
        assertEquals(setOf("tail"), completedAssistantFooters(next, true).keys)
        assertEquals(setOf("tail", "a2"), completedAssistantFooters(next, false).keys)
    }
    @Test fun canonicalSteeringDoesNotSplitCopyOrIncludeUserText() {
        val steered = rows.take(2) + row("steer", "user_message", "different request", seconds = 3) + rows.drop(2)
        assertEquals("**First**\n\nLast answer", completedAssistantFooters(steered, false)["tail"]?.content)
    }
    @Test fun legacyPromptsSeparateResponsesAndSystemTurnsCannotLeakPreviousText() {
        val legacy = rows.map { it.copy(turnId = "") } + row("u2", "user_message", turn = "") + row("a2", "assistant_message", turn = "")
        assertEquals("a2", completedAssistantFooters(legacy, false)["a2"]?.content)
        val system = listOf(row("old", "assistant_message", turn = "old"), row("system", "tool_call", turn = "new"), row("new", "assistant_message", turn = "new"))
        assertEquals("new", completedAssistantFooters(system, false)["new"]?.content)
        assertNull(completedAssistantFooters(system, false)["new"]?.durationMs)
    }
    @Test fun toolOnlyTurnHasTimingWithoutCopyAndPromptOnlyHasNoFooter() {
        assertNull(completedAssistantFooters(listOf(rows[0], rows[3]), false)["tool"]?.content)
        assertTrue(completedAssistantFooters(listOf(rows[0]), false).isEmpty())
        assertTrue(completedAssistantFooters(emptyList(), false).isEmpty())
    }
    @Test fun collapsedAndExpandedWorkDoNotChangeResponseContentOrDuplicateFooter() {
        val footer = completedAssistantFooters(rows, false)
        listOf(emptySet(), setOf("user")).forEach { expanded ->
            val rendered = groupToolRows(conversationPresentation(rows, false, expanded))
            assertEquals(1, rendered.count { (it.calls.lastOrNull()?.key ?: it.key) in footer })
        }
    }
    @Test fun partialHistoryDoesNotInventDurationAndBadTimestampsAreSafe() {
        assertNull(completedAssistantFooters(rows.drop(1), false)["tail"]?.durationMs)
        assertNull(completedAssistantFooters(rows.map { it.copy(timestamp = "bad") }, false)["tail"]?.completedAt)
        assertEquals(0L, completedAssistantFooters(listOf(rows[0].copy(timestamp = rows.last().timestamp), rows[1]), false)["intro"]?.durationMs)
    }
}
