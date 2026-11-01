@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import sh.paseo.android.core.*

internal class ToolExpansion {
    val calls = mutableStateMapOf<String, Boolean>()
    val groups = mutableStateMapOf<String, Boolean>()
    var selectedCall by mutableStateOf<String?>(null)
}
internal val LocalToolExpansion = staticCompositionLocalOf<ToolExpansion> { error("Missing conversation tool state") }
private val toolExpansionSaver = Saver<ToolExpansion, List<String>>(
    save = { state -> state.calls.filterValues { it }.keys.map { "c:$it" } + state.groups.filterValues { it }.keys.map { "g:$it" } + listOfNotNull(state.selectedCall?.let { "s:$it" }) },
    restore = { saved -> ToolExpansion().apply { saved.forEach {
        when { it.startsWith("c:") -> calls[it.drop(2)] = true; it.startsWith("g:") -> groups[it.drop(2)] = true; else -> selectedCall = it.drop(2) }
    } } },
)
@Composable
internal fun rememberToolExpansion() = rememberSaveable(saver = toolExpansionSaver) { ToolExpansion() }

/** Official ExpandableBadge: muted text, a 12dp glyph in a 22dp slot, and no trailing chevron. */
@Composable
internal fun ToolBadge(label: String, summary: String = "", icon: Int, running: Boolean = false,
    failed: Boolean = false, expanded: Boolean = false, attached: Boolean = false, onClick: (() -> Unit)? = null) {
    val palette = LocalPaseoPalette.current
    val color = if (expanded || running) palette.foreground else palette.foregroundMuted
    Row(Modifier.fillMaxWidth().background(if (expanded) palette.surface1 else Color.Transparent, if (expanded && attached) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(12.dp))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { if (failed) stateDescription = "失败" else if (running) stateDescription = "执行中" }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            PaseoGlyph(if (expanded) PaseoIcons.ChevronDown else if (failed) PaseoIcons.TriangleAlert else icon,
                modifier = Modifier.size(12.dp), tint = if (failed && !expanded) palette.destructive.copy(alpha = .8f) else color)
        }
        Spacer(Modifier.width(4.dp))
        val shimmer = if (running && LocalTimelineActive.current && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            val phase by rememberInfiniteTransition(label = "tool-shimmer").animateFloat(-.3f, 1.3f,
                infiniteRepeatable(tween(((1.25f + (label.length + summary.length) * .008f - if (label.length + summary.length <= 12) .25f else 0f).coerceIn(1f, 2.3f) * 1000).toInt(), easing = LinearEasing)), label = "tool-shimmer-position")
            phase
        } else null
        BoxWithConstraints(Modifier.weight(1f)) {
            val availableWidth = maxWidth
            val width = with(LocalDensity.current) { availableWidth.toPx() }
            val brush = shimmer?.let { phase -> Brush.linearGradient(
                listOf(color.copy(alpha = .72f), color, color.copy(alpha = .72f)),
                Offset((phase - .15f) * width, 0f), Offset((phase + .15f) * width, 0f)) }
            val style = if (brush == null) TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Normal)
                else TextStyle(brush = brush, fontSize = 14.sp, fontWeight = FontWeight.Normal)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = availableWidth * if (summary.isEmpty()) 1f else .6f))
                if (summary.isNotBlank()) Text(summary.replace('\n', ' '), Modifier.padding(start = 8.dp).weight(1f),
                    style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ToolMessage(row: TimelineRow, cwd: String, inline: Boolean = false) {
    val display = remember(row, cwd) { toolDisplay(row, cwd) }
    val expansion = LocalToolExpansion.current
    val opened = inline && expansion.calls[row.key] == true
    fun setOpened(value: Boolean) {
        if (inline) expansion.calls[row.key] = value
        else expansion.selectedCall = row.key
    }
    if (row.detail.string("type") == "plan") {
        Surface(color = LocalPaseoPalette.current.surface1, contentColor = LocalPaseoPalette.current.foreground, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) { Text("Plan", fontWeight = FontWeight.SemiBold); MarkdownText(row.detail.string("text")) }
        }
        return
    }
    val palette = LocalPaseoPalette.current
    Column(Modifier.then(if (inline && opened) Modifier.border(1.dp, palette.border, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)) else Modifier)) {
        ToolBadge(display.name, display.summary, toolCallIcon(row.title, row.detail), row.toolRunning(), row.status == "failed",
            expanded = inline && opened, attached = true, onClick = if (display.canOpen) ({ setOpened(!opened) }) else null)
        if (opened && inline) ToolDetails(row, display, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
    }
}

