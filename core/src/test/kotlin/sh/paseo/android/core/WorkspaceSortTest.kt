package sh.paseo.android.core

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSortTest {
    private fun workspace(id: String, created: String?, prompt: String? = null, pin: String? = null) =
        Workspace(id, "project", "Project", "/project", name = id,
            createdAt = created, lastUserMessageAt = prompt, pinnedAt = pin)
    private val older = "2026-01-01T00:00:00Z"
    private val newer = "2026-02-01T00:00:00Z"

    @Test fun creationAndPromptUseDifferentTimesAndPromptFallsBackToCreation() {
        val a = workspace("a", older, "2026-03-01T00:00:00Z")
        val b = workspace("b", newer)
        assertEquals(listOf(b, a), sortSidebarWorkspaces(listOf(a, b), WorkspaceSort.Created, true))
        assertEquals(listOf(a, b), sortSidebarWorkspaces(listOf(b, a), WorkspaceSort.LastPrompt, true))
    }

    @Test fun timestampsUseInstantsAndTiesAreStable() {
        val a = workspace("a", "2026-02-01T01:00:00+01:00")
        val b = workspace("b", newer)
        assertEquals(listOf(a, b), sortSidebarWorkspaces(listOf(b, a), WorkspaceSort.Created, true))
    }

    @Test fun manualOrderSurvivesUpdatesAndIncludesNewWorkspaces() {
        val a = workspace("a", newer)
        val b = workspace("b", older)
        val c = workspace("c", "2026-03-01T00:00:00Z")
        assertEquals(listOf(c, b, a), sortSidebarWorkspaces(listOf(c, a, b), WorkspaceSort.Manual, true, listOf("deleted", "b", "a")))
        assertEquals(listOf("c", "a", "hidden", "b"),
            moveSidebarWorkspace(listOf("a", "hidden", "c", "b"), "c", "a"))
        assertEquals(listOf("a", "b"), moveSidebarWorkspace(listOf("a", "b"), "missing", "a"))
    }

    @Test fun olderHostsKeepFallbackAndPinsStayFirst() {
        val a = workspace("a", null)
        val b = workspace("b", null, pin = newer)
        assertEquals(listOf(b, a), sortSidebarWorkspaces(listOf(a, b), WorkspaceSort.Created, false))
        assertEquals(WorkspaceSort.Created, WorkspaceSort.fromKey("unknown"))
    }

    @Test fun wireFieldsAreOptionalAndDecoded() {
        val old = jsonObject("id" to "a", "projectId" to "p", "projectDisplayName" to "P", "projectRootPath" to "/p", "name" to "A")
        assertNull(wireJson.decodeFromJsonElement<Workspace>(old).createdAt)
        val current = wireJson.decodeFromJsonElement<Workspace>(jsonObject(*old.map { it.key to it.value }.toTypedArray(), "createdAt" to older, "lastUserMessageAt" to newer))
        assertEquals(older, current.createdAt)
        assertEquals(newer, current.lastUserMessageAt)
    }
}
