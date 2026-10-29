package sh.paseo.android.core

import org.junit.Assert.*
import org.junit.Test

class ConversationPresentationTest {
    private val rows = listOf(TimelineRow("u", "user_message", "Task"), TimelineRow("r", "reasoning", "Think"),
        TimelineRow("t", "tool_call", title = "shell"), TimelineRow("a1", "assistant_message", "Working"),
        TimelineRow("a2", "assistant_message", "Done"))
    @Test fun completedTurnKeepsFinalAnswerAndCanRevealAllWork() {
        val collapsed = conversationPresentation(rows, false, emptySet())
        assertEquals(listOf("u", "summary:u", "a2"), collapsed.map { it.key })
        assertEquals(rows.map { it.key }, conversationPresentation(rows, true, emptySet()).map { it.key })
        assertEquals(listOf("u", "summary:u", "r", "t", "a1", "a2"), conversationPresentation(rows, false, setOf("u")).map { it.key })
        assertEquals(rows, conversationPresentation(rows, false, emptySet(), collapse = false))
    }
    @Test fun reasoningAloneDoesNotCreateEmptySummaryAndEarlierTurnCanCollapseWhileLatestRuns() {
        val simple = listOf(rows[0], rows[1], rows.last())
        assertEquals(simple, conversationPresentation(simple, false, emptySet()))
        val next = TimelineRow("u2", "user_message", "Next")
        assertEquals(listOf("u", "summary:u", "a2", "u2"), conversationPresentation(rows + next, true, emptySet()).map { it.key })
    }
    @Test fun forgeAttachmentsFollowServerVersionAndKeepReviewContext() {
        val issue = jsonObject("kind" to "issue", "number" to 42, "title" to "Fix", "url" to "https://example.test/issues/42", "body" to "Details", "projectPath" to "team/project", "forge" to "gitlab")
        assertEquals("forge_issue", forgeAttachment(issue, true).payload!!.string("type"))
        assertEquals("gitlab", forgeAttachment(issue, true).payload!!.string("forge"))
        val legacy = forgeAttachment(issue, false).payload!!
        assertEquals("github_issue", legacy.string("type"))
        assertFalse(legacy.containsKey("forge"))
        assertEquals("Details", legacy.string("body"))
        val change = jsonObject("kind" to "change_request", "number" to 8, "title" to "PR", "url" to "https://example.test/8", "baseRefName" to "main", "headRefName" to "fix")
        assertEquals("github_pr", forgeAttachment(change, false).payload!!.string("type"))
        assertEquals("main", forgeAttachment(change, true).payload!!.string("baseRefName"))
    }
}
