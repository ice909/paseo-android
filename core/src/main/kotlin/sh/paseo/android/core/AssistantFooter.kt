package sh.paseo.android.core

import java.time.Instant

/** A response owns one footer after its last item, including trailing tools. */
data class AssistantFooter(val content: String?, val completedAt: Long?, val durationMs: Long?)

fun completedAssistantFooters(rows: List<TimelineRow>, running: Boolean): Map<String, AssistantFooter> = buildMap {
    var start = 0
    while (start < rows.size) {
        var end = start + 1
        while (end < rows.size && continuesTimelineTurn(rows[end - 1], rows[end])) end++
        val last = rows[end - 1]
        // The latest live response uses the working footer, while historical responses keep theirs.
        if (!(running && end == rows.size) && (last.type != "user_message" || end == rows.size)) {
            val messages = rows.subList(start, end).filter { it.type == "assistant_message" }
            if (messages.isNotEmpty() || last.type == "tool_call") {
                val completed = timestampMillis(last.timestamp)
                val began = rows[start].takeIf { it.isTurnTrigger() }?.let { timestampMillis(it.timestamp) }
                put(last.key, AssistantFooter(
                    content = messages.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { it.text },
                    completedAt = completed,
                    durationMs = if (completed != null && began != null) (completed - began).coerceAtLeast(0) else null,
                ))
            }
        }
        start = end
    }
}

private fun timestampMillis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
