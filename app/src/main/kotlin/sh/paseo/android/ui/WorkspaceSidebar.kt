package sh.paseo.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
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

private const val INITIAL_PROJECT_WORKSPACE_LIMIT = 7

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
    onHosts: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit) {
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var preferences by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sidebarPreferences = remember { SidebarPreferences(context.applicationContext) }
    val sortSupported = state.features.bool("workspaceSortTimes")
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dragging by remember(state.host?.id) { mutableStateOf<String?>(null) }
    val hostKey = state.serverId.ifBlank { state.host?.id.orEmpty() }
    val sortedWorkspaces = state.workspaces.groupBy { it.projectId }.mapValues { (project, workspaces) ->
        sortSidebarWorkspaces(workspaces, sidebarPreferences.sort, sortSupported, sidebarPreferences.order(hostKey, project))
    }
    val currentWorkspaces by rememberUpdatedState(state.workspaces)
    val currentSorted by rememberUpdatedState(sortedWorkspaces)
    fun move(from: String, to: String) {
        val source = currentWorkspaces.find { it.id == from } ?: return
        val target = currentWorkspaces.find { it.id == to } ?: return
        if (source.projectId != target.projectId || (source.pinnedAt == null) != (target.pinnedAt == null)) return
        val order = currentSorted[source.projectId].orEmpty().map { it.id }
        val moved = moveSidebarWorkspace(order, from, to)
        val hidden = sidebarPreferences.order(hostKey, source.projectId).filterNot { it in moved }
        sidebarPreferences.reorder(hostKey, source.projectId, moved + hidden)
    }
    val moveCurrent by rememberUpdatedState(::move)
    var history by rememberSaveable { mutableStateOf(false) }
    var usage by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var edit by remember(state.host?.id) { mutableStateOf<Workspace?>(null) }
    var archive by remember(state.host?.id) { mutableStateOf<Workspace?>(null) }
    val collapsed = remember(state.host?.id) { mutableStateMapOf<String, Boolean>() }
    val expandedWorkspaces = remember(state.host?.id) { mutableStateMapOf<String, Boolean>() }
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
    val selectedProjects = sidebarPreferences.projectFilters.intersect(state.projects.map { it.projectId }.toSet())
    val visibleWorkspaces = filtered.filter { (sidebarPreferences.showBackground || !it.background) &&
        (selectedProjects.isEmpty() || it.projectId in selectedProjects) }
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
                SidebarIcon(PaseoIcons.Settings2, "显示偏好", { preferences = true }, size = 14, touchSize = 28)
            }
            if (search) OutlinedTextField(query, { query = it }, placeholder = { Text("搜索项目或工作区") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            if (!state.catalogLoaded) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.connected) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("连接主机以读取工作区", fontSize = 12.sp)
            } else LazyColumn(Modifier.weight(1f).pointerInput(sidebarPreferences.sort, sidebarPreferences.grouping, hostKey) {
                if (sidebarPreferences.sort != WorkspaceSort.Manual || sidebarPreferences.grouping != "project") return@pointerInput
                var pointerY = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        pointerY = offset.y
                        dragging = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            offset.y >= it.offset && offset.y < it.offset + it.size
                        }?.key?.toString()?.takeIf { it.startsWith("workspace:") }?.removePrefix("workspace:")
                    },
                    onDragEnd = { dragging = null }, onDragCancel = { dragging = null },
                    onDrag = { change, amount ->
                        val id = dragging
                        if (id != null) {
                            change.consume()
                            pointerY += amount.y
                            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                pointerY >= it.offset && pointerY < it.offset + it.size
                            }?.key?.toString()?.takeIf { it.startsWith("workspace:") }
                                ?.removePrefix("workspace:")?.takeIf { it != id }?.let { target ->
                                    val project = currentWorkspaces.find { it.id == id }?.projectId
                                    val peers = currentSorted[project].orEmpty()
                                    val from = peers.indexOfFirst { it.id == id }
                                    val to = peers.indexOfFirst { it.id == target }
                                    if ((amount.y > 0 && to > from) || (amount.y < 0 && to in 0 until from)) moveCurrent(id, target)
                                }
                            val edge = 48.dp.toPx()
                            val end = listState.layoutInfo.viewportEndOffset
                            val scroll = when {
                                pointerY < edge -> -edge / 3
                                pointerY > end - edge -> edge / 3
                                else -> 0f
                            }
                            if (scroll != 0f) scope.launch { listState.scrollBy(scroll) }
                        }
                    },
                )
            }, state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                if (sidebarPreferences.grouping == "status") {
                    val workspaceOrder = sortedWorkspaces.values.flatten()
                        .filter { workspace -> visibleWorkspaces.any { it.id == workspace.id } }
                    workspaceOrder.groupBy { it.status }.entries.sortedBy { statusGroupOrder(it.key) }
                        .forEach { (status, workspaces) ->
                            item("status:$status") {
                                Text(statusGroupLabel(status), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp),
                                    fontSize = 14.sp, color = palette.foregroundMuted)
                            }
                            items(workspaces, key = { "workspace:${it.id}" }) { workspace ->
                                SidebarWorkspaceRow(workspace, workspace.id == state.selectedWorkspaceId, workspace.id == dragging,
                                    workspaceTitle(workspace, sidebarPreferences.titleSource),
                                    workspaceDetails(workspace, sidebarPreferences.rowItems, null),
                                    active, sidebarPreferences.sort, emptyList(), onOpen,
                                    onRename = { edit = workspace },
                                    onPin = { vm.action { vm.repository.pinWorkspace(workspace.id, workspace.pinnedAt == null) } },
                                    onCopyPath = { clipboard.setText(AnnotatedString(workspace.cwd)) },
                                    onArchive = { archive = workspace }, connected = state.connected, hostBadge = state.host?.takeIf { "host" in sidebarPreferences.rowItems })
                            }
                        }
                } else {
                    val projects = state.projects
                        .filter { selectedProjects.isEmpty() || it.projectId in selectedProjects }
                        .sortedBy { it.projectDisplayName.lowercase() }
                    projects.forEach { project ->
                        val allWorkspaces = sortedWorkspaces[project.projectId].orEmpty()
                            .filter { workspace -> visibleWorkspaces.any { it.id == workspace.id } }
                        val initiallyVisibleWorkspaces = allWorkspaces.filterIndexed { index, workspace ->
                            index < INITIAL_PROJECT_WORKSPACE_LIMIT || workspace.status != "done"
                        }
                        val projectExpanded = expandedWorkspaces[project.projectId] == true
                        val workspaces = if (projectExpanded) allWorkspaces else initiallyVisibleWorkspaces
                        if (allWorkspaces.isNotEmpty() || query.isBlank()) {
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
                                    val peers = workspaces.filter { (it.pinnedAt == null) == (workspace.pinnedAt == null) }
                                    SidebarWorkspaceRow(workspace, workspace.id == state.selectedWorkspaceId, workspace.id == dragging,
                                        workspaceTitle(workspace, sidebarPreferences.titleSource),
                                        workspaceDetails(workspace, sidebarPreferences.rowItems, null),
                                        active, sidebarPreferences.sort, peers, onOpen,
                                        onMove = ::move,
                                        onRename = { edit = workspace },
                                        onPin = { vm.action { vm.repository.pinWorkspace(workspace.id, workspace.pinnedAt == null) } },
                                        onCopyPath = { clipboard.setText(AnnotatedString(workspace.cwd)) },
                                        onArchive = { archive = workspace }, connected = state.connected, hostBadge = state.host?.takeIf { "host" in sidebarPreferences.rowItems })
                                }
                                if (initiallyVisibleWorkspaces.size < allWorkspaces.size) {
                                    item("workspace-toggle:${project.projectId}") {
                                        val label = if (projectExpanded) "收起" else "显示更多"
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                                                expandedWorkspaces[project.projectId] = !projectExpanded
                                            }.padding(start = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                PaseoGlyph(
                                                    if (projectExpanded) PaseoIcons.ChevronUp else PaseoIcons.ChevronDown,
                                                    null,
                                                    Modifier.size(16.dp),
                                                    tint = palette.foregroundMuted,
                                                )
                                            }
                                            Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = palette.foregroundMuted)
                                        }
                                    }
                                }
                            }
                            item("space:${project.projectId}") { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
                if (state.projects.isEmpty()) item { Text("还没有项目", Modifier.padding(16.dp), fontSize = 14.sp) }
                if (query.isNotBlank() && visibleWorkspaces.isEmpty()) item { Text("没有匹配的工作区", Modifier.padding(16.dp), fontSize = 14.sp) }
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
                SidebarIcon(PaseoIcons.Settings, "设置", onSettings)
            }
        }
    }
    if (preferences) SidebarPreferencesSheet(sidebarPreferences,
        projects = state.projects.map { it.projectId to it.projectDisplayName }.distinctBy { it.first }.sortedBy { it.second.lowercase() },
        automaticSortSupported = sortSupported, onDismiss = { preferences = false })
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
private fun SidebarWorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    dragging: Boolean,
    title: String,
    details: String,
    active: Boolean,
    sort: WorkspaceSort,
    peers: List<Workspace>,
    onOpen: (Workspace) -> Unit,
    onMove: ((String, String) -> Unit)? = null,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onCopyPath: () -> Unit,
    onArchive: () -> Unit,
    connected: Boolean,
    hostBadge: Host? = null,
) {
    val palette = LocalPaseoPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected || dragging) (if (palette.dark) palette.surface2 else palette.surface4) else Color.Transparent, RoundedCornerShape(8.dp))
            .semantics {
                if (sort == WorkspaceSort.Manual && onMove != null) {
                    val index = peers.indexOfFirst { it.id == workspace.id }
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction("上移") { onMove(workspace.id, peers[index - 1].id); true })
                        if (index in 0 until peers.lastIndex) add(CustomAccessibilityAction("下移") { onMove(workspace.id, peers[index + 1].id); true })
                    }
                }
            }
            .clickable { onOpen(workspace) }
            .padding(start = 8.dp)
            .heightIn(min = if (details.isBlank()) 44.dp else 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { SidebarWorkspaceStatus(workspace.status, active) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details.isNotBlank() || hostBadge?.badgeDisplay?.let { it != "hidden" } == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (details.isNotBlank()) Text(details, Modifier.weight(1f, fill = false), fontSize = 11.sp,
                        lineHeight = 14.sp, color = palette.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    hostBadge?.let { HostIdentityBadge(it) }
                }
            }
        }
        if (workspace.pinnedAt != null) PaseoGlyph(PaseoIcons.Pin, "已固定", Modifier.size(12.dp))
        Box {
            SidebarIcon(PaseoIcons.EllipsisVertical, "${workspace.name} 工作区菜单", { menu = true }, size = 16)
            PaseoDropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("重命名") }, enabled = connected, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(if (workspace.pinnedAt == null) "固定" else "取消固定") }, enabled = connected,
                    onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text("复制工作目录") }, onClick = { menu = false; onCopyPath() })
                DropdownMenuItem(text = { Text("归档") }, enabled = connected, onClick = { menu = false; onArchive() })
            }
        }
    }
}

private fun workspaceTitle(workspace: Workspace, source: String): String =
    if (source == "branch") workspace.gitRuntime?.string("branch")?.takeIf { it.isNotBlank() } ?: workspace.title ?: workspace.name
    else workspace.title?.takeIf { it.isNotBlank() } ?: workspace.name

private fun workspaceDetails(workspace: Workspace, items: Set<String>, hostName: String?): String =
    buildList {
        if ("branch" in items) workspace.gitRuntime?.string("branch")?.takeIf { it.isNotBlank() }?.let(::add)
        if ("project" in items) add(workspace.projectDisplayName)
        if ("host" in items) hostName?.takeIf { it.isNotBlank() }?.let(::add)
    }.distinct().joinToString(" · ")

private fun statusGroupOrder(status: String): Int = when (status) {
    "needs_input" -> 0
    "failed" -> 1
    "ready_to_review" -> 2
    "running", "loading" -> 3
    "done" -> 4
    else -> 5
}

private fun statusGroupLabel(status: String): String = when (status) {
    "needs_input" -> "需要输入"
    "failed" -> "失败"
    "ready_to_review" -> "待查看"
    "running", "loading" -> "运行中"
    "done" -> "已完成"
    else -> "其他"
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
