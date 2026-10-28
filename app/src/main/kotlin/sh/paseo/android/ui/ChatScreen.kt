@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.paseo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
fun ChatScreen(vm: AppViewModel, state: PaseoState) {
    var tab by rememberSaveable(state.selectedAgentId) { mutableIntStateOf(0) }
    val agent = state.selectedAgent
    Column(Modifier.fillMaxSize()) {
        if (!state.connected) ConnectionStrip(state) { state.host?.let(vm::connect) }
        SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("对话", "文件", "改动", "终端").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
        }
        if (agent == null) EmptyState(Icons.Outlined.Forum, "正在读取会话", "连接主机后自动恢复。", Modifier.weight(1f))
        else when (tab) {
            0 -> Conversation(vm, state, agent, Modifier.weight(1f))
            1 -> FilesPane(vm, agent, state.connected, Modifier.weight(1f))
            2 -> DiffPane(vm, agent, state.connected, Modifier.weight(1f))
            3 -> TerminalPane(vm, agent, state.connected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Conversation(vm: AppViewModel, state: PaseoState, agent: Agent, modifier: Modifier) {
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    val rows = state.timeline
    val last = rows.lastOrNull()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) following = listState.firstVisibleItemIndex == 0
        }
    }
    LaunchedEffect(last?.key, last?.text?.length) {
        // Stable lazy-list keys preserve the old anchor when new rows are inserted.
        // Remember whether the user was following before that insertion.
        if (following && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    Column(modifier.imePadding()) {
        if (state.loadingTimeline && rows.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (rows.isEmpty() && !state.loadingTimeline) EmptyState(Icons.Outlined.AutoAwesome, "从一个任务开始", "描述你想完成的工作，代理将在主机上执行。", Modifier.weight(1f))
        else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (agent.running) item("working") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Text(if (agent.pendingPermissions.isNotEmpty()) "等待你的确认" else "代理正在工作…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(rows.asReversed(), key = { it.key }, contentType = { it.type }) { row -> TimelineItem(row) }
            if (state.hasOlder) item("older") {
                TextButton(onClick = { vm.action { vm.repository.loadOlder() } }, enabled = !state.loadingTimeline, modifier = Modifier.fillMaxWidth()) { Text(if (state.loadingTimeline) "加载中…" else "加载更早的消息") }
            }
        }
        if (!following) TextButton(onClick = { following = true; scope.launch { listState.scrollToItem(0) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("最新消息")
        }
        if (agent.pendingPermissions.isNotEmpty()) Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            agent.pendingPermissions.forEach { request -> PermissionCard(vm, agent.id, request, state.connected) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = drafts[agent.id].orEmpty(), onValueChange = { vm.setDraft(agent.id, it) },
                modifier = Modifier.weight(1f), placeholder = { Text("发送消息…") },
                maxLines = 6, shape = RoundedCornerShape(20.dp),
            )
            if (agent.running && drafts[agent.id].isNullOrBlank()) FilledTonalIconButton(
                onClick = { vm.action { vm.repository.cancelAgent(agent.id) } }, enabled = state.connected,
                modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
            ) { Icon(Icons.Outlined.Stop, "停止生成") }
            else FilledIconButton(onClick = { following = true; vm.send() }, enabled = state.connected && !sending && !drafts[agent.id].isNullOrBlank(), modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "发送消息")
            }
        }
    }
}

@Composable
private fun TimelineItem(row: TimelineRow) {
    when (row.type) {
        "user_message" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 560.dp)) {
                SelectionContainer { Text(row.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge) }
            }
        }
        "assistant_message" -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Text("代理", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MarkdownText(row.text)
        }
        "reasoning" -> CollapsibleBlock("思考过程", row.text, Icons.Outlined.Psychology)
        "tool_call" -> {
            val body = row.detail.let { detail -> listOf(
                detail.string("command"), detail.string("filePath"), detail.string("unifiedDiff"),
                detail.string("output"), detail.string("content"), detail.string("text"), detail.string("log"),
                if (detail.string("type") == "edit" && detail.string("unifiedDiff").isBlank()) "${detail.string("oldString")}\n→\n${detail.string("newString")}" else "",
                detail["input"]?.takeUnless { it.toString() == "null" }?.toString().orEmpty(),
            ).filter { it.isNotBlank() }.joinToString("\n\n") }
            CollapsibleBlock("${when (row.status) { "completed" -> "✓"; "failed" -> "!"; "canceled" -> "−"; else -> "↳" }}  ${row.title}", body,
                Icons.Outlined.Terminal, isError = row.status == "failed")
        }
        "todo" -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("任务进度", style = MaterialTheme.typography.labelLarge)
                row.detail.array("items").forEach { element ->
                    val item = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (item.bool("completed") || item.string("status") == "completed") Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(item.string("text"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        "error", "notification" -> Text(row.text, color = if (row.type == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        "compaction" -> Text(if (row.status == "loading") "正在整理上下文…" else "上下文已整理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text("此记录类型暂未支持：${row.type}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun CollapsibleBlock(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isError: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(17.dp), tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开", Modifier.size(18.dp))
            }
            if (expanded && body.isNotEmpty()) CodeBlock(body, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun PermissionCard(vm: AppViewModel, agentId: String, request: PermissionRequest, connected: Boolean) {
    var pending by remember(request.id) { mutableStateOf(false) }
    val actions = request.actions.ifEmpty { listOf(PermissionAction("", "允许", "allow"), PermissionAction("", "拒绝", "deny")) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(request.title ?: request.name, style = MaterialTheme.typography.titleSmall)
            }
            request.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val detail = request.detail?.let { it.string("command").ifBlank { it.string("text").ifBlank { it.toString() } } } ?: request.input?.toString()
            detail?.let { Text(it.take(3000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    TextButton(onClick = {
                        pending = true
                        vm.action { try { vm.repository.respondToPermission(agentId, request, action.behavior, action.id.ifBlank { null }) }
                            finally { pending = false } }
                    }, enabled = connected && !pending) { Text(action.label, color = if (action.behavior == "deny") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}
