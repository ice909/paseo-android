package sh.paseo.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import sh.paseo.android.AppViewModel
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
fun WorkspaceSidebar(vm: AppViewModel, state: PaseoState, active: Boolean, onClose: () -> Unit,
    onNew: (String?) -> Unit, onOpen: (Workspace) -> Unit,
    onHosts: () -> Unit, onAppearance: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit) {
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var preferences by remember { mutableStateOf(false) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var usage by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var edit by remember(state.host?.id) { mutableStateOf<Workspace?>(null) }
    var archive by remember(state.host?.id) { mutableStateOf<Workspace?>(null) }
    val collapsed = remember(state.host?.id) { mutableStateMapOf<String, Boolean>() }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val palette = LocalPaseoPalette.current
    var projectMetadata by remember(state.host?.id, state.serverId) { mutableStateOf<Map<String, Project>>(emptyMap()) }
    LaunchedEffect(state.host?.id, state.serverId, state.connected, state.features, active, state.projects) {
        if (!state.connected || !active) return@LaunchedEffect
        try { projectMetadata = vm.repository.sidebarProjects().associateBy { it.projectId } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Older/offline hosts retain the official placement-key fallback. */ }
    }
    val filtered = remember(state.workspaces, query) { state.workspaces.filter {
        "${it.name} ${it.projectDisplayName} ${it.cwd}".contains(query, true)
    } }
    CompositionLocalProvider(LocalContentColor provides palette.foregroundMuted) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SidebarAction("新建工作区", PaseoIcons.Plus, Modifier.weight(1f)) { onNew(null) }
                SidebarIcon(PaseoIcons.X, "关闭侧栏", onClose, touchSize = 32)
            }
            Spacer(Modifier.height(2.dp))
            SidebarAction("历史", PaseoIcons.History, Modifier.padding(horizontal = 8.dp)) { history = true }
            HorizontalDivider(Modifier.padding(top = 6.dp), color = palette.border)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Workspaces", fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                Box {
                    SidebarIcon(PaseoIcons.SlidersHorizontal, "工作区显示设置", { preferences = true }, size = 16)
                    PaseoDropdownMenu(expanded = preferences, onDismissRequest = { preferences = false }) {
                        DropdownMenuItem(text = { Text("搜索工作区") }, onClick = { preferences = false; search = !search; if (!search) query = "" })
                        DropdownMenuItem(text = { Text(if (alphabetical) "按最近活动排序" else "按项目名称排序") }, onClick = { alphabetical = !alphabetical; preferences = false })
                        DropdownMenuItem(text = { Text("折叠所有项目") }, onClick = { state.projects.forEach { collapsed[it.projectId] = true }; preferences = false })
                        DropdownMenuItem(text = { Text("展开所有项目") }, onClick = { collapsed.clear(); preferences = false })
                        DropdownMenuItem(text = { Text("刷新工作区") }, enabled = state.connected, onClick = { preferences = false; onRefresh() })
                    }
                }
            }
            if (search) OutlinedTextField(query, { query = it }, placeholder = { Text("搜索项目或工作区") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            if (!state.catalogLoaded) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.connected) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("连接主机以读取工作区", fontSize = 12.sp)
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                val projects = if (alphabetical) state.projects.sortedBy { it.projectDisplayName.lowercase() } else state.projects
                projects.forEach { project ->
                    val workspaces = filtered.filter { it.projectId == project.projectId }
                    if (workspaces.isNotEmpty() || query.isBlank()) {
                        item("project:${project.projectId}") {
                            var menu by remember { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { collapsed[project.projectId] = collapsed[project.projectId] != true }
                                    .padding(start = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.size(20.dp).background(Color(projectIdentityColor((projectMetadata[project.projectId] ?: project).identityKey(state.serverId))), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                                        Text(project.projectDisplayName.take(1).uppercase(), fontSize = 12.sp, lineHeight = 16.sp, color = Color.White)
                                    }
                                    Text(project.projectDisplayName, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                SidebarIcon(PaseoIcons.Plus, "在 ${project.projectDisplayName} 新建工作区", { onNew(project.projectId) }, size = 16)
                                Box {
                                    SidebarIcon(PaseoIcons.EllipsisVertical, "${project.projectDisplayName} 项目菜单", { menu = true }, size = 16)
                                    PaseoDropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("新建工作区") }, onClick = { menu = false; onNew(project.projectId) })
                                        DropdownMenuItem(text = { Text(if (collapsed[project.projectId] == true) "展开项目" else "折叠项目") }, onClick = { menu = false; collapsed[project.projectId] = collapsed[project.projectId] != true })
                                        DropdownMenuItem(text = { Text("复制项目路径") }, onClick = { menu = false; clipboard.setText(AnnotatedString(project.projectRootPath)) })
                                    }
                                }
                            }
                        }
                        if (collapsed[project.projectId] != true) {
                            items(workspaces, key = { "workspace:${it.id}" }) { workspace ->
                                var menu by remember { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth().background(if (workspace.id == state.selectedWorkspaceId) (if (palette.dark) palette.surface2 else palette.surface4) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { onOpen(workspace) }.padding(start = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { SidebarWorkspaceStatus(workspace.status, active) }
                                    Spacer(Modifier.width(8.dp))
                                    Text(workspace.name, Modifier.weight(1f).padding(vertical = 12.dp), fontSize = 14.sp, lineHeight = 20.sp, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (workspace.pinnedAt != null) PaseoGlyph(PaseoIcons.Pin, "已固定", Modifier.size(12.dp))
                                    Box {
                                        SidebarIcon(PaseoIcons.EllipsisVertical, "${workspace.name} 工作区菜单", { menu = true }, size = 16)
                                        PaseoDropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text("重命名") }, enabled = state.connected, onClick = { menu = false; edit = workspace })
                                            DropdownMenuItem(text = { Text(if (workspace.pinnedAt == null) "固定" else "取消固定") }, enabled = state.connected,
                                                onClick = { menu = false; vm.action { vm.repository.pinWorkspace(workspace.id, workspace.pinnedAt == null) } })
                                            DropdownMenuItem(text = { Text("复制工作目录") }, onClick = { menu = false; clipboard.setText(AnnotatedString(workspace.cwd)) })
                                            DropdownMenuItem(text = { Text("归档") }, enabled = state.connected, onClick = { menu = false; archive = workspace })
                                        }
                                    }
                                }
                            }
                        }
                        item("space:${project.projectId}") { Spacer(Modifier.height(16.dp)) }
                    }
                }
                if (state.projects.isEmpty()) item { Text("还没有项目", Modifier.padding(16.dp), fontSize = 14.sp) }
                if (query.isNotBlank() && filtered.isEmpty()) item { Text("没有匹配的工作区", Modifier.padding(16.dp), fontSize = 14.sp) }
                if (state.nextWorkspaceCursor != null) item { TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多工作区") } }
            }
            HorizontalDivider(color = palette.border)
            SidebarUsage(vm.repository, state, active, usage) { usage = it }
            HorizontalDivider(color = palette.border)
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SidebarIcon(PaseoIcons.FolderPlus, "添加项目", { onNew(null) })
                SidebarIcon(PaseoIcons.Gauge, "用量", { usage = true })
                SidebarIcon(PaseoIcons.Server, "管理主机，${if (state.connected) "已连接" else "未连接"}", onHosts)
                Spacer(Modifier.weight(1f))
                SidebarIcon(PaseoIcons.CircleHelp, "帮助", { help = true })
                SidebarIcon(PaseoIcons.Settings, "设置", onAppearance)
            }
        }
    }
    if (history) SidebarHistory(vm, state, onDismiss = { history = false }, onOpen = { workspace, agent ->
        history = false; onOpen(workspace); vm.openAgent(agent)
    })
    edit?.let { workspace ->
        var name by remember(workspace.id) { mutableStateOf(workspace.name) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("重命名工作区") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = { edit = null; vm.action { vm.repository.renameWorkspace(workspace.id, name.trim()) } }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("取消") } })
    }
    archive?.let { workspace ->
        AlertDialog(onDismissRequest = { archive = null }, title = { Text("归档工作区？") }, text = { Text(workspace.name) },
            confirmButton = { TextButton(onClick = { archive = null; vm.action { vm.repository.archiveWorkspace(workspace.id) } }) { Text("归档") } },
            dismissButton = { TextButton(onClick = { archive = null }) { Text("取消") } })
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Paseo 帮助") },
        text = { Text("在主机上运行 Paseo，复制配对链接到管理主机页。侧栏可新建工作区、查看历史会话，以及查看主机账户的用量。") },
        confirmButton = { TextButton(onClick = { help = false; uri.openUri("https://paseo.sh") }) { Text("打开官网") } },
        dismissButton = { TextButton(onClick = { help = false }) { Text("关闭") } })
}

