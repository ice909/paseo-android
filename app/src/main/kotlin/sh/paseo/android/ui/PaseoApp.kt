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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
fun PaseoApp(vm: AppViewModel) {
    LocalNetworkPermission(vm)
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val initialized by vm.initialized.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showHostEditor by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<Host?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.repository.clearError() }
    }
    BackHandler(screen == "chat" || (screen == "hosts" && state.host != null)) {
        if (screen == "chat") vm.backToSessions() else vm.screen.value = "sessions"
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (screen == "chat") Column {
                        Text(state.selectedAgent?.displayTitle ?: "会话", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(state.selectedAgent?.let { listOfNotNull(it.provider, it.model).joinToString(" · ") }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary)
                        Text("paseo", fontWeight = FontWeight.Bold)
                        Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("NATIVE", modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    if (screen == "chat" || (screen == "hosts" && state.host != null)) IconButton(onClick = {
                        if (screen == "chat") vm.backToSessions() else vm.screen.value = "sessions"
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    when (screen) {
                        "hosts" -> IconButton(onClick = { editingHost = null; showHostEditor = true }) { Icon(Icons.Outlined.Add, "添加主机") }
                        "sessions" -> {
                            IconButton(onClick = { vm.action { vm.repository.refreshAgents() } }, enabled = state.connected) { Icon(Icons.Outlined.Refresh, "刷新会话") }
                            IconButton(onClick = { vm.screen.value = "hosts" }) { Icon(Icons.Outlined.Dns, "管理主机") }
                        }
                        "chat" -> Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "会话选项") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("刷新消息") }, onClick = { menu = false; state.selectedAgentId?.let(vm.repository::selectAgent) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renaming = true }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                                DropdownMenuItem(text = { Text("归档会话") }, onClick = { menu = false; archiving = true }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (screen == "sessions") ExtendedFloatingActionButton(onClick = { if (state.connected) showCreate = true },
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("新会话") }, containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.semantics { contentDescription = "新会话" })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!initialized) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else when (screen) {
                "hosts" -> HostsScreen(hosts, state, onConnect = vm::connect,
                    onAdd = { editingHost = null; showHostEditor = true },
                    onEdit = { editingHost = it; showHostEditor = true }, onDelete = vm::deleteHost)
                "chat" -> ChatScreen(vm, state)
                else -> SessionsScreen(state, vm::openAgent, { vm.screen.value = "hosts" }, { vm.action { vm.repository.refreshAgents(loadMore = true) } })
            }
        }
    }
    if (showHostEditor) HostEditor(vm, editingHost) { showHostEditor = false }
    if (showCreate) CreateAgentDialog(vm, state) { showCreate = false }
    if (renaming) TextEntryDialog("重命名会话", state.selectedAgent?.displayTitle.orEmpty(), "名称", { renaming = false }) { name ->
        vm.action { state.selectedAgentId?.let { vm.repository.renameAgent(it, name) }; renaming = false }
    }
    if (archiving) AlertDialog(onDismissRequest = { archiving = false }, title = { Text("归档此会话？") },
        text = { Text("会话会从活动列表移除，历史记录保留在主机上。") },
        confirmButton = { TextButton(onClick = {
            vm.action { state.selectedAgentId?.let { vm.repository.archiveAgent(it) }; archiving = false; vm.screen.value = "sessions" }
        }, enabled = state.connected) { Text("归档") } }, dismissButton = { TextButton(onClick = { archiving = false }) { Text("取消") } })
}

@Composable
fun ConnectionStrip(state: PaseoState, onClick: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(7.dp).background(if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
            Text(state.host?.name ?: "尚未连接主机", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(when (state.connection) {
                ConnectionStatus.Connected -> "已连接"
                ConnectionStatus.Connecting -> "连接中…"
                ConnectionStatus.Reconnecting -> "正在重连…"
                else -> "未连接"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Icon(Icons.Outlined.Dns, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(if (hosts.isEmpty()) "将工作带在身边" else "你的主机", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("连接运行 Paseo 的电脑，继续与编码代理协作。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(hosts, key = { it.id }) { host ->
            OutlinedCard(onClick = { onConnect(host) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Computer, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(host.name, style = MaterialTheme.typography.titleMedium)
                        Text(host.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host.id == state.host?.id && state.connected) Text("已连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { onEdit(host) }) { Icon(Icons.Outlined.Edit, "编辑 ${host.name}", Modifier.size(20.dp)) }
                    IconButton(onClick = { deleting = host }) { Icon(Icons.Outlined.DeleteOutline, "移除 ${host.name}", Modifier.size(20.dp)) }
                }
            }
        }
        item { FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加主机") } }
        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("开始连接", style = MaterialTheme.typography.titleSmall)
                HelpStep("1", "在电脑上安装并启动 Paseo。")
                HelpStep("2", "使用电脑的局域网、VPN 或 Tailscale 地址。")
                HelpStep("3", "输入主机地址和密码，或粘贴直连配对链接。")
            }
        }
    }
    deleting?.let { host -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除 ${host.name}？") }, text = { Text("移除本机保存的连接配置。") },
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

@Composable
private fun SessionsScreen(state: PaseoState, onOpen: (Agent) -> Unit, onHosts: () -> Unit, onMore: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val filtered = remember(state.agents, query, filter) { state.agents.filter {
        (query.isBlank() || "${it.displayTitle} ${it.cwd} ${it.provider}".contains(query, true)) &&
            when (filter) { 1 -> it.running; 2 -> it.pendingPermissions.isNotEmpty(); else -> true }
    } }
    Column {
        ConnectionStrip(state, onHosts)
        Column(Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("工作区", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${state.agents.size} 个会话", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索会话或项目") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "运行中", "待确认").forEachIndexed { index, label -> FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) }) }
            }
        }
        if (filtered.isEmpty()) EmptyState(Icons.Outlined.Forum, if (query.isNotBlank()) "没有匹配的会话" else "还没有会话",
            if (state.connected) "点击「新会话」，开始处理一个任务。" else "连接主机后，你的会话会显示在这里。", Modifier.weight(1f))
        else LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), modifier = Modifier.weight(1f)) {
            filtered.groupBy { it.cwd }.forEach { (cwd, agents) ->
                item("directory:$cwd") {
                    Row(Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(cwd.substringAfterLast('/').ifEmpty { cwd }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${agents.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                items(agents, key = { it.id }) { agent -> AgentCard(agent) { onOpen(agent) } }
            }
            if (state.nextAgentCursor != null) item { TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多会话") } }
        }
    }
}

@Composable
private fun AgentCard(agent: Agent, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(when {
                agent.pendingPermissions.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
                agent.running -> MaterialTheme.colorScheme.primary
                agent.status == "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }, RoundedCornerShape(50)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(agent.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${agent.provider}  ·  ${agentStatus(agent)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
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
fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostEditor(vm: AppViewModel, host: Host?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(host?.name.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(host?.url.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(host?.password.orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (host == null) "添加主机" else "编辑主机") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("主机名称") }, placeholder = { Text("我的开发机") }, singleLine = true)
            OutlinedTextField(address, { address = it; error = null }, label = { Text("地址或直连配对链接") }, placeholder = { Text("192.168.1.10:6767") }, singleLine = true, isError = error != null)
            OutlinedTextField(password, { password = it }, label = { Text("密码（可选）") }, singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏密码") } })
            Text(error ?: "支持局域网和 VPN 直连，以及 ws / wss / tcp 链接。", style = MaterialTheme.typography.bodySmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(enabled = address.isNotBlank() && !saving, onClick = {
            try { Endpoint.parse(address) } catch (e: Exception) { error = e.message; return@TextButton }
            saving = true
            vm.saveHost(host?.id, name, address, password, onDismiss).invokeOnCompletion { saving = false }
        }) { Text(if (saving) "保存中…" else "保存并连接") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } })
}

@Composable
private fun CreateAgentDialog(vm: AppViewModel, state: PaseoState, onDismiss: () -> Unit) {
    val creating by vm.creating.collectAsStateWithLifecycle()
    val available = state.providers.filter { it.available }
    var provider by rememberSaveable { mutableStateOf(available.firstOrNull()?.provider.orEmpty()) }
    var cwd by rememberSaveable { mutableStateOf(state.agents.firstOrNull()?.cwd.orEmpty()) }
    var model by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(provider, cwd) {
        models = emptyList(); model = ""; catalogError = null
        if (provider.isNotBlank()) {
            delay(350)
            try { models = vm.repository.models(provider, cwd) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { catalogError = e.message }
        }
    }
    AlertDialog(onDismissRequest = { if (!creating) onDismiss() }, title = { Text("新会话") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (available.isEmpty()) Text("主机上没有可用的编码代理。请先在电脑上安装并登录一个代理。", color = MaterialTheme.colorScheme.error)
            ChoiceField("编码代理", provider, available.map { it.provider to it.provider }, { provider = it })
            OutlinedTextField(cwd, { cwd = it }, label = { Text("工作目录（主机上的路径）") }, placeholder = { Text("/home/me/project") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceField("模型", models.find { it.id == model }?.displayName ?: "提供方默认", listOf("" to "提供方默认") + models.map { it.id to it.displayName }, { model = it })
            catalogError?.let { Text("模型列表读取失败，可使用默认模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(title, { title = it }, label = { Text("会话名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it }, label = { Text("你想做什么？（可稍后发送）") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = state.connected && provider.isNotBlank() && cwd.startsWith('/') && !creating,
            onClick = { vm.create(provider, cwd, model, prompt, title, onDismiss) }) { Text(if (creating) "创建中…" else "创建会话") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } })
}

@Composable
fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
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
