package sh.paseo.android.core

import java.time.Instant

/** Matches the official sidebar's persisted sort values. */
enum class WorkspaceSort(val key: String) {
    Manual("manual"), Created("created"), LastPrompt("lastPrompt");

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: Created
    }
}

fun sortSidebarWorkspaces(
    workspaces: List<Workspace>, sort: WorkspaceSort, automaticSortSupported: Boolean,
    manualOrder: List<String> = emptyList(),
): List<Workspace> {
    fun time(value: String?): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)
    val base = if (!automaticSortSupported && sort != WorkspaceSort.Manual) {
        workspaces.sortedWith(compareBy<Workspace> { it.name.lowercase() }.thenBy { it.id })
    } else {
        workspaces.sortedWith(compareByDescending<Workspace> {
            time(if (sort == WorkspaceSort.LastPrompt) it.lastUserMessageAt ?: it.createdAt else it.createdAt)
        }.thenBy { it.id })
    }
    val ranks = manualOrder.withIndex().associate { it.value to it.index }
    val ordered = if (sort == WorkspaceSort.Manual) base.sortedBy { ranks[it.id] ?: -1 } else base
    return ordered.sortedBy { it.pinnedAt == null }
}

/** Reordering a filtered view must retain hidden workspaces. */
fun moveSidebarWorkspace(order: List<String>, from: String, to: String): List<String> {
    val source = order.indexOf(from)
    val target = order.indexOf(to)
    if (source < 0 || target < 0 || source == target) return order
    return order.toMutableList().apply { removeAt(source); add(target, from) }
}
