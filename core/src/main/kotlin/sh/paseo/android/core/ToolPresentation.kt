package sh.paseo.android.core

import java.time.Instant
import kotlinx.serialization.json.*

enum class ConversationDisplayMode {
    Full,
    CollapseToolCalls,
    CollapseTurns,
}

/** Mirrors the official tool-call display, grouping and turn membership rules. */
data class ToolDisplay(val name: String, val summary: String, val error: String?, val canOpen: Boolean, val loadingDetails: Boolean)

fun TimelineRow.isTurnTrigger() = type == "user_message" || type == "tool_call" && (title == "heartbeat" || agentMessage != null)
fun continuesTimelineTurn(previous: TimelineRow, next: TimelineRow): Boolean =
    if (previous.turnId.isNotBlank() && next.turnId.isNotBlank()) previous.turnId == next.turnId else !next.isTurnTrigger()
fun TimelineRow.toolRunning() = status == "running" || status == "executing"
fun TimelineRow.groupable() = type == "reasoning" || type == "tool_call" && !isTurnTrigger() && detail.string("type") != "plan" && !title.equals("speak", true)
fun visibleToolRow(row: TimelineRow) = row.type != "tool_call" || (row.title != "ExitPlanMode" && !(row.title == "plan_approval" && row.status == "running"))

data class ToolTimelineEntry(val row: TimelineRow, val calls: List<TimelineRow> = emptyList()) {
    val key get() = row.key
    val grouped get() = calls.size > 1
}

fun groupToolRows(rows: List<TimelineRow>, enabled: Boolean = true, ungroupedKeys: Set<String> = emptySet()): List<ToolTimelineEntry> {
    val result = mutableListOf<ToolTimelineEntry>()
    val pending = mutableListOf<TimelineRow>()
    fun flush() {
        if (pending.isNotEmpty()) result += ToolTimelineEntry(pending.first(), if (pending.size > 1) pending.toList() else emptyList())
        pending.clear()
    }
    for (row in rows.filter(::visibleToolRow)) {
        if (enabled && row.key !in ungroupedKeys && row.groupable()) {
            if (pending.lastOrNull()?.let { !continuesTimelineTurn(it, row) } == true) flush()
            pending += row
        } else { flush(); result += ToolTimelineEntry(row) }
    }
    flush()
    return result
}

fun toolSummary(rows: List<TimelineRow>): String {
    val edited = mutableSetOf<String>(); val read = mutableSetOf<String>()
    var commands = 0; var searches = 0; var other = 0; var thoughts = 0
    rows.forEach { row ->
        if (row.type == "reasoning") thoughts++
        else if (row.type == "tool_call" && !row.isTurnTrigger()) when (row.detail.string("type")) {
            "edit", "write" -> edited += row.detail.string("filePath")
            "read" -> read += row.detail.string("filePath")
            "shell" -> commands++
            "search" -> searches++
            else -> if (Regex("(?:^|[_.:/])(?:web_search|llm_context)$").containsMatchIn(row.title.lowercase())) searches++ else other++
        }
    }
    val parts = listOfNotNull(
        "编辑了 ${edited.size} 个文件".takeIf { edited.isNotEmpty() },
        "运行了 $commands 个命令".takeIf { commands > 0 },
        "读取了 ${read.size} 个文件".takeIf { read.isNotEmpty() },
        "搜索了 $searches 次".takeIf { searches > 0 },
        "使用了 $other 个其他工具".takeIf { other > 0 },
    )
    return when (parts.size) {
        0 -> if (thoughts > 0) "已思考" else "查看过程"
        1 -> parts.single()
        2 -> "${parts[0]} 并 ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") + ", 并 " + parts.last()
    }
}

private fun JsonElement?.meaningful(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> content.isNotBlank()
    is JsonArray -> any { it.meaningful() }
    is JsonObject -> values.any { it.meaningful() }
}
fun toolDisplay(row: TimelineRow, cwd: String): ToolDisplay {
    val detail = row.detail
    val type = detail.string("type")
    fun path() = detail.string("filePath").let { if (cwd.isNotBlank()) it.removePrefix(cwd.trimEnd('/') + "/") else it }
    val human = row.title.trim().let { name ->
        if (Regex("[:./]|__").containsMatchIn(name)) name
        else name.replace(Regex("[_-]+"), " ").lowercase().replaceFirstChar { it.uppercase() }
    }
    val name = when (type) {
        "shell" -> "Shell"; "read" -> "Read"; "edit" -> "Edit"; "write" -> "Write"
        "search" -> "Search"; "fetch" -> "Fetch"; "worktree_setup" -> "Worktree setup"
        "sub_agent" -> detail.string("subAgentType").ifBlank { "Task" }; "plan" -> "Plan"
        else -> human
    }
    val summary = when (type) {
        "shell" -> detail.string("command")
        "read", "edit", "write" -> path()
        "search" -> detail.string("query"); "fetch" -> detail.string("url")
        "worktree_setup" -> detail.string("branchName"); "sub_agent" -> detail.string("description")
        "plain_text" -> detail.string("label")
        else -> if (row.title.equals("task", true)) row.metadata.string("subAgentActivity") else ""
    }
    val error = row.error?.takeIf { row.status == "failed" }?.let {
        when (it) { is JsonPrimitive -> it.content; is JsonObject -> it.string("content").ifBlank { it.toString() }; else -> it.toString() }
    }
    val hasDetails = error != null || when (type) {
        "shell" -> true
        "worktree_setup" -> false
        else -> detail.filterKeys { it != "type" }.values.any { it.meaningful() }
    }
    val loading = row.toolRunning() && row.error == null && !hasDetails && type != "worktree_setup"
    return ToolDisplay(name, summary, error, hasDetails || loading, loading)
}

fun turnStartedAt(agent: Agent): Long? = agent.activeTurn?.string("startedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
fun formatTurnDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return when {
        seconds < 60 -> "${seconds}s"
        minutes < 60 -> "${minutes}m" + if (seconds % 60 > 0) " ${seconds % 60}s" else ""
        else -> "${minutes / 60}h" + if (minutes % 60 > 0) " ${minutes % 60}m" else ""
    }
}

/** A late close for a previous run must not stop the current run's footer or timer. */
fun Agent.applyTurnLifecycle(event: JsonObject, timestamp: String): Agent {
    val opening = event.string("type") == "turn_started"
    val turnId = event.string("turnId")
    if (!opening && turnId.isNotBlank() && currentTurnId.isNotBlank() && turnId != currentTurnId) return this
    val start = if (opening && currentTurnId == turnId) activeTurn?.string("startedAt").orEmpty() else ""
    return copy(status = if (opening) "running" else if (event.string("type") == "turn_failed") "error" else "idle",
        activeTurnId = turnId.takeIf { opening },
        activeTurn = if (opening) jsonObject("turnId" to turnId,
            "startedAt" to start.ifBlank { timestamp.ifBlank { event.string("startedAt") } }) else null)
}