@Composable
private fun SidebarAction(text: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaseoGlyph(icon, null, Modifier.size(18.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarIcon(icon: Int, label: String, onClick: () -> Unit, size: Int = 20, touchSize: Int = 44) {
    IconButton(onClick = onClick, modifier = Modifier.size(touchSize.dp)) { PaseoGlyph(icon, label, Modifier.size(size.dp)) }
}

@Composable
private fun SidebarWorkspaceStatus(status: String, active: Boolean) {
    val palette = LocalPaseoPalette.current
    if (status == "needs_input" || status == "failed") {
        PaseoGlyph(PaseoIcons.CircleAlert, if (status == "needs_input") "等待输入" else "失败", Modifier.size(14.dp),
            if (status == "failed") palette.statusDotDanger else palette.statusDotWarning)
    } else if (status in setOf("running", "loading")) {
        val rotation = if (active) {
            val transition = rememberInfiniteTransition(label = "workspace status")
            val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "workspace rotation")
            angle
        } else 0f
        val color = palette.statusDotRunning
        Canvas(Modifier.size(12.dp).semantics { contentDescription = "正在运行" }) {
            drawCircle(color, radius = 3.dp.toPx())
            drawCircle(color.copy(alpha = .3f), radius = size.minDimension / 2 - .75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            drawArc(color.copy(alpha = .9f), rotation, 90f, false, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    } else Box(Modifier.size(6.dp).background(if (status == "attention") palette.statusDotSuccess else palette.foregroundExtraMuted.copy(alpha = .3f), RoundedCornerShape(50)))
}
