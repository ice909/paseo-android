package sh.paseo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.paseo.android.core.*

@Composable
fun sidebarColor(): Color = LocalPaseoPalette.current.surfaceSidebar

@Composable
fun StatusDot(waiting: Boolean = false, running: Boolean = false, failed: Boolean = false) {
    Box(Modifier.size(6.dp).background(when {
        waiting -> LocalPaseoPalette.current.statusDotWarning
        failed -> LocalPaseoPalette.current.statusDotDanger
        running -> LocalPaseoPalette.current.statusDotRunning
        else -> MaterialTheme.colorScheme.outline
    }, RoundedCornerShape(50)))
}

@Composable
fun WorkspaceSidebar(state: PaseoState, onNew: (String?) -> Unit, onOpen: (Workspace) -> Unit,
    onHosts: () -> Unit, onAppearance: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit) {
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val filtered = remember(state.workspaces, query) { state.workspaces.filter {
        "${it.name} ${it.projectDisplayName} ${it.cwd}".contains(query, true)
    } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("paseo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = state.connected) { Icon(Icons.Outlined.Refresh, "刷新工作区", Modifier.size(19.dp)) }
        }
        SidebarAction("新建工作区", Icons.Outlined.Add) { onNew(null) }
        SidebarAction("搜索工作区", Icons.Outlined.Search) { search = !search; if (!search) query = "" }
        if (search) OutlinedTextField(query, { query = it }, placeholder = { Text("搜索项目或工作区") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("工作区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { onNew(null) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.CreateNewFolder, "添加项目", Modifier.size(17.dp)) }
        }
        if (!state.catalogLoaded) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.connected) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("连接主机以读取工作区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            state.projects.forEach { project ->
                val workspaces = filtered.filter { it.projectId == project.projectId }
                if (workspaces.isNotEmpty() || query.isBlank()) {
                    item("project:${project.projectId}") {
                        Row(Modifier.fillMaxWidth().clickable { collapsed[project.projectId] = collapsed[project.projectId] != true }.padding(start = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (collapsed[project.projectId] == true) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.padding(horizontal = 7.dp).size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(project.projectDisplayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onNew(project.projectId) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Add, "在 ${project.projectDisplayName} 新建工作区", Modifier.size(18.dp)) }
                        }
                    }
                    if (collapsed[project.projectId] != true) {
                        items(workspaces, key = { "workspace:${it.id}" }) { workspace ->
                            val selected = workspace.id == state.selectedWorkspaceId
                            Surface(onClick = { onOpen(workspace) }, shape = RoundedCornerShape(7.dp), color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else sidebarColor()) {
                                Row(Modifier.fillMaxWidth().padding(start = 39.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Icon(if (workspace.workspaceKind == "worktree") Icons.Outlined.CallSplit else Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(workspace.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (workspace.pinnedAt != null) Icon(Icons.Outlined.PushPin, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    StatusDot(workspace.status == "needs_input", workspace.status == "running", workspace.status == "failed")
                                }
                            }
                        }
                        if (workspaces.isEmpty()) item("empty:${project.projectId}") {
                            TextButton(onClick = { onNew(project.projectId) }, modifier = Modifier.padding(start = 30.dp)) { Text("新建第一个工作区", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            if (state.projects.isEmpty()) item { Text("还没有项目", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (query.isNotBlank() && filtered.isEmpty()) item { Text("没有匹配的工作区", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.nextWorkspaceCursor != null) item { TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多工作区") } }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ConnectionStrip(state, onHosts)
        SidebarAction("管理主机", Icons.Outlined.Dns, onHosts)
        SidebarAction("设置", Icons.Outlined.Settings, onAppearance)
    }
}

@Composable
private fun SidebarAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
