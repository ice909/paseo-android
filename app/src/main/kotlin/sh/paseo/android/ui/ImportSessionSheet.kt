package sh.paseo.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.*

@Composable
internal fun ImportSessionSheet(vm: AppViewModel, state: PaseoState, cwd: String, workspace: Workspace?,
    onDismiss: () -> Unit, onImported: (Agent) -> Unit) {
    val hostId = state.host?.id
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(hostId) { mutableStateOf("") }
    var allDirectories by rememberSaveable(hostId, cwd) { mutableStateOf(false) }
    var provider by rememberSaveable(hostId) { mutableStateOf("") }
    var limit by rememberSaveable(hostId, query, allDirectories) { mutableIntStateOf(50) }
    var revision by remember { mutableIntStateOf(0) }
    var response by remember(hostId) { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember(hostId) { mutableStateOf<String?>(null) }
    var importing by remember(hostId) { mutableStateOf<String?>(null) }
    val directory = cwd.takeIf { it.isNotBlank() && !allDirectories }
    LaunchedEffect(hostId, state.connected, directory, query, limit, revision) {
        response = null; error = null; loading = false
        if (!state.connected) return@LaunchedEffect
        loading = true
        try {
            delay(200)
            response = vm.repository.recentProviderSessions(directory, query.trim(), limit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取会话" }
        finally { loading = false }
    }
    val entries = response?.array("entries").orEmpty().map { it.jsonObject }
    val providers = entries.distinctBy { it.string("providerId") }
    val filtered = entries.filter { (provider.isBlank() || it.string("providerId") == provider) &&
        (state.features.bool("importSessionSearch") || query.isBlank() ||
            listOf("title", "firstPromptPreview", "lastPromptPreview", "cwd", "providerLabel").any { field -> it.string(field).contains(query.trim(), true) }) }
    WorkspacePicker("导入会话", { if (importing == null) onDismiss() }) {
        Text(directory ?: "此主机上的全部项目", style = PaseoUiText, color = LocalPaseoPalette.current.foregroundMuted)
        Row {
            if (cwd.isNotBlank()) TextButton(onClick = { allDirectories = !allDirectories }, enabled = importing == null) { Text(if (allDirectories) "仅当前项目" else "显示全部项目") }
            TextButton(onClick = { revision++ }, enabled = !loading && importing == null && state.connected) { Text("刷新") }
        }
        WorkspaceSearch(query, { if (importing == null) query = it }, "搜索会话")
        if (providers.size > 1 || provider.isNotBlank()) ComposerChoice(
            providers.find { it.string("providerId") == provider }?.string("providerLabel") ?: "全部代理",
            listOf("" to "全部代理") + providers.map { it.string("providerId") to it.string("providerLabel") }, importing == null) { provider = it }
        if (!state.connected) Text("请先连接主机", Modifier.padding(12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        response?.array("providerErrors").orEmpty().forEach { item ->
            val failure = item.jsonObject
            Text("${failure.string("provider")}：${failure.string("message")}", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        if (!loading && response != null && filtered.isEmpty()) Text(
            if ((response!!.long("filteredAlreadyImportedCount") ?: 0L) > 0 && query.isBlank()) "此范围的会话已全部导入" else "没有可导入的会话",
            Modifier.padding(12.dp))
        filtered.sortedByDescending { it.string("lastActivityAt") }.forEach { entry ->
            val id = "${entry.string("providerId")}:${entry.string("providerHandleId")}"
            val title = entry.string("title").ifBlank { entry.string("firstPromptPreview") }.ifBlank { "未命名会话" }
            WorkspaceOption(if (importing == id) "正在导入…" else title,
                detail = listOf(entry.string("providerLabel"), entry.string("cwd"), entry.string("lastPromptPreview").ifBlank { entry.string("firstPromptPreview") }).filter { it.isNotBlank() }.joinToString(" · "),
                icon = PaseoIcons.provider(entry.string("providerId"))) {
                if (importing == null && state.connected) {
                    importing = id; error = null
                    scope.launch {
                        try {
                            val target = workspace?.takeIf { directory != null && it.cwd == entry.string("cwd") && state.features.bool("importSessionWorkspaceTarget") }?.id
                            val agent = vm.repository.importSession(entry, target)
                            if (vm.state.value.host?.id == hostId) onImported(agent)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "导入失败，请重试" }
                        finally { importing = null }
                    }
                }
            }
        }
        if (entries.size >= limit) TextButton(onClick = { limit += 50 }, enabled = !loading && importing == null) { Text("加载更多") }
    }
}
