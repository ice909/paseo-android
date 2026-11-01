package sh.paseo.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.paseo.android.core.*

internal data class HostSettingsSection(val id: String, val title: String, val icon: Int, val hint: String)

private val hostSettingsSections = listOf(
    HostSettingsSection("projects", "项目", PaseoIcons.FolderGit2, "查看此主机上的项目"),
    HostSettingsSection("connections", "连接", PaseoIcons.Link2, "连接地址与连接方式"),
    HostSettingsSection("pair-device", "配对设备", PaseoIcons.QrCode, "管理移动设备配对"),
    HostSettingsSection("agents", "代理", PaseoIcons.Bot, "主机上的代理行为与能力"),
    HostSettingsSection("metadata", "元数据", PaseoIcons.Sparkles, "工作区与会话元数据"),
    HostSettingsSection("voice", "语音", PaseoIcons.Mic, "语音输入与播放设置"),
    HostSettingsSection("workspaces", "工作区", PaseoIcons.FolderOpen, "工作区行为"),
    HostSettingsSection("providers", "提供商", PaseoIcons.Layers, "模型提供商配置"),
    HostSettingsSection("usage", "用量", PaseoIcons.Gauge, "查看账户用量"),
    HostSettingsSection("terminals", "终端", PaseoIcons.SquareTerminal, "终端配置"),
    HostSettingsSection("plugins", "插件", PaseoIcons.SquarePlus, "主机插件"),
)

internal fun hostSettingsTitle(section: String): String =
    if (section == "host") "主机" else hostSettingsSections.find { it.id == section }?.title ?: "主机"

@Composable
internal fun HostSettingsScreen(
    host: Host,
    section: String,
    state: PaseoState,
    onSection: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onAppearance: () -> Unit,
) {
    val palette = LocalPaseoPalette.current
    var confirmRemove by remember(host.id) { mutableStateOf(false) }
    val connected = state.host?.id == host.id && state.connected
    val activeRoute = state.activeConnectionUrl?.takeIf { state.host?.id == host.id }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (section == "host") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(host.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (connected) "已连接" else "未连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) palette.statusSuccess else palette.foregroundMuted,
                )
            }
            HostSettingsCard {
                HostSettingsValueRow("连接地址", host.url)
                host.directUrl?.let { HostSettingsValueRow("局域网直连", it) }
                HostSettingsValueRow("当前路径", when {
                    !connected -> "未连接"
                    host.directUrl != null && activeRoute == host.directUrl -> "局域网直连"
                    host.relay != null -> "中继"
                    else -> "直接连接"
                })
                HostSettingsAction("编辑连接", PaseoIcons.Pencil, onEdit)
            }
            HostSettingsCard {
                HostSettingsAction("外观", PaseoIcons.SlidersHorizontal, onAppearance, "主题与界面显示")
            }
            Text("主机设置", style = MaterialTheme.typography.titleSmall)
            HostSettingsCard {
                hostSettingsSections.forEach { item ->
                    HostSettingsAction(item.title, item.icon, { onSection(item.id) }, item.hint)
                }
            }
            HostSettingsCard {
                HostSettingsAction("移除主机", PaseoIcons.Trash2, { confirmRemove = true }, "从此设备移除保存的连接", destructive = true)
            }
        } else {
            Text(hostSettingsTitle(section), style = MaterialTheme.typography.titleMedium)
            when (section) {
                "connections" -> HostSettingsCard {
                    HostSettingsValueRow("配对链接", host.url)
                    host.directUrl?.let { HostSettingsValueRow("局域网地址", it) }
                    HostSettingsValueRow("状态", if (connected) "已连接" else "未连接")
                    HostSettingsAction("编辑连接", PaseoIcons.Pencil, onEdit)
                }
                "projects" -> {
                    if (state.host?.id == host.id && state.projects.isNotEmpty()) HostSettingsCard {
                        state.projects.forEach { project ->
                            HostSettingsValueRow(project.projectDisplayName, project.projectRootPath)
                        }
                    } else HostSettingsInfo(if (connected) "此主机尚未提供项目。" else "连接此主机后可查看项目。")
                }
                "workspaces" -> {
                    if (state.host?.id == host.id && state.workspaces.isNotEmpty()) HostSettingsCard {
                        state.workspaces.forEach { workspace -> HostSettingsValueRow(workspace.name, workspace.cwd) }
                    } else HostSettingsInfo(if (connected) "此主机尚未提供工作区。" else "连接此主机后可查看工作区。")
                }
                "pair-device" -> HostSettingsInfo("设备配对管理尚未在 Android 客户端开放。可在运行 Paseo 的电脑上管理配对设备。")
                "agents" -> HostSettingsInfo("代理行为设置尚未在 Android 客户端开放。")
                "metadata" -> HostSettingsInfo("元数据生成设置尚未在 Android 客户端开放。")
                "voice" -> HostSettingsInfo("语音服务由主机配置；此处的主机语音设置尚未在 Android 客户端开放。")
                "providers" -> HostSettingsInfo("提供商配置尚未在 Android 客户端开放。")
                "usage" -> HostSettingsInfo(if (connected) "用量数据可从侧栏的「用量」入口查看。" else "连接此主机后可查看账户用量。")
                "terminals" -> HostSettingsInfo("终端配置尚未在 Android 客户端开放。")
                "plugins" -> HostSettingsInfo("插件管理尚未在 Android 客户端开放。")
                else -> HostSettingsInfo("此主机设置暂不可用。")
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("移除 ${host.displayName}？") },
        text = { Text("移除本机保存的连接配置。") },
        confirmButton = {
            TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("移除") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
    )
}

@Composable
private fun HostSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = LocalPaseoPalette.current.surface1,
        border = BorderStroke(1.dp, LocalPaseoPalette.current.border),
    ) {
        Column(content = content)
    }
}

@Composable
private fun HostSettingsValueRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = LocalPaseoPalette.current.foregroundMuted,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = LocalPaseoPalette.current.border)
}

@Composable
private fun HostSettingsAction(
    title: String,
    icon: Int,
    onClick: () -> Unit,
    hint: String? = null,
    destructive: Boolean = false,
) {
    val palette = LocalPaseoPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PaseoGlyph(icon, null, Modifier.size(18.dp), tint = if (destructive) MaterialTheme.colorScheme.error else palette.foregroundMuted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (destructive) MaterialTheme.colorScheme.error else palette.foreground)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.foregroundMuted) }
        }
        PaseoGlyph(PaseoIcons.ChevronRight, null, Modifier.size(16.dp), tint = palette.foregroundMuted)
    }
    HorizontalDivider(color = palette.border)
}

@Composable
private fun HostSettingsInfo(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = LocalPaseoPalette.current.surface1,
        border = BorderStroke(1.dp, LocalPaseoPalette.current.border),
    ) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = LocalPaseoPalette.current.foregroundMuted)
    }
}
