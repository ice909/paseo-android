@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
fun SidebarHistory(vm: AppViewModel, state: PaseoState, onDismiss: () -> Unit, onOpen: (Workspace, Agent) -> Unit) {
    var query by remember(state.host?.id) { mutableStateOf("") }
    var entries by remember(state.host?.id, query) { mutableStateOf<List<Agent>>(emptyList()) }
    var cursor by remember(state.host?.id, query) { mutableStateOf<String?>(null) }
    var next by remember(state.host?.id, query) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember(state.host?.id, query) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember(state.host?.id) { mutableStateOf<Agent?>(null) }
    var rows by remember(preview?.id) { mutableStateOf<List<TimelineRow>>(emptyList()) }
    LaunchedEffect(state.host?.id, state.connected, query, cursor, retry, preview?.id) {
        error = null
        if (!state.connected) { error = "请先连接主机"; return@LaunchedEffect }
        loading = true
        try {
            if (preview != null) rows = vm.repository.historyTranscript(preview!!.id)
            else {
                if (query.isNotEmpty() && cursor == null) delay(250)
                val page = vm.repository.historyPage(query, cursor)
                entries = (if (cursor == null) page.first else entries + page.first).distinctBy { it.id }
                next = page.second
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取历史失败" }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sidebarColor(), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (preview != null) IconButton(onClick = { preview = null }) { PaseoGlyph(PaseoIcons.ArrowLeft, "返回历史", Modifier.size(18.dp)) }
                Text(preview?.displayTitle ?: "历史", style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { PaseoGlyph(PaseoIcons.X, "关闭历史", Modifier.size(18.dp)) }
            }
            if (preview == null) OutlinedTextField(query, { query = it; cursor = null }, singleLine = true,
                placeholder = { Text("搜索历史会话") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            else Text("历史预览 · 最近 200 条记录", fontSize = 12.sp, color = LocalPaseoPalette.current.foregroundMuted)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重试") } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (preview == null) {
                    items(entries, key = { it.id }) { agent ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val workspace = state.workspaces.firstOrNull { it.contains(agent) }
                            if (workspace != null && state.agents.any { it.id == agent.id }) onOpen(workspace, agent)
                            else preview = agent
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PaseoGlyph(PaseoIcons.provider(agent.provider), null, Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(agent.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(agent.cwd, fontSize = 12.sp, color = LocalPaseoPalette.current.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (agent.archivedAt != null) Text("已归档", fontSize = 12.sp)
                        }
                    }
                    if (entries.isEmpty() && !loading && error == null) item { Text("没有匹配的历史会话", Modifier.padding(vertical = 20.dp)) }
                    if (next != null) item { TextButton(onClick = { cursor = next }, enabled = !loading) { Text("加载更多历史") } }
                } else {
                    items(rows, key = { it.key }) { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(when(row.type) { "user_message" -> "你"; "assistant_message" -> "助手"; "reasoning" -> "思考"; else -> row.title.ifBlank { "工具" } }, fontSize = 12.sp, color = LocalPaseoPalette.current.foregroundMuted)
                            SelectionContainer { MarkdownText(row.text.ifBlank { row.detail.toString() }) }
                        }
                    }
                    if (rows.isEmpty() && !loading && error == null) item { Text("没有历史记录", Modifier.padding(vertical = 20.dp)) }
                }
            }
        }
    }
}
