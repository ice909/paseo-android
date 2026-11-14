@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.paseo.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.paseo.android.AppViewModel
import sh.paseo.android.HostSettingsActivity
import sh.paseo.android.PairScanActivity
import sh.paseo.android.SettingsActivity
import sh.paseo.android.core.*

@Composable
fun PaseoApp(
    vm: AppViewModel,
    initialEditHostId: String? = null,
) {
    LocalNetworkPermission(vm)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.onAppForegrounded()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val initialized by vm.initialized.collectAsStateWithLifecycle()
    val contentState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var hostPickerOpen by rememberSaveable { mutableStateOf(false) }
    var editingHostId by rememberSaveable { mutableStateOf(initialEditHostId) }
    val editingHost = hosts.find { it.id == editingHostId }
    var directPairing by rememberSaveable { mutableStateOf(false) }
    var choosePairingMethod by rememberSaveable { mutableStateOf(false) }
    val welcome = screen == "hosts" && hosts.isEmpty()
    fun openSettings() {
        SettingsActivity.open(context)
        scope.launch { drawer.close() }
    }
    fun openPairScanner() {
        PairScanActivity.open(context)
        scope.launch { drawer.close() }
    }
    fun openPairing(direct: Boolean = false, chooseMethod: Boolean = false) {
        editingHostId = null
        directPairing = direct
        choosePairingMethod = chooseMethod
    }
    var showHostEditor by rememberSaveable { mutableStateOf(initialEditHostId != null) }
    var importSessions by rememberSaveable { mutableStateOf(false) }
    var importCwd by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf(false) }
    var explorer by rememberSaveable(state.selectedWorkspaceId) { mutableStateOf(false) }
    var explorerTab by rememberSaveable { mutableIntStateOf(0) }
    // Creation changes the workspace before its callback selects the launch view.
    // Keep the same state holder so that callback cannot write to a discarded one.
    var terminal by rememberSaveable { mutableStateOf(false) }
    var composerFullscreen by rememberSaveable(state.host?.id, state.selectedAgentId) { mutableStateOf(false) }
    var createAgent by rememberSaveable { mutableStateOf(false) }
    var newProject by rememberSaveable { mutableStateOf<String?>(null) }
    val workspace = state.selectedWorkspace
    fun navigate(destination: String) {
        scope.launch { drawer.close() }
        vm.navigate(destination)
        explorer = false; createAgent = false
    }
    fun openHostSettings(host: Host) {
        scope.launch { drawer.close() }
        HostSettingsActivity.open(context, host.id)
    }
    LaunchedEffect(screen, state.restoringWorkspace, state.selectedWorkspaceId, state.workspaceAgents.map { it.id }, state.selectedAgentId, terminal, createAgent) {
        if (screen == "chat" && state.restoringWorkspace == null && !terminal && !createAgent && state.selectedAgentId == null) state.workspaceAgents.firstOrNull()?.let(vm::openAgent)
    }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.error, state.connection) {
        val error = state.error ?: return@LaunchedEffect
        if (state.connection == ConnectionStatus.Reconnecting) return@LaunchedEffect
        delay(300)
        if (vm.state.value.error == error && vm.state.value.connection != ConnectionStatus.Reconnecting) {
            snackbar.showSnackbar(error)
            vm.repository.clearError()
        }
    }
    BackHandler(drawer.isOpen || explorer || createAgent || (screen == "hosts" && state.host != null)) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            explorer -> explorer = false
            createAgent -> createAgent = false
            else -> { vm.backToSessions(); terminal = false }
        }
    }
    if (importSessions) ImportSessionSheet(vm, state, importCwd,
        if (createAgent) workspace else null, onDismiss = { importSessions = false }, onImported = { agent ->
            importSessions = false; createAgent = false; terminal = false; vm.openAgent(agent)
        })
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = screen != "hosts" && !explorer, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = sidebarColor(), drawerContentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(max = 340.dp).fillMaxHeight()) {
            WorkspaceSidebar(vm, state, active = drawer.isOpen, onClose = { scope.launch { drawer.close() } }, onNew = { newProject = it; navigate("new") }, onOpen = {
                vm.openWorkspace(it); terminal = false; createAgent = false; explorer = false; scope.launch { drawer.close() }
            }, onHosts = {
                if (hosts.isEmpty()) navigate("hosts")
                else {
                    hostPickerOpen = true
                    scope.launch { drawer.close() }
                }
            }, onSettings = ::openSettings, onRefresh = { vm.action { vm.repository.refreshWorkspaces(); vm.repository.refreshAgents() } },
                onMore = { vm.action { vm.repository.refreshWorkspaces(loadMore = true) } })
        }
    }) {
        ExplorerOverlay(open = explorer, enabled = screen == "chat" && workspace != null && !createAgent,
            onOpen = { explorer = true }, onClose = { explorer = false }, workspace = {
        val compactWorkspace = screen == "chat" && workspace != null && !createAgent
        val floatingHeader = compactWorkspace && !terminal && state.selectedAgent != null
        val headerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
        val topOverlayInset = if (floatingHeader && !composerFullscreen) headerHeight else 0.dp
        val header: @Composable () -> Unit = {
            if (!welcome) {
                Column(Modifier.statusBarsPadding().padding(top = if (compactWorkspace) 8.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (compactWorkspace) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compactWorkspace) 8.dp else 0.dp)) {
                        IconButton(onClick = { scope.launch { drawer.open() } }, modifier = if (compactWorkspace) Modifier.size(32.dp) else Modifier) { PaseoGlyph(PaseoIcons.MobileMenu, "打开侧栏", Modifier.size(16.dp, 12.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
                        run {
                            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                                Text(if (screen == "hosts") "主机" else if (screen == "chat" && !createAgent) workspace?.name ?: "工作区" else "paseo",
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = if (compactWorkspace) 14.sp else MaterialTheme.typography.titleSmall.fontSize,
                                        lineHeight = if (compactWorkspace) 18.sp else MaterialTheme.typography.titleSmall.lineHeight,
                                        fontWeight = FontWeight.Medium, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (compactWorkspace && workspace != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(workspace.projectDisplayName, modifier = Modifier.weight(1f), style = PaseoUiText.copy(fontSize = 12.sp,
                                        lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                        color = LocalPaseoPalette.current.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    state.host?.let { host ->
                                        Text("·", style = PaseoUiText.copy(fontSize = 12.sp, lineHeight = 14.sp), color = LocalPaseoPalette.current.foregroundExtraMuted)
                                        PaseoGlyph(PaseoIcons.Server, null, Modifier.size(12.dp), LocalPaseoPalette.current.foregroundMuted)
                                        Text(host.displayName, modifier = Modifier.widthIn(max = 96.dp), style = PaseoUiText.copy(fontSize = 11.sp,
                                            lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                            color = LocalPaseoPalette.current.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Box(Modifier.size(6.dp).background(when (state.connection) {
                                            ConnectionStatus.Connected -> LocalPaseoPalette.current.statusDotSuccess
                                            ConnectionStatus.Connecting, ConnectionStatus.Reconnecting -> LocalPaseoPalette.current.statusDotWarning
                                            ConnectionStatus.Disconnected -> LocalPaseoPalette.current.statusDotDanger
                                        }, RoundedCornerShape(50)))
                                        Text(connectionLabel(state.connection), style = PaseoUiText.copy(fontSize = 11.sp,
                                            lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                            color = LocalPaseoPalette.current.foregroundMuted, maxLines = 1,
                                            modifier = Modifier.clickable(enabled = !state.connected) { vm.connect(host) }
                                                .semantics { contentDescription = "${host.displayName}，${connectionLabel(state.connection)}${if (state.connected) "" else "，点击重连"}" })
                                    }
                                }
                            }
                            if (screen != "hosts" && !compactWorkspace) TextButton(onClick = { importSessions = true }) {
                                PaseoGlyph(PaseoIcons.Import, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入会话")
                            }
                            if (screen == "hosts") IconButton(onClick = { openPairing(chooseMethod = true); showHostEditor = true }) { PaseoGlyph(PaseoIcons.Plus, "添加主机") }
                            if (compactWorkspace && workspace != null) {
                                IconButton(onClick = { picker = true }, modifier = Modifier.size(32.dp).semantics { contentDescription = "切换代理或终端" }) {
                                    Box(Modifier.sizeIn(minWidth = 16.dp).height(16.dp).border(1.dp, LocalPaseoPalette.current.foregroundMuted, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                                        Text((state.workspaceAgents.size + if (terminal) 1 else 0).toString(), fontSize = 10.sp, lineHeight = 12.sp, color = LocalPaseoPalette.current.foregroundMuted)
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { PaseoGlyph(PaseoIcons.Ellipsis, "工作区选项", Modifier.size(16.dp)) }
                                    PaseoDropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("重命名工作区") }, onClick = { menu = false; rename = "workspace" })
                                        DropdownMenuItem(text = { Text(if (workspace.pinnedAt == null) "置顶工作区" else "取消置顶") }, onClick = { menu = false; vm.action { vm.repository.pinWorkspace(workspace.id, workspace.pinnedAt == null) } })
                                        DropdownMenuItem(text = { Text("归档工作区") }, onClick = { menu = false; archive = "workspace" })
                                        if (state.selectedAgent != null) {
                                            HorizontalDivider()
                                            DropdownMenuItem(text = { Text("重命名会话") }, onClick = { menu = false; rename = "agent" })
                                            DropdownMenuItem(text = { Text("归档会话") }, onClick = { menu = false; archive = "agent" })
                                        }
                                        DropdownMenuItem(text = { Text("刷新") }, onClick = { menu = false; vm.action { vm.repository.refreshWorkspaces(); vm.repository.refreshAgents() }; state.selectedAgentId?.let(vm.repository::selectAgent) })
                                    }
                                }
                                IconButton(onClick = { explorer = true }, modifier = Modifier.size(32.dp)) { PaseoGlyph(PaseoIcons.Explorer, "打开文件和改动", Modifier.size(16.dp)) }
                            }
                            if (createAgent) IconButton(onClick = { createAgent = false }) { PaseoGlyph(PaseoIcons.X, "取消新建代理") }
                        }
                    }
                }
            }
        }
        Scaffold(contentWindowInsets = if (welcome) WindowInsets(0, 0, 0, 0)
            else if (floatingHeader) ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            else ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { SnackbarHost(snackbar) }, containerColor = if (compactWorkspace) LocalPaseoPalette.current.surfaceWorkspace else MaterialTheme.colorScheme.background,
            topBar = { if (!floatingHeader) header() }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                contentState.SaveableStateProvider(screen) { when {
                    !initialized -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    screen == "chat" && state.restoringWorkspace != null -> RestoringWorkspace(state,
                        onBack = vm::backToSessions, onHosts = { navigate("hosts") }, onRetry = { state.host?.let(vm::connect) })
                    screen == "chat" && workspace == null -> RestoringWorkspace(state,
                        onBack = vm::backToSessions, onHosts = { navigate("hosts") }, onRetry = { state.host?.let(vm::connect) })
                    welcome -> WelcomeScreen(
                        onScan = ::openPairScanner,
                        onDirect = { openPairing(direct = true); showHostEditor = true },
                        onPasteLink = { openPairing(); showHostEditor = true },
                        onSettings = ::openSettings)
                    screen == "hosts" -> HostsScreen(hosts, state, vm::connect, { openPairing(chooseMethod = true); showHostEditor = true }, { editingHostId = it.id; showHostEditor = true })
                    screen == "chat" && workspace != null && !createAgent -> if (terminal) TerminalPane(vm, workspace.context, state.connected, Modifier.fillMaxSize()) else ChatScreen(vm, state, snackbar, onCreateAgent = { createAgent = true }, topOverlayInset = topOverlayInset, expanded = composerFullscreen, onFullscreenChange = { composerFullscreen = it })
                    else -> NewWorkspaceScreen(vm, state, if (createAgent) workspace else null, newProject, onManageHosts = { navigate("hosts") }, onImportCwd = { importCwd = it }) { launchTerminal -> createAgent = false; terminal = launchTerminal }
                } }
                if (floatingHeader && !composerFullscreen) {
                    // Official native FloatingHeaderLayout: same-color opacity stops + 48dp overhang.
                    // Only the header receives input; the decorative fade lets timeline gestures pass through.
                    val backdrop = LocalPaseoPalette.current.surfaceWorkspace
                    Box(Modifier.fillMaxWidth().height(headerHeight + 48.dp).background(Brush.verticalGradient(
                        0f to backdrop, 0.57f to backdrop, 1f to backdrop.copy(alpha = 0f))))
                    header()
                }
            }
        }
        }, panel = {
            workspace?.let { current ->
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("改动", "文件").forEachIndexed { index, label ->
                            FilterChip(selected = explorerTab == index, onClick = { explorerTab = index }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { explorer = false }) { PaseoGlyph(PaseoIcons.X, "关闭文件面板") }
                    }
                    if (explorerTab == 0) DiffPane(vm, current.context, state.connected, Modifier.weight(1f))
                    else FilesPane(vm, current.context, state.connected, Modifier.weight(1f))
                }
            }
        })
    }
    if (picker && workspace != null) ModalBottomSheet(onDismissRequest = { picker = false },
        containerColor = LocalPaseoPalette.current.surface0, contentColor = LocalPaseoPalette.current.foreground) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("代理和终端", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            state.workspaceAgents.forEach { agent ->
                ListItem(colors = paseoListItemColors(), headlineContent = { Text(agent.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${agent.provider} · ${agentStatus(agent)}") },
                    leadingContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { PaseoGlyph(PaseoIcons.provider(agent.provider), modifier = Modifier.size(16.dp)); StatusDot(agent.pendingPermissions.isNotEmpty(), agent.running, agent.status == "error") } },
                    trailingContent = { if (agent.id == state.selectedAgentId && !terminal) PaseoGlyph(PaseoIcons.Check, null) },
                    modifier = Modifier.clickable { vm.openAgent(agent); terminal = false; picker = false })
            }
            ListItem(colors = paseoListItemColors(), headlineContent = { Text("新建代理") }, leadingContent = { PaseoGlyph(PaseoIcons.Plus, null) }, modifier = Modifier.clickable { createAgent = true; picker = false })
            ListItem(colors = paseoListItemColors(), headlineContent = { Text("终端") }, leadingContent = { PaseoGlyph(PaseoIcons.SquareTerminal, null) }, modifier = Modifier.clickable { terminal = true; picker = false })
        }
    }
    if (hostPickerOpen) ModalBottomSheet(onDismissRequest = { hostPickerOpen = false },
        containerColor = LocalPaseoPalette.current.surface0, contentColor = LocalPaseoPalette.current.foreground) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("切换主机", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            hosts.forEach { host ->
                ListItem(
                    colors = paseoListItemColors(),
                    headlineContent = { Text(host.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { PaseoGlyph(PaseoIcons.Server, null, tint = LocalPaseoPalette.current.foregroundMuted) },
                    trailingContent = { if (host.id == state.host?.id) PaseoGlyph(PaseoIcons.Check, "当前主机") },
                    modifier = Modifier.clickable {
                        hostPickerOpen = false
                        openHostSettings(host)
                    },
                )
            }
            ListItem(
                colors = paseoListItemColors(),
                headlineContent = { Text("添加主机") },
                leadingContent = { PaseoGlyph(PaseoIcons.Plus, null) },
                modifier = Modifier.clickable {
                    hostPickerOpen = false
                    openPairing(chooseMethod = true)
                    showHostEditor = true
                },
            )
        }
    }
    if (showHostEditor && (editingHostId == null || editingHost != null)) {
        if (editingHost != null) LaunchedEffect(editingHost.id) { openHostSettings(editingHost); showHostEditor = false; editingHostId = null }
        else PairingSheet(vm, direct = directPairing, chooseMethod = choosePairingMethod,
            onScanQr = { showHostEditor = false; openPairScanner() },
            onDismiss = { choosePairingMethod = false; showHostEditor = false })
    }
    PreviewUpdateDialog(vm)
    if (rename.isNotEmpty()) TextEntryDialog(if (rename == "workspace") "重命名工作区" else "重命名会话",
        if (rename == "workspace") workspace?.name.orEmpty() else state.selectedAgent?.displayTitle.orEmpty(), "名称", { rename = "" }) { name ->
        vm.action { if (rename == "workspace") workspace?.let { vm.repository.renameWorkspace(it.id, name) } else state.selectedAgentId?.let { vm.repository.renameAgent(it, name) }; rename = "" }
    }
    if (archive.isNotEmpty()) AlertDialog(onDismissRequest = { archive = "" }, title = { Text(if (archive == "workspace") "归档工作区？" else "归档会话？") },
        text = { Text("将从活动列表移除，历史记录保留在主机上。") },
        confirmButton = { TextButton(enabled = state.connected, onClick = { vm.action {
            if (archive == "workspace") { workspace?.let { vm.repository.archiveWorkspace(it.id) }; vm.navigate("sessions") }
            else state.selectedAgentId?.let { vm.repository.archiveAgent(it) }
            archive = ""
        } }) { Text("归档") } }, dismissButton = { TextButton(onClick = { archive = "" }) { Text("取消") } })
}

@Composable
private fun RestoringWorkspace(state: PaseoState, onBack: () -> Unit, onHosts: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        if (state.connection != ConnectionStatus.Disconnected) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text("正在恢复上次会话", style = MaterialTheme.typography.titleMedium)
        Text(when (state.connection) {
            ConnectionStatus.Connected -> "正在读取 ${state.host?.displayName.orEmpty()} 的工作区和会话…"
            ConnectionStatus.Disconnected -> "主机未连接。连接后将继续恢复，你也可以选择其他主机。"
            else -> "正在连接 ${state.host?.displayName.orEmpty()}，连接成功后将继续恢复。"
        }, color = LocalPaseoPalette.current.foregroundMuted)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("返回工作区") }
            TextButton(onClick = onHosts) { Text("管理主机") }
            if (state.connection == ConnectionStatus.Disconnected) TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun ConnectionStrip(state: PaseoState, onClick: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(7.dp).background(if (state.connected) LocalPaseoPalette.current.statusDotSuccess else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
            Text(state.host?.displayName ?: "尚未连接主机", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(when (state.connection) {
                ConnectionStatus.Connected -> "已连接"
                ConnectionStatus.Connecting -> "连接中…"
                ConnectionStatus.Reconnecting -> "正在重连…"
                else -> "未连接"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PaseoGlyph(PaseoIcons.ChevronDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostsScreen(hosts: List<Host>, state: PaseoState, onConnect: (Host) -> Unit, onAdd: () -> Unit, onEdit: (Host) -> Unit) {
    val palette = LocalPaseoPalette.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("主机", Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = palette.foregroundMuted) }
        items(hosts, key = { it.id }) { host ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface1)
                .border(1.dp, palette.border, RoundedCornerShape(8.dp)).clickable { onConnect(host) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaseoGlyph(PaseoIcons.Server, null, Modifier.size(18.dp), hostIdentityColor(host.color))
                Text(host.displayName, Modifier.weight(1f), style = PaseoUiText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (host.id == state.host?.id && state.connected) StatusDot()
                Box(Modifier.size(32.dp).clickable { onEdit(host) }, contentAlignment = Alignment.Center) {
                    PaseoGlyph(PaseoIcons.Settings, "${host.displayName} 设置", Modifier.size(18.dp), palette.foregroundMuted)
                }
            }
        }
        item { PaseoButton("添加主机", onAdd, Modifier.fillMaxWidth(), icon = PaseoIcons.Plus, primary = false) }
    }
}

fun agentStatus(agent: Agent): String = when {
    agent.pendingPermissions.isNotEmpty() -> "等待确认"
    agent.status == "initializing" -> "正在启动"
    agent.status == "running" -> "执行中"
    agent.status == "error" -> "发生错误"
    agent.status == "closed" -> "已关闭"
    else -> "就绪"
}

@Composable
fun EmptyState(icon: Int, title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PaseoGlyph(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { PaseoGlyph(if (expanded) PaseoIcons.ChevronUp else PaseoIcons.ChevronDown, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        ExposedDropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChoose(id); expanded = false }) }
        }
    }
}

@Composable
fun TextEntryDialog(title: String, initial: String, label: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun connectionLabel(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.Connecting -> "连接中…"
    ConnectionStatus.Reconnecting -> "正在重连…"
    else -> "未连接"
}
