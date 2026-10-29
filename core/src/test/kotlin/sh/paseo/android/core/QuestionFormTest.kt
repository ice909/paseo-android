package sh.paseo.android.core

import org.junit.Assert.*
import org.junit.Test

class QuestionFormTest {
    @Test fun answersUseHeadersAndPreserveTheOriginalInput() {
        val request = PermissionRequest("q1", input = jsonObject("context" to "keep", "questions" to listOf(jsonObject("header" to "stack", "question" to "Which?", "options" to listOf(jsonObject("label" to "Kotlin"), jsonObject("label" to "Java")), "multiSelect" to true, "allowOther" to true))))
        val q = request.questions().single()
        assertFalse(q.answered(emptySet(), " "))
        assertTrue(q.answered(setOf(0), ""))
        val input = request.answeredInput(mapOf(q.header to q.answer(setOf(0), " Rust ")))
        assertEquals("keep", input.string("context"))
        assertEquals("Kotlin, Rust", input.obj("answers").string("stack"))
        assertEquals("Rust", q.copy(multiSelect = false).answer(setOf(0), "Rust"))
    }
    @Test fun emptyAnswersAreOnlyAllowedForTextQuestions() {
        val q = Question("note", "Note?", emptyList(), false, false, true, "")
        assertTrue(q.answered(emptySet(), ""))
        assertEquals("", q.answer(emptySet(), ""))
        assertFalse(q.copy(options = listOf(QuestionOption("A", "")), allowOther = false).answered(emptySet(), ""))
    }
}
