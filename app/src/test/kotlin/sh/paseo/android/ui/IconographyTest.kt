package sh.paseo.android.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class IconographyTest {
    private fun detail(type: String, icon: String? = null) = buildJsonObject {
        put("type", type)
        icon?.let { put("icon", it) }
    }
    @Test fun toolDetailsChooseTheirOwnOfficialGlyph() {
        val expected = mapOf("shell" to PaseoIcons.SquareTerminal, "read" to PaseoIcons.Eye, "edit" to PaseoIcons.Pencil,
            "write" to PaseoIcons.Pencil, "search" to PaseoIcons.Search, "fetch" to PaseoIcons.Search,
            "sub_agent" to PaseoIcons.Bot, "plan" to PaseoIcons.Brain, "unknown" to PaseoIcons.Wrench)
        expected.forEach { (type, icon) -> assertEquals(type, icon, toolCallIcon("tool", detail(type))) }
        assertEquals(PaseoIcons.Eye, toolCallIcon("custom", detail("plain_text", "eye")))
        assertEquals(PaseoIcons.Wrench, toolCallIcon("unrecognized", detail("new_type")))
    }
    @Test fun toolNamesRespectOfficialNamespacesAndSpecialCases() {
        assertEquals(PaseoIcons.Brain, toolCallIcon(" Thinking ", detail("unknown")))
        assertEquals(PaseoIcons.MicVocal, toolCallIcon("speak", detail("unknown")))
        assertEquals(PaseoIcons.Bot, toolCallIcon("task", detail("plain_text")))
        listOf("mcp__paseo__send_agent_message", "mcp__paseo_host__send", "paseo.send", "paseo_host.send").forEach {
            assertEquals(PaseoIcons.Paseo, toolCallIcon(it, detail("unknown")))
        }
        listOf("paseo_custom", "mcp__other__paseo", "mcp__paseo__speak").forEach {
            assertEquals(PaseoIcons.Wrench, toolCallIcon(it, detail("unknown")))
        }
    }
    @Test fun fileIconsUseOfficialExtensionRulesAndFallback() {
        assertEquals(PaseoIcons.file("README.md"), PaseoIcons.file("README.MD"))
        assertNotEquals(PaseoIcons.file("Main.kt"), PaseoIcons.file("Main.java"))
        assertEquals(PaseoIcons.file("a.tsx"), PaseoIcons.file("A.TSX"))
        assertEquals(PaseoIcons.file("unknown"), PaseoIcons.file("unknown."))
        assertEquals(PaseoIcons.file("unknown"), PaseoIcons.file("unknown.unrecognized"))
        assertNotEquals(PaseoIcons.file("unknown"), PaseoIcons.file("Cargo.lock"))
        assertNotEquals(fileChangeIcon("added"), fileChangeIcon("deleted"))
        assertEquals(PaseoIcons.SquareDot, fileChangeIcon("modified"))
    }
    @Test fun diffFlagsFollowTheOfficialPriority() {
        assertEquals("added", diffFileChangeKind(buildJsonObject { put("isNew", true); put("isDeleted", false) }))
        assertEquals("deleted", diffFileChangeKind(buildJsonObject { put("isNew", false); put("isDeleted", true) }))
        assertEquals("modified", diffFileChangeKind(buildJsonObject { put("isNew", false); put("isDeleted", false); put("status", "text") }))
        assertEquals("added", diffFileChangeKind(buildJsonObject { put("isNew", true); put("isDeleted", true) }))
    }
    @Test fun builtInProvidersHaveTheirOwnMarksAndUnknownProvidersUseBot() {
        val names = listOf("antigravity", "claude", "codex", "copilot", "kiro", "minimax", "omp", "opencode", "pi")
        val icons = names.map { PaseoIcons.provider(it) }
        assertEquals(names.size, icons.toSet().size)
        assertFalse(icons.contains(PaseoIcons.Bot))
        assertEquals(PaseoIcons.provider("claude"), PaseoIcons.provider("CLAUDE"))
        assertEquals(PaseoIcons.Bot, PaseoIcons.provider("custom-provider"))
    }
}
