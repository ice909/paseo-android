package sh.paseo.android.core

import kotlinx.serialization.json.*

/** Percentages are nullable: an unavailable quota must never look like 0% used. */
data class UsageWindow(val id: String, val label: String, val shortLabel: String, val summary: Boolean,
    val usedPercent: Double?, val resetsAt: String, val tone: String) {
    fun percent(remaining: Boolean = false): Double? = usedPercent?.let { if (remaining) 100 - it else it }
}
data class UsageEntry(val id: String, val sourceId: String, val label: String, val icon: String,
    val account: String, val plan: String, val status: String, val problem: String,
    val fetchedAt: String, val windows: List<UsageWindow>, val details: List<Pair<String, String>>) {
    val summaryWindows: List<UsageWindow> get() = if (status != "available") emptyList() else
        windows.filter { it.usedPercent != null }.let { valid -> valid.filter { it.summary }.ifEmpty { valid.take(1) } }
}
private fun JsonObject.percent(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
private fun usageWindows(report: JsonObject): List<UsageWindow> = report.array("windows").mapNotNull { it as? JsonObject }.map {
    UsageWindow(it.string("id"), it.string("label"), (it["shortLabel"] as? JsonPrimitive)?.contentOrNull ?: it.string("label"), it.bool("summary"),
        (it.percent("usedPct") ?: it.percent("remainingPct")?.let { pct -> 100 - pct })?.coerceIn(0.0, 100.0),
        it.string("resetsAt"), it.string("tone"))
}
fun parseUsageEntry(entry: JsonObject): UsageEntry {
    val report = entry.obj("report")
    val problem = report.obj("problem")
    return UsageEntry(entry.string("id"), entry.string("sourceId"), entry.string("sourceLabel"),
        entry.string("icon").ifBlank { entry.string("sourceId") }, entry.obj("account").string("label"), report.string("planLabel"),
        report.string("status"), report.string("error").ifBlank { problem.string("detail").ifBlank { problem.string("kind") } },
        entry.string("fetchedAt"), usageWindows(report), report.array("details").mapNotNull { it as? JsonObject }.map { it.string("label") to it.string("value") })
}
fun parseLegacyUsage(entry: JsonObject): UsageEntry = parseUsageEntry(jsonObject(
    "id" to entry.string("providerId"), "sourceId" to entry.string("providerId"), "sourceLabel" to entry.string("displayName"),
    "icon" to entry.string("providerId"), "fetchedAt" to entry.string("fetchedAt"), "report" to entry,
))

/** Matches the official identity palette and its unsigned 31-based hash. */
fun projectIdentityColor(key: String): Long {
    val colors = longArrayOf(0xff7a6aa8, 0xff3d7ea6, 0xff388068, 0xffa4673a, 0xffb05c80,
        0xff6a70b8, 0xff368080, 0xffb06260, 0xff8f7838, 0xff5179b0)
    var hash = 0L
    key.codePoints().forEach { hash = (hash * 31 + Character.toChars(it)[0].code) and 0xffffffffL }
    return colors[(hash % colors.size).toInt()]
}
