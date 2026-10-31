package sh.paseo.android.core

/** Completed turns keep their final answer visible; opening the summary reveals earlier work. */
fun conversationPresentation(rows: List<TimelineRow>, running: Boolean, expanded: Set<String>, collapse: Boolean = true): List<TimelineRow> {
    if (!collapse) return rows
    val result = mutableListOf<TimelineRow>()
    var index = 0
    while (index < rows.size) {
        val host = rows[index]
        result += host
        if (!host.isTurnTrigger()) { index++; continue }
        val end = ((index + 1) until rows.size).firstOrNull { !continuesTimelineTurn(rows[it - 1], rows[it]) } ?: rows.size
        val reply = ((index + 1) until end).lastOrNull { rows[it].type == "assistant_message" }
        val folded = reply?.let { rows.subList(index + 1, it) }.orEmpty()
        val work = folded.any { it.type in setOf("tool_call", "todo", "assistant_message") }
        if (reply != null && work && (end < rows.size || !running)) {
            result += TimelineRow("summary:${host.key}", "turn_summary", title = toolSummary(folded), messageId = host.key)
            if (host.key in expanded) result += folded else result += folded.filter { it.isTurnTrigger() || it.type == "compaction" }
            result += rows.subList(reply, end)
        } else result += rows.subList(index + 1, end)
        index = end
    }
    return result
}

/** Expanded turn details render individually inside an indent rail, without regrouping. */
fun expandedTurnWorkKeys(rows: List<TimelineRow>, expanded: Set<String>): Set<String> = buildSet {
    rows.forEachIndexed { index, host ->
        if (host.key !in expanded || !host.isTurnTrigger()) return@forEachIndexed
        val end = ((index + 1) until rows.size).firstOrNull { !continuesTimelineTurn(rows[it - 1], rows[it]) } ?: rows.size
        val reply = ((index + 1) until end).lastOrNull { rows[it].type == "assistant_message" } ?: return@forEachIndexed
        rows.subList(index + 1, reply).filterNot { it.isTurnTrigger() || it.type == "compaction" }.forEach { add(it.key) }
    }
}

fun forgeAttachment(item: kotlinx.serialization.json.JsonObject, modern: Boolean): ChatAttachment {
    val change = item.string("kind") in setOf("change_request", "pr")
    val type = if (modern) (if (change) "forge_change_request" else "forge_issue") else if (change) "github_pr" else "github_issue"
    val mime = if (modern) (if (change) "application/paseo-forge-change-request" else "application/paseo-forge-issue") else if (change) "application/github-pr" else "application/github-issue"
    val fields = item.filterKeys { it in setOf("number", "title", "url", "body", "baseRefName", "headRefName") || modern && it in setOf("projectPath", "forge") }
    val payload = kotlinx.serialization.json.JsonObject(fields + jsonObject("type" to type, "mimeType" to mime, "forge" to item.string("forge").ifBlank { "github" }.takeIf { modern }))
    return ChatAttachment(name = "#${item.long("number")} ${item.string("title")}", mimeType = mime, payload = payload)
}