/** The mobile detail sheet belongs to the conversation, so grouping/scrolling cannot dismiss it. */
@Composable
internal fun ConversationToolSheet(rows: List<TimelineRow>, cwd: String) {
    val expansion = LocalToolExpansion.current
    val selected = expansion.selectedCall ?: return
    val row = rows.firstOrNull { it.key == selected }
    if (row == null) {
        LaunchedEffect(selected) { expansion.selectedCall = null }
        return
    }
    val display = remember(row, cwd) { toolDisplay(row, cwd) }
    ToolSheet(display.name, toolCallIcon(row.title, row.detail), { expansion.selectedCall = null }) {
        ToolDetails(row, display, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
    }
}

@Composable
internal fun ToolGroup(entry: ToolTimelineEntry, cwd: String) {
    val expansion = LocalToolExpansion.current
    val opened = expansion.groups[entry.key] == true
    val label = remember(entry.calls) { toolSummary(entry.calls) }
    ToolBadge(label, icon = PaseoIcons.Wrench, running = entry.calls.any { it.toolRunning() }, onClick = { expansion.groups[entry.key] = true })
    if (opened) ToolSheet(label, PaseoIcons.Wrench, { expansion.groups[entry.key] = false }) {
        val scroll = rememberScrollState()
        LaunchedEffect(entry.calls.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 13.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entry.calls.forEach { row -> key(row.key) {
                if (row.type == "reasoning") ReasoningMessage(row) else ToolMessage(row, cwd, inline = true)
            } }
        }
    }
}

@Composable
internal fun ReasoningMessage(row: TimelineRow) {
    var opened by rememberSaveable(row.key) { mutableStateOf(false) }
    Column {
        ToolBadge("思考过程", icon = PaseoIcons.Brain, running = row.toolRunning(), expanded = opened, onClick = { opened = !opened })
        if (opened) Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(12.dp)) { MarkdownText(row.text) }
    }
}

/** Phone sheets start at 60%, expand to 95% via the handle, and dismiss by dragging down/back. */
@Composable
private fun ToolSheet(title: String, icon: Int, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var tall by rememberSaveable { mutableStateOf(false) }
    val fraction by animateFloatAsState(if (tall) .95f else .6f, label = "tool-sheet-height")
    val palette = LocalPaseoPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = palette.surface2,
        contentColor = palette.foreground, scrimColor = Color.Black.copy(alpha = .5f), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null) {
        Column(Modifier.fillMaxHeight(fraction)) {
            var drag by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxWidth().height(24.dp).semantics {
                contentDescription = "调整工具详情高度"
                onClick { tall = !tall; true }
            }.pointerInput(tall) {
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                    if (drag < -24.dp.toPx()) tall = true
                    else if (drag > 24.dp.toPx()) { if (tall) tall = false else onClose() }
                }) { change, amount -> change.consume(); drag += amount }
            }, contentAlignment = Alignment.Center) { Box(Modifier.size(32.dp, 4.dp).background(palette.foregroundMuted.copy(alpha = .4f), RoundedCornerShape(2.dp))) }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaseoGlyph(icon, modifier = Modifier.size(20.dp))
                Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { PaseoGlyph(PaseoIcons.X, "关闭工具详情", Modifier.size(20.dp), palette.foregroundMuted) }
            }
            HorizontalDivider(color = palette.border)
            content()
        }
    }
}

@Composable
private fun ToolDetails(row: TimelineRow, display: ToolDisplay, modifier: Modifier = Modifier) {
    val detail = row.detail
    val palette = LocalPaseoPalette.current
    Column(modifier.fillMaxWidth().background(palette.surface2).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (display.loadingDetails) {
            repeat(3) { Box(Modifier.fillMaxWidth(if (it == 2) .6f else 1f).height(14.dp).background(palette.surface3, RoundedCornerShape(4.dp))) }
        }
        display.error?.let { Text(it, color = palette.destructive, fontSize = 13.sp) }
        when (detail.string("type")) {
            "shell" -> ToolCode("$ " + detail.string("command").trimEnd('\n') + detail.string("output").trimStart('\n').let { if (it.isNotEmpty()) "\n\n$it" else "" })
            "read", "write" -> { ToolPath(detail.string("filePath")); ToolCode(detail.string("content")) }
            "edit" -> {
                ToolPath(detail.string("filePath"))
                val diff = detail.string("unifiedDiff").ifBlank {
                    detail.string("oldString").lines().joinToString("\n") { "-$it" } + "\n" + detail.string("newString").lines().joinToString("\n") { "+$it" }
                }
                SelectionContainer { Column(Modifier.horizontalScroll(rememberScrollState())) { diff.lines().forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = when {
                        line.startsWith('+') -> palette.diffAddition; line.startsWith('-') -> palette.diffDeletion
                        line.startsWith("@@") -> palette.foregroundMuted; else -> palette.foreground
                    })
                } } }
            }
            "plain_text", "plan" -> MarkdownText(detail.string("text"))
            "sub_agent" -> { Text(detail.string("description"), fontSize = 14.sp); ToolCode(detail.string("log")) }
            "fetch" -> { Text(detail.string("url"), fontSize = 14.sp, color = palette.foregroundMuted); ToolCode(detail.string("result").ifBlank { detail.string("codeText") }) }
            "search" -> {
                Text(detail.string("query"), fontSize = 14.sp)
                if (detail.string("content").isNotBlank()) ToolCode(detail.string("content"))
                detail.array("filePaths").forEach { ToolPath((it as? JsonPrimitive)?.content.orEmpty()) }
                detail.array("webResults").forEach { ToolCode(it.toString()) }
            }
            else -> listOf("input" to "输入", "output" to "输出").forEach { (field, label) ->
                detail[field]?.takeUnless { it is JsonNull }?.let { value ->
                    Text(label, fontSize = 12.sp, color = palette.foregroundMuted)
                    ToolCode(if (value is JsonPrimitive) value.content else Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value))
                }
            }
        }
    }
}

@Composable
private fun ToolPath(path: String) {
    if (path.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FileGlyph(path, false); Text(path, fontSize = 12.sp, color = LocalPaseoPalette.current.foregroundMuted)
    }
}

@Composable
private fun ToolCode(text: String) {
    if (text.isNotEmpty()) SelectionContainer {
        Text(text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
            fontSize = 12.sp, lineHeight = 20.sp, color = LocalPaseoPalette.current.foreground)
    }
}
