@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.paseo.android.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.paseo.android.R
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
fun PaseoApp(vm: AppViewModel) {
    LocalNetworkPermission(vm)
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val initialized by vm.initialized.collectAsStateWithLifecycle()
    val contentState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val theme by vm.theme.collectAsStateWithLifecycle()
    var appearanceReturnScreen by rememberSaveable { mutableStateOf("hosts") }
    var editingHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingHost = hosts.find { it.id == editingHostId }
    var directPairing by rememberSaveable { mutableStateOf(false) }
    val welcome = screen == "hosts" && hosts.isEmpty()
    fun openAppearance() {
        appearanceReturnScreen = screen
        vm.screen.value = "appearance"
        scope.launch { drawer.close() }
    }
    fun openPairing(direct: Boolean = false) {
        editingHostId = null
        directPairing = direct
    }
    var showHostEditor by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf(false) }
    var explorer by rememberSaveable(state.selectedWorkspaceId) { mutableStateOf(false) }
    var explorerTab by rememberSaveable { mutableIntStateOf(0) }
    // Creation changes the workspace before its callback selects the launch view.
    // Keep the same state holder so that callback cannot write to a discarded one.
    var terminal by rememberSaveable { mutableStateOf(false) }
    var createAgent by rememberSaveable { mutableStateOf(false) }
    var newProject by rememberSaveable { mutableStateOf<String?>(null) }
    val workspace = state.selectedWorkspace
    fun navigate(destination: String) {
        scope.launch { drawer.close() }
        vm.screen.value = destination
        explorer = false; createAgent = false
    }
    LaunchedEffect(state.selectedWorkspaceId, state.workspaceAgents.map { it.id }, state.selectedAgentId, terminal, createAgent) {
        if (screen == "chat" && !terminal && !createAgent && state.selectedAgentId == null) state.workspaceAgents.firstOrNull()?.let(vm::openAgent)
    }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.repository.clearError() } }
    BackHandler(drawer.isOpen || screen == "scan" || screen == "appearance" || explorer || createAgent || screen == "chat" || (screen == "hosts" && state.host != null)) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            screen == "scan" -> { vm.cancelPairing(); vm.screen.value = "hosts" }
            screen == "appearance" -> vm.screen.value = appearanceReturnScreen
            explorer -> explorer = false
            createAgent -> createAgent = false
            else -> { vm.backToSessions(); terminal = false }
        }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = screen != "hosts" && screen != "appearance", drawerContent = {
        ModalDrawerSheet(drawerContainerColor = sidebarColor(), drawerContentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(max = 340.dp).fillMaxHeight()) {
            WorkspaceSidebar(state, onNew = { newProject = it; navigate("new") }, onOpen = {
                vm.openWorkspace(it); terminal = false; createAgent = false; explorer = false; scope.launch { drawer.close() }
            }, onHosts = { navigate("hosts") }, onAppearance = ::openAppearance, onRefresh = { vm.action { vm.repository.refreshWorkspaces(); vm.repository.refreshAgents() } },
                onMore = { vm.action { vm.repository.refreshWorkspaces(loadMore = true) } })
        }
    }) {
        Scaffold(contentWindowInsets = if (welcome || screen == "scan") WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { SnackbarHost(snackbar) }, containerColor = if (screen == "chat") LocalPaseoPalette.current.surfaceWorkspace else MaterialTheme.colorScheme.background,
            topBar = { if (!welcome && screen != "scan") {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (screen == "appearance") IconButton(onClick = { vm.screen.value = appearanceReturnScreen }) { PaseoGlyph(R.drawable.lucide_arrow_left, "返回", Modifier.size(18.dp), LocalPaseoPalette.current.foregroundMuted) }
                        else IconButton(onClick = { scope.launch { drawer.open() } }) { PaseoGlyph(PaseoIcons.MobileMenu, "打开侧栏", Modifier.size(16.dp, 12.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (explorer && screen != "appearance") {
                            listOf("改动", "文件").forEachIndexed { index, label ->
                                FilterChip(selected = explorerTab == index, onClick = { explorerTab = index }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { explorer = false }) { PaseoGlyph(PaseoIcons.X, "关闭文件面板") }
                        } else {
                            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                                Text(if (screen == "appearance") "外观" else if (screen == "hosts") "主机" else if (screen == "chat" && !createAgent) workspace?.name ?: "工作区" else "paseo",
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (screen == "chat" && workspace != null && !createAgent) Text(workspace.projectDisplayName,
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (screen == "hosts") IconButton(onClick = { openPairing(); showHostEditor = true }) { PaseoGlyph(PaseoIcons.Plus, "添加主机") }
                            if (screen == "chat" && workspace != null && !createAgent) {
                                Box {
                                    IconButton(onClick = { menu = true }) { PaseoGlyph(PaseoIcons.Ellipsis, "工作区选项") }
                                    DropdownMenu(menu, { menu = false }) {
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
                                IconButton(onClick = { explorer = true }) { PaseoGlyph(PaseoIcons.Explorer, "打开文件和改动") }
                            }
                            if (createAgent && screen != "appearance") IconButton(onClick = { createAgent = false }) { PaseoGlyph(PaseoIcons.X, "取消新建代理") }
                        }
                    }
                    if (!state.connected && screen == "chat" && !createAgent) ConnectionStrip(state) { state.host?.let(vm::connect) }
                    if (screen == "chat" && workspace != null && !explorer && !createAgent) Surface(onClick = { picker = true }, color = MaterialTheme.colorScheme.background) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaseoGlyph(if (terminal) PaseoIcons.SquareTerminal else PaseoIcons.provider(state.selectedAgent?.provider.orEmpty()), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (terminal) "终端" else state.selectedAgent?.displayTitle ?: "选择代理", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (terminal) "" else state.selectedAgent?.provider.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PaseoGlyph(PaseoIcons.ChevronDown, "切换代理或终端", Modifier.size(18.dp))
                        }
                    }
                }
            } }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                contentState.SaveableStateProvider(screen) { when {
                    screen == "appearance" -> AppearanceSettingsScreen(theme, vm::setTheme)
                    screen == "scan" -> PairScanScreen(vm) { vm.cancelPairing(); vm.screen.value = "hosts" }
                    !initialized -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    welcome -> WelcomeScreen(
                        onScan = { vm.screen.value = "scan" },
                        onDirect = { openPairing(direct = true); showHostEditor = true },
                        onPasteLink = { openPairing(); showHostEditor = true },
                        onSettings = ::openAppearance)
                    screen == "hosts" -> HostsScreen(hosts, state, vm::connect, { openPairing(); showHostEditor = true }, { editingHostId = it.id; showHostEditor = true }, vm::deleteHost)
                    screen == "chat" && workspace != null && explorer -> if (explorerTab == 0) DiffPane(vm, workspace.context, state.connected, Modifier.fillMaxSize()) else FilesPane(vm, workspace.context, state.connected, Modifier.fillMaxSize())
                    screen == "chat" && workspace != null && !createAgent -> if (terminal) TerminalPane(vm, workspace.context, state.connected, Modifier.fillMaxSize()) else ChatScreen(vm, state, onCreateAgent = { createAgent = true })
                    else -> NewWorkspaceScreen(vm, state, if (createAgent) workspace else null, newProject, onManageHosts = { navigate("hosts") }) { launchTerminal -> createAgent = false; terminal = launchTerminal }
                } }
            }
        }
    }
    if (picker && workspace != null) ModalBottomSheet(onDismissRequest = { picker = false }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("代理和终端", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            state.workspaceAgents.forEach { agent ->
                ListItem(headlineContent = { Text(agent.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${agent.provider} · ${agentStatus(agent)}") },
                    leadingContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { PaseoGlyph(PaseoIcons.provider(agent.provider), modifier = Modifier.size(16.dp)); StatusDot(agent.pendingPermissions.isNotEmpty(), agent.running, agent.status == "error") } },
                    trailingContent = { if (agent.id == state.selectedAgentId && !terminal) PaseoGlyph(PaseoIcons.Check, null) },
                    modifier = Modifier.clickable { vm.openAgent(agent); terminal = false; picker = false })
            }
            ListItem(headlineContent = { Text("新建代理") }, leadingContent = { PaseoGlyph(PaseoIcons.Plus, null) }, modifier = Modifier.clickable { createAgent = true; picker = false })
            ListItem(headlineContent = { Text("终端") }, leadingContent = { PaseoGlyph(PaseoIcons.SquareTerminal, null) }, modifier = Modifier.clickable { terminal = true; picker = false })
        }
    }
    if (showHostEditor) {
        if (editingHost != null) HostEditor(vm, editingHost) { showHostEditor = false }
        else PairingSheet(vm, direct = directPairing, onDismiss = { showHostEditor = false })
    }
    if (rename.isNotEmpty()) TextEntryDialog(if (rename == "workspace") "重命名工作区" else "重命名会话",
        if (rename == "workspace") workspace?.name.orEmpty() else state.selectedAgent?.displayTitle.orEmpty(), "名称", { rename = "" }) { name ->
        vm.action { if (rename == "workspace") workspace?.let { vm.repository.renameWorkspace(it.id, name) } else state.selectedAgentId?.let { vm.repository.renameAgent(it, name) }; rename = "" }
    }
    if (archive.isNotEmpty()) AlertDialog(onDismissRequest = { archive = "" }, title = { Text(if (archive == "workspace") "归档工作区？" else "归档会话？") },
        text = { Text("将从活动列表移除，历史记录保留在主机上。") },
        confirmButton = { TextButton(enabled = state.connected, onClick = { vm.action {
            if (archive == "workspace") { workspace?.let { vm.repository.archiveWorkspace(it.id) }; vm.screen.value = "sessions" }
            else state.selectedAgentId?.let { vm.repository.archiveAgent(it) }
            archive = ""
        } }) { Text("归档") } }, dismissButton = { TextButton(onClick = { archive = "" }) { Text("取消") } })
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
private fun HostsScreen(hosts: List<Host>, state: PaseoState, onConnect: (Host) -> Unit, onAdd: () -> Unit, onEdit: (Host) -> Unit, onDelete: (Host) -> Unit) {
    var deleting by remember { mutableStateOf<Host?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    PaseoGlyph(PaseoIcons.Server, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(if (hosts.isEmpty()) "将工作带在身边" else "你的主机", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("连接运行 Paseo 的电脑，继续与编码代理协作。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(hosts, key = { it.id }) { host ->
            OutlinedCard(onClick = { onConnect(host) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PaseoGlyph(PaseoIcons.Monitor, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(host.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(host.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host.id == state.host?.id && state.connected) Text("已连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { onEdit(host) }) { PaseoGlyph(PaseoIcons.Pencil, "编辑 ${host.displayName}", Modifier.size(20.dp)) }
                    IconButton(onClick = { deleting = host }) { PaseoGlyph(PaseoIcons.Trash2, "移除 ${host.displayName}", Modifier.size(20.dp)) }
                }
            }
        }
        item { FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp)) { PaseoGlyph(PaseoIcons.Plus, null); Spacer(Modifier.width(8.dp)); Text("添加主机") } }
        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("开始连接", style = MaterialTheme.typography.titleSmall)
                HelpStep("1", "在电脑上安装并启动 Paseo。")
                HelpStep("2", "在电脑上选择「配对设备」，复制配对链接。")
                HelpStep("3", "点击「添加主机」，粘贴链接并连接。")
            }
        }
    }
    deleting?.let { host -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除 ${host.displayName}？") }, text = { Text("移除本机保存的连接配置。") },
        confirmButton = { TextButton(onClick = { onDelete(host); deleting = null }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun HelpStep(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Suppress("DEPRECATION")
@Composable
private fun HostEditor(vm: AppViewModel, host: Host?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(host?.takeUnless { it.automaticName }?.name.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(host?.relay?.pairingLink() ?: host?.url.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(host?.password.orEmpty()) }
    var manual by rememberSaveable { mutableStateOf(host != null && host.relay == null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val saving by vm.pairingBusy.collectAsStateWithLifecycle()
    val error by vm.pairingError.collectAsStateWithLifecycle()
    val needsPassword by vm.pairingPasswordNeeded.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    fun dismiss() { vm.cancelPairing(); onDismiss() }
    fun changeAddress(text: String) { address = text; password = ""; vm.clearPairingError(resetPassword = true) }
    LaunchedEffect(Unit) { vm.clearPairingError(resetPassword = true) }
    AlertDialog(onDismissRequest = ::dismiss, title = { Text(if (manual) "手动连接" else "粘贴配对链接") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (manual) "使用电脑的局域网、VPN 或 Tailscale 地址。" else "复制电脑上 Paseo 提供的配对链接，粘贴后即可连接。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(address, ::changeAddress, label = { Text(if (manual) "主机地址" else "配对链接") },
                placeholder = { Text(if (manual) "192.168.1.10:6767" else "https://app.paseo.sh/#offer=…") },
                modifier = Modifier.fillMaxWidth(), maxLines = 3, isError = error != null && !needsPassword, enabled = !saving,
                trailingIcon = { IconButton(onClick = { clipboard.getText()?.text?.let(::changeAddress) }, enabled = !saving) { PaseoGlyph(PaseoIcons.ClipboardPaste, "从剪贴板粘贴链接") } })
            if (manual || needsPassword) OutlinedTextField(password, { password = it; vm.clearPairingError() }, label = { Text(if (needsPassword) "主机密码" else "密码（可选）") }, singleLine = true, enabled = !saving,
                modifier = Modifier.fillMaxWidth(), visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { visible = !visible }) { PaseoGlyph(if (visible) PaseoIcons.EyeOff else PaseoIcons.Eye, "显示或隐藏密码") } })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (saving) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("正在验证并连接主机…", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { more = !more }, enabled = !saving) { Text(if (more) "收起更多选项" else "更多选项") }
            if (more) {
                OutlinedTextField(name, { name = it }, label = { Text("主机名称（可选）") }, supportingText = { Text("留空时使用电脑的名称") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { manual = !manual; vm.clearPairingError(resetPassword = true) }, enabled = !saving) { Text(if (manual) "使用配对链接" else "手动输入地址") }
            }
        } },
        confirmButton = { TextButton(enabled = address.isNotBlank() && !saving, onClick = {
            vm.saveHost(host?.id, name, address, password, onDismiss)
        }) { Text(if (saving) "连接中…" else "连接") } },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("取消") } })
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
