@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.paseo.android.core.*
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SidebarUsage(repository: PaseoRepository, state: PaseoState, active: Boolean, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    var reports by remember(state.host?.id, state.connected) { mutableStateOf<List<UsageEntry>>(emptyList()) }
    var error by remember(state.host?.id, state.connected) { mutableStateOf<String?>(null) }
    var loading by remember(state.host?.id, state.connected) { mutableStateOf(false) }
    var refresh by remember(state.host?.id) { mutableIntStateOf(0) }
    var appliedRefresh by remember(state.host?.id) { mutableIntStateOf(0) }
    var remaining by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.host?.id, state.connected, state.features, active, expanded, refresh) {
        if (!state.connected || (!active && !expanded)) return@LaunchedEffect
        var force = refresh != appliedRefresh
        appliedRefresh = refresh
        while (true) {
            loading = true
            try { reports = repository.usageReports(force); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法读取用量" }
            finally { loading = false }
            force = false
            delay(60_000)
        }
    }
    val summary = reports.flatMap { report -> report.summaryWindows.map { report to it } }
    val message = when {
        !state.connected -> "连接主机以查看用量"
        error != null -> "用量暂不可用"
        loading && reports.isEmpty() -> "正在读取用量…"
        else -> "暂无可用额度数据"
    }
    val palette = LocalPaseoPalette.current
    Column(Modifier.fillMaxWidth().clickable { onExpanded(true) }.padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics { contentDescription = "查看用量，${if (remaining) "剩余" else "已用"}" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (summary.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaseoGlyph(PaseoIcons.provider("codex"), null, Modifier.size(14.dp), palette.foregroundMuted)
            Text(message, fontSize = 12.sp, color = palette.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (summary.isNotEmpty() && error != null) Text("刷新失败 · 显示上次数据", fontSize = 11.sp, color = palette.foregroundMuted)
        summary.forEach { (report, window) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaseoGlyph(usageIcon(report.sourceId), report.label, Modifier.size(14.dp), palette.foregroundMuted)
                QuotaBar(window.percent(remaining)!!, Modifier.weight(1f), quiet = true)
                Text("${window.percent(remaining)!!.roundToInt()}%", fontSize = 12.sp, color = palette.foreground)
                Text(window.shortLabel, fontSize = 12.sp, color = palette.foregroundMuted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 64.dp))
            }
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { onExpanded(false) }, containerColor = sidebarColor()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("用量", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { remaining = !remaining }) { Text(if (remaining) "显示：剩余" else "显示：已用") }
                IconButton(onClick = { refresh++ }, enabled = state.connected && !loading) {
                    PaseoGlyph(PaseoIcons.RefreshCw, "刷新用量", Modifier.size(18.dp))
                }
            }
            if (!state.connected) Text("连接主机以查看用量")
            else if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && state.connected && reports.isEmpty() && error == null) Text("主机尚未提供用量数据，请检查主机上的账户登录状态。")
            reports.forEach { report ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaseoGlyph(usageIcon(report.sourceId), null, Modifier.size(18.dp))
                    Text(listOf(report.label, report.account, report.plan).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                }
                if (report.status != "available") Text(report.problem.ifBlank { "此账户的用量暂不可用" }, color = palette.foregroundMuted)
                else report.windows.forEach { window ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val pct = window.percent(remaining)
                        Row {
                            Text(window.label, Modifier.weight(1f), fontSize = 14.sp)
                            Text(pct?.let { "${it.roundToInt()}% ${if (remaining) "剩余" else "已用"}" } ?: "暂无数据", fontSize = 14.sp)
                        }
                        if (pct != null) QuotaBar(pct, Modifier.fillMaxWidth(), tone = window.tone.ifBlank { when { (window.usedPercent ?: 0.0) > 90 -> "danger"; (window.usedPercent ?: 0.0) >= 70 -> "warning"; else -> "default" } })
                        if (window.resetsAt.isNotBlank()) Text("重置时间：${quotaTime(window.resetsAt)}", fontSize = 12.sp, color = palette.foregroundMuted)
                    }
                }
                report.details.forEach { (label, value) -> Text("$label：$value", fontSize = 12.sp, color = palette.foregroundMuted) }
                if (report.fetchedAt.isNotBlank()) Text("更新于 ${quotaTime(report.fetchedAt)}", fontSize = 11.sp, color = palette.foregroundMuted)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun QuotaBar(percent: Double, modifier: Modifier, quiet: Boolean = false, tone: String = "default") {
    val palette = LocalPaseoPalette.current
    Box(modifier.height(6.dp).background(palette.surface3, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth((percent / 100).toFloat()).fillMaxHeight().background(if (quiet) palette.surface4 else when (tone) { "ok" -> palette.statusSuccess; "warning" -> palette.statusWarning; "danger" -> palette.statusDanger; else -> palette.foregroundMuted }, RoundedCornerShape(50)))
    }
}
private fun quotaTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

// Modern report.icon is catalog SVG, not a provider identifier. Use the matching
// bundled official brand asset; unknown sources retain the official gauge fallback.
private fun usageIcon(sourceId: String): Int = when (sourceId.lowercase()) {
    "antigravity", "claude", "codex", "copilot", "kiro", "minimax", "omp", "opencode", "pi" -> PaseoIcons.provider(sourceId)
    else -> PaseoIcons.Gauge
}
