package sh.paseo.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

/** Compact layout follows the official app: setup fields sit directly above the composer. */
@Composable
fun NewWorkspaceScreen(vm: AppViewModel, state: PaseoState, workspace: Workspace?, initialProject: String?, onCreated: () -> Unit) {
    val creating by vm.creating.collectAsStateWithLifecycle()
    val available = state.providers.filter { it.available }
    var provider by rememberSaveable(state.host?.id, workspace?.id) { mutableStateOf("") }
    var projectId by rememberSaveable(initialProject, workspace?.id) { mutableStateOf(initialProject.orEmpty()) }
    val project = state.projects.find { it.projectId == projectId }
    var cwd by rememberSaveable(state.host?.id, initialProject, workspace?.id) { mutableStateOf(workspace?.cwd ?: project?.projectRootPath.orEmpty()) }
    var model by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable(state.host?.id, workspace?.id, initialProject) { mutableStateOf("") }
    var title by rememberSaveable(state.host?.id, workspace?.id, initialProject) { mutableStateOf("") }
    var worktree by rememberSaveable(initialProject) { mutableStateOf(false) }
    var branch by rememberSaveable(initialProject) { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modes by remember { mutableStateOf<List<ModeOption>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    // Freeze an intent after submission. Returning to edit starts a fresh key; retry uses the same one.
    var key by rememberSaveable { mutableStateOf(newId()) }
    LaunchedEffect(available.map { it.provider }) { if (provider.isBlank()) provider = available.firstOrNull()?.provider.orEmpty() }
    LaunchedEffect(provider, cwd, state.connected) {
        models = emptyList(); modes = emptyList(); catalogError = null
        if (provider.isBlank() || !state.connected) return@LaunchedEffect
        delay(250)
        try { models = vm.repository.models(provider, cwd); modes = vm.repository.modes(provider, cwd) }
        catch (e: Exception) { if (e is CancellationException) throw e; catalogError = e.message }
    }
    LaunchedEffect(cwd, projectId, state.connected) {
        suggestions = emptyList()
        if (cwd.length < 2 || projectId.isNotEmpty() || workspace != null || !state.connected) return@LaunchedEffect
        delay(400)
        try { suggestions = vm.repository.directorySuggestions(cwd).map { it.string("path") }.filter { it.isNotBlank() && it != cwd } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The user can always enter an absolute path. */ }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (workspace == null) "新建工作区" else "新建代理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (workspace == null) "选择项目，开始一个新任务。" else workspace.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (!creating && submitted) {
                TextButton(onClick = { submitted = false; key = newId() }) { Text("编辑创建配置") }
                Text("重试会沿用同一次创建请求。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (workspace == null) {
                ChoiceField("项目", project?.projectDisplayName ?: "选择主机上的目录", listOf("" to "选择主机上的目录") + state.projects.map { it.projectId to it.projectDisplayName }) {
                    if (!submitted) { projectId = it; cwd = state.projects.find { p -> p.projectId == it }?.projectRootPath.orEmpty(); worktree = false }
                }
                OutlinedTextField(cwd, { cwd = it }, label = { Text("工作目录") }, placeholder = { Text("/home/me/project") }, singleLine = true, enabled = !submitted,
                    modifier = Modifier.fillMaxWidth(), supportingText = { Text("填写主机上的绝对路径") })
                if (!submitted && suggestions.isNotEmpty()) Column {
                    suggestions.take(3).forEach { path -> TextButton(onClick = { cwd = path }) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(path) } }
                }
                if (project?.projectKind == "git") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(worktree, { worktree = it }, enabled = !submitted)
                        Text("使用独立 Git worktree", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (worktree) OutlinedTextField(branch, { branch = it }, label = { Text("新分支名称（可选）") }, enabled = !submitted, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("名称（可选）") }, enabled = !submitted, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (state.connected && available.isEmpty()) Text("主机上没有可用的编码代理，请先安装并登录代理。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (catalogError != null) Text("无法读取部分代理选项，可以使用默认值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
        }
        }
        MessageComposer(value = prompt, onValueChange = { if (!submitted) prompt = it }, placeholder = "你想完成什么？",
            enabled = !submitted, canSend = state.connected && provider.isNotBlank() && cwd.startsWith('/') && !creating,
            sending = creating, onSend = { submitted = true; vm.create(provider, cwd, model, mode, prompt, title, key, workspace, projectId.ifBlank { null }, worktree, branch, onCreated) },
            sendDescription = if (submitted && !creating) "重试创建" else if (workspace == null) "创建工作区" else "创建代理",
            options = {
                ComposerChoice(provider.ifBlank { "选择代理" }, available.map { it.provider to it.provider }, enabled = !submitted) { provider = it; model = ""; mode = "" }
                ComposerChoice(models.find { it.id == model }?.displayName ?: "默认模型", listOf("" to "默认模型") + models.map { it.id to it.displayName }, enabled = !submitted) { model = it }
                if (modes.isNotEmpty()) ComposerChoice(modes.find { it.id == mode }?.displayName ?: "默认模式", listOf("" to "默认模式") + modes.map { it.id to it.displayName }, enabled = !submitted) { mode = it }
            })
    }
}
