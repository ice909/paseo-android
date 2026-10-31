package sh.paseo.android.core

import kotlinx.serialization.Serializable

/** Opaque identities only: restoring a view must never create a workspace or an agent. */
@Serializable
data class WorkspaceSelection(val hostId: String, val workspaceId: String, val agentId: String? = null) {
    init {
        require(hostId.isNotBlank() && workspaceId.isNotBlank())
        require(agentId == null || agentId.isNotBlank())
    }
}

/** Ignore intermediate selections while switching workspaces or loading their agent catalog. */
fun PaseoState.workspaceSelection(): WorkspaceSelection? {
    if (restoringWorkspace != null) return null
    val hostId = host?.id ?: return null
    val workspace = selectedWorkspace ?: return null
    val agent = selectedAgent
    if (selectedAgentId != null && (agent == null || agent.archivedAt != null || !workspace.contains(agent))) return null
    if (agent == null && workspaceAgents.isNotEmpty()) return null
    return WorkspaceSelection(hostId, workspace.id, agent?.id)
}
