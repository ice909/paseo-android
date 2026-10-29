package sh.paseo.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

/** The compact official form has five reserved rows, with the composer docked below. */
@Composable
fun NewWorkspaceScreen(vm: AppViewModel, state: PaseoState, workspace: Workspace?, initialProject: String?,
    onManageHosts: () -> Unit, onCreated: (Boolean) -> Unit) {
    val p = LocalPaseoPalette.current
    val creating by vm.creating.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val sessions by vm.conversations.sessions.collectAsStateWithLifecycle()
    val imports by vm.importingAttachments.collectAsStateWithLifecycle()
    val audio by vm.audio.state.collectAsStateWithLifecycle()
    val available = state.providers.filter { it.available }
    var provider by rememberSaveable(state.host?.id, workspace?.id) { mutableStateOf("") }
    var projectId by rememberSaveable(state.host?.id, initialProject, workspace?.id) { mutableStateOf(initialProject.orEmpty()) }
    val project = state.projects.find { it.projectId == projectId }
    var cwd by rememberSaveable(state.host?.id, initialProject, workspace?.id) { mutableStateOf(workspace?.cwd.orEmpty()) }
    var model by rememberSaveable(state.host?.id) { mutableStateOf("") }
    var mode by rememberSaveable(state.host?.id) { mutableStateOf("") }
    var worktree by rememberSaveable(state.host?.id, initialProject) { mutableStateOf(false) }
    var baseRef by rememberSaveable(state.host?.id, projectId) { mutableStateOf("") }
    var terminal by rememberSaveable(workspace?.id) { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modes by remember { mutableStateOf<List<ModeOption>>(emptyList()) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable(state.host?.id, workspace?.id, initialProject) { mutableStateOf(false) }
    var intent by rememberSaveable(state.host?.id, workspace?.id, initialProject) { mutableStateOf(newId()) }
    var sheet by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable(sheet) { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val key = ConversationKey(state.host?.id.orEmpty(), "workspace-draft:${workspace?.id ?: initialProject ?: "new"}:${if (terminal) "terminal" else "chat"}")
    val draft = sessions[key]?.draft ?: ChatDraft()
    val importing = key in imports
    val dictating = audio.key == key && audio.active
    val editable = !submitted && !creating
    val canCreate = state.connected && cwd.startsWith('/') && !creating && !importing && !dictating && (terminal || (workspace == null && draft.empty) || provider.isNotBlank())
    fun submit() {
        // Dictation emits its final text and submit event before Compose's next frame.
        if (!vm.state.value.connected || vm.state.value.host?.id != key.hostId || !cwd.startsWith('/') || vm.creating.value || key in vm.importingAttachments.value || (vm.audio.state.value.key == key && vm.audio.state.value.active)) return
        if (!terminal && (workspace != null || !vm.conversations.session(key).draft.empty) && provider.isBlank()) return
        submitted = true
        vm.createFromHome(provider, cwd, model, mode, key, intent, workspace, projectId.ifBlank { null }, worktree, baseRef, terminal) { launchTerminal ->
            submitted = false; intent = newId(); onCreated(launchTerminal)
        }
    }
    val latestSubmit by rememberUpdatedState(::submit)
    LaunchedEffect(key) { vm.homeSubmitRequests.collect { if (it == key) latestSubmit() } }
    LaunchedEffect(available.map { it.provider }) { if (provider.isBlank()) provider = available.firstOrNull()?.provider.orEmpty() }
    LaunchedEffect(project?.projectRootPath) { if (project != null) cwd = project.projectRootPath }
    LaunchedEffect(provider, cwd, state.connected) {
        models = emptyList(); modes = emptyList()
        if (provider.isBlank() || !state.connected) return@LaunchedEffect
        delay(200)
        try { models = vm.repository.models(provider, cwd); modes = vm.repository.modes(provider, cwd) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The provider's defaults remain selectable. */ }
    }
    LaunchedEffect(sheet, search, cwd, state.connected) {
        branches = emptyList()
        if (sheet != "base" || !state.connected) return@LaunchedEffect
        delay(200)
        try { branches = vm.repository.branches(cwd, search) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { vm.notices.tryEmit("无法读取分支，请检查主机连接后重试") }
    }
    LaunchedEffect(sheet, path, state.connected) {
        suggestions = emptyList()
        if (sheet != "directory" || path.length < 2 || !state.connected) return@LaunchedEffect
        delay(250)
        try { suggestions = vm.repository.directorySuggestions(path).map { it.string("path") }.filter { it.isNotBlank() && it != path } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Manual absolute paths still work. */ }
    }
    val context = LocalContext.current
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.attachFiles(key, uris) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.audio.start(key, false) else vm.notices.tryEmit("需要麦克风权限才能使用语音")
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(key, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && (context as? android.app.Activity)?.isChangingConfigurations != true) vm.audio.cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (vm.audio.state.value.key == key && (context as? android.app.Activity)?.isChangingConfigurations != true) vm.audio.cancel()
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().then(if (expanded) Modifier.height(0.dp) else Modifier.weight(1f)), verticalArrangement = Arrangement.Bottom) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (submitted && !creating) TextButton(onClick = { submitted = false; intent = newId() }) { PaseoText("编辑创建配置") }
                if (workspace == null) {
                    WorkspaceField("项目", project?.projectDisplayName ?: cwd.substringAfterLast('/').ifBlank { "Choose project" }, editable, { sheet = "project" }) {
                        if (project != null || cwd.isNotBlank()) Box(Modifier.size(16.dp).background(p.surface3, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                            PaseoText((project?.projectDisplayName ?: cwd.substringAfterLast('/')).take(1).uppercase(), style = PaseoUiText.copy(fontSize = 10.sp), fontWeight = FontWeight.SemiBold)
                        } else PaseoGlyph(PaseoIcons.Folder, null, Modifier.size(14.dp), p.foregroundMuted)
                    }
                    WorkspaceField("主机", state.host?.displayName ?: "选择主机", editable, { sheet = "host" }) {
                        Box(Modifier.size(7.dp).background(if (state.connected) p.statusDotSuccess else p.foregroundExtraMuted, CircleShape))
                    }
                    if (project?.projectKind == "git") WorkspaceField("隔离", if (worktree) "新建 worktree" else "本地", editable, { sheet = "isolation" }) {
                        PaseoGlyph(PaseoIcons.GitBranch, null, Modifier.size(14.dp), p.foregroundMuted)
                    }
                    if (project?.projectKind == "git" && worktree) WorkspaceField("分支", baseRef.ifBlank { "默认分支" }, editable, { sheet = "base" }) {
                        PaseoGlyph(PaseoIcons.GitBranch, null, Modifier.size(14.dp), p.foregroundMuted)
                    }
                    WorkspaceField("What to launch", if (terminal) "Terminal" else "Chat", editable, { sheet = "launch" }) {
                        PaseoGlyph(if (terminal) PaseoIcons.SquareTerminal else PaseoIcons.MessageCircle, null, Modifier.size(14.dp), p.foregroundMuted)
                    }
                    if (project?.projectKind != "git") Spacer(Modifier.height(56.dp))
                    if (project?.projectKind != "git" || !worktree) Spacer(Modifier.height(56.dp))
                }
            }
        }
        if (audio.key == key) ConversationAudioPanel(vm, audio)
        MessageComposer(draft.text, { if (editable) vm.conversations.setDraft(key, it) }, if (terminal) "输入命令（可选）" else "发消息，@files，/commands",
            enabled = editable && !dictating, canSend = canCreate || (dictating && audio.phase == "recording"), sending = creating || importing || audio.phase == "uploading",
            onSend = { if (dictating) vm.audio.confirm(true) else submit() }, returnIcon = !terminal,
            sendDescription = if (submitted && !creating) "重试创建" else if (workspace == null) "创建工作区" else "创建代理",
            expanded = expanded, onExpand = { expanded = !expanded }, modifier = if (expanded) Modifier.weight(1f) else Modifier,
            hasAttachments = draft.attachments.isNotEmpty(), onReceiveFiles = if (terminal) null else { uris -> vm.attachFiles(key, uris) },
            attachments = { ChatAttachmentTray(draft.attachments, if (editable) { id -> vm.conversations.removeAttachment(key, id) } else null) },
            trailingControls = {
                if (!terminal) IconButton(onClick = {
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.audio.start(key, false)
                    else microphone.launch(android.Manifest.permission.RECORD_AUDIO)
                }, enabled = state.connected && editable && !dictating && !importing, modifier = Modifier.size(28.dp)) { PaseoGlyph(PaseoIcons.Mic, "语音输入", Modifier.size(20.dp), p.foregroundMuted) }
            }, options = {
                if (!terminal) {
                    IconButton(onClick = { sheet = "attach" }, enabled = editable && !dictating && !importing, modifier = Modifier.size(28.dp)) { PaseoGlyph(PaseoIcons.Plus, "添加附件", Modifier.size(20.dp), p.foregroundMuted) }
                    ComposerChoice(models.find { it.id == model }?.displayName ?: "默认模型",
                        available.map { "provider:${it.provider}" to "代理：${it.provider}" } + listOf("model:" to "默认模型") + models.map { "model:${it.id}" to it.displayName }, editable, PaseoIcons.provider(provider)) { selection ->
                        if (selection.startsWith("provider:")) { provider = selection.removePrefix("provider:"); model = ""; mode = "" }
                        else model = selection.removePrefix("model:")
                    }
                    if (modes.isNotEmpty()) ComposerChoice(modes.find { it.id == mode }?.displayName ?: "默认模式", listOf("" to "默认模式") + modes.map { it.id to it.displayName }, editable) { mode = it }
                }
            })
    }
    if (sheet.isNotBlank()) WorkspacePicker(when (sheet) { "project" -> "选择项目"; "directory" -> "添加项目"; "host" -> "选择主机"; "isolation" -> "隔离"; "base" -> "选择分支"; "attach" -> "添加附件"; else -> "What to launch" }, { sheet = "" }) {
        when (sheet) {
            "project" -> {
                WorkspaceSearch(search, { search = it }, "搜索项目")
                state.projects.filter { it.projectDisplayName.contains(search, true) || it.projectRootPath.contains(search, true) }.forEach { item ->
                    WorkspaceOption(item.projectDisplayName, item.projectId == projectId, item.projectRootPath) { projectId = item.projectId; cwd = item.projectRootPath; worktree = false; sheet = "" }
                }
                WorkspaceOption("添加项目", icon = PaseoIcons.Plus) { path = cwd.ifBlank { "/" }; sheet = "directory" }
            }
            "directory" -> {
                WorkspaceSearch(path, { path = it }, "主机上的绝对目录")
                suggestions.take(8).forEach { item -> WorkspaceOption(item) { path = item } }
                TextButton(onClick = { projectId = ""; cwd = path.trim(); worktree = false; sheet = "" }, enabled = path.trim().startsWith('/')) { PaseoText("使用此目录") }
            }
            "host" -> {
                hosts.forEach { host -> WorkspaceOption(host.displayName, host.id == state.host?.id, if (host.id == state.host?.id) when (state.connection) { ConnectionStatus.Connected -> "已连接"; ConnectionStatus.Connecting -> "连接中…"; ConnectionStatus.Reconnecting -> "重新连接中…"; else -> "未连接" } else null, PaseoIcons.Server) { vm.connectWorkspaceHost(host); sheet = "" } }
                WorkspaceOption("添加主机", icon = PaseoIcons.Plus) { sheet = ""; onManageHosts() }
            }
            "isolation" -> {
                WorkspaceOption("本地", !worktree, icon = PaseoIcons.Folder) { worktree = false; sheet = "" }
                WorkspaceOption("新建 worktree", worktree, icon = PaseoIcons.GitBranch) { worktree = true; sheet = "" }
            }
            "base" -> {
                WorkspaceSearch(search, { search = it }, "搜索分支")
                WorkspaceOption("默认分支", baseRef.isBlank(), icon = PaseoIcons.GitBranch) { baseRef = ""; sheet = "" }
                branches.forEach { ref -> WorkspaceOption(ref, baseRef == ref, icon = PaseoIcons.GitBranch) { baseRef = ref; sheet = "" } }
            }
            "launch" -> {
                WorkspaceOption("Chat", !terminal, icon = PaseoIcons.MessageCircle) { terminal = false; sheet = "" }
                WorkspaceOption("Terminal", terminal, icon = PaseoIcons.SquareTerminal) { terminal = true; sheet = "" }
            }
            "attach" -> {
                WorkspaceOption("图片", icon = PaseoIcons.ImagePlus) { sheet = ""; documents.launch(arrayOf("image/*")) }
                WorkspaceOption("文件", icon = PaseoIcons.Paperclip) { sheet = ""; documents.launch(arrayOf("*/*")) }
            }
        }
    }
}
