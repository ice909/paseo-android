package sh.paseo.android.core

/** Completed turns keep their final answer visible; opening the summary reveals earlier work. */
fun conversationPresentation(rows: List<TimelineRow>, running: Boolean, expanded: Set<String>, collapse: Boolean = true): List<TimelineRow> {
    if (!collapse) return rows
    val result = mutableListOf<TimelineRow>()
    var index = 0
    while (index < rows.size) {
        val host = rows[index]
        result += host
        if (host.type != "user_message") { index++; continue }
        val end = ((index + 1) until rows.size).firstOrNull { rows[it].type == "user_message" } ?: rows.size
        val reply = ((index + 1) until end).lastOrNull { rows[it].type == "assistant_message" }
        val folded = reply?.let { rows.subList(index + 1, it) }.orEmpty()
        val work = folded.any { it.type in setOf("tool_call", "todo", "assistant_message") }
        if (reply != null && work && (end < rows.size || !running)) {
            val tools = folded.count { it.type == "tool_call" }
            result += TimelineRow("summary:${host.key}", "turn_summary", title = if (tools > 0) "$tools 项操作" else "查看过程", messageId = host.key)
            if (host.key in expanded) result += folded else result += folded.filter { it.type == "compaction" }
            result += rows.subList(reply, end)
        } else result += rows.subList(index + 1, end)
        index = end
    }
    return result
}

fun forgeAttachment(item: kotlinx.serialization.json.JsonObject, modern: Boolean): ChatAttachment {
    val change = item.string("kind") in setOf("change_request", "pr")
    val type = if (modern) (if (change) "forge_change_request" else "forge_issue") else if (change) "github_pr" else "github_issue"
    val mime = if (modern) (if (change) "application/paseo-forge-change-request" else "application/paseo-forge-issue") else if (change) "application/github-pr" else "application/github-issue"
    val fields = item.filterKeys { it in setOf("number", "title", "url", "body", "baseRefName", "headRefName") || modern && it in setOf("projectPath", "forge") }
    val payload = kotlinx.serialization.json.JsonObject(fields + jsonObject("type" to type, "mimeType" to mime, "forge" to item.string("forge").ifBlank { "github" }.takeIf { modern }))
    return ChatAttachment(name = "#${item.long("number")} ${item.string("title")}", mimeType = mime, payload = payload)
}
