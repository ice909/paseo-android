@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package sh.paseo.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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

internal val hostSettingsSections = listOf(
    HostSettingsSection("projects", "项目", PaseoIcons.FolderGit2, "查看此主机上的项目"),
    HostSettingsSection("connections", "连接", PaseoIcons.Network, "连接地址与连接方式"),
    HostSettingsSection("pair-device", "配对设备", PaseoIcons.Smartphone, "管理移动设备配对"),
    HostSettingsSection("agents", "代理", PaseoIcons.Bot, "主机上的代理行为与能力"),
    HostSettingsSection("metadata", "元数据", PaseoIcons.Sparkles, "工作区与会话元数据"),
    HostSettingsSection("voice", "语音", PaseoIcons.Mic, "语音输入与播放设置"),
    HostSettingsSection("workspaces", "工作区", PaseoIcons.FolderOpen, "工作区行为"),
    HostSettingsSection("providers", "提供商", PaseoIcons.Boxes, "模型提供商配置"),
    HostSettingsSection("usage", "用量", PaseoIcons.Gauge, "查看账户用量"),
    HostSettingsSection("terminals", "终端", PaseoIcons.SquareTerminal, "终端配置"),
    HostSettingsSection("plugins", "插件", PaseoIcons.Blocks, "主机插件"),
)

internal fun hostSettingsTitle(section: String): String =
    if (section == "host") "主机" else hostSettingsSections.find { it.id == section }?.title ?: "主机"

internal fun isHostSettingsSection(section: String): Boolean =
    section == "host" || hostSettingsSections.any { it.id == section }

@Composable
internal fun HostSettingsScreen(
    vm: sh.paseo.android.AppViewModel,
    host: Host,
    section: String,
    state: PaseoState,
    onRemove: () -> Unit,
) {
    val palette = LocalPaseoPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val appVersion = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().removePrefix("v") }
    val connected = state.host?.id == host.id && state.connected
    var removing by remember(host.id) { mutableStateOf<HostConnection?>(null) }
    var confirmRemove by remember(host.id) { mutableStateOf(false) }
    var rename by remember(host.id) { mutableStateOf(false) }
    var draftName by remember(host.id, rename) { mutableStateOf(host.displayName) }
    var lifecycleAction by remember(host.id) { mutableStateOf<String?>(null) }
    var probes by remember(host.id) { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    LaunchedEffect(host.id, host.connections, section) {
        if (section == "connections") while (true) {
            probes = vm.probeHostConnections(host)
            kotlinx.coroutines.delay(if (connected) 10_000 else 30_000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.host?.id == host.id) state.error?.let {
            Text(it, Modifier.fillMaxWidth().padding(12.dp), color = palette.destructive, style = PaseoUiText)
        }
        if (section == "host") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HostStatusPill(if (connected) "已连接" else "未连接", status = true, connected = connected)
                if (connected) host.savedConnections().find { it.url == state.activeConnectionUrl }?.let {
                    HostStatusPill(if (it.relay != null) "中继" else it.displayLabel.removePrefix("TCP (").removeSuffix(")"))
                }
                if (state.host?.id == host.id && state.serverVersion.isNotBlank()) HostStatusPill("v" + state.serverVersion.removePrefix("v"))
            }
            HostSection("外观") {
                HostSettingsCard {
                    HostInlineRow("名称") {
                        Text(host.displayName, Modifier.widthIn(max = 180.dp), style = PaseoUiText, color = palette.foregroundMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(28.dp).clickable { rename = true }, contentAlignment = Alignment.Center) {
                            PaseoGlyph(PaseoIcons.Pencil, "重命名主机", Modifier.size(16.dp), palette.foregroundMuted)
                        }
                    }
                    HostDivider()
                    HostInlineRow("颜色") { HostAppearanceMenu(host.color, hostColors) { vm.setHostAppearance(host, color = it) } }
                    HostDivider()
                    HostInlineRow("主机标记") { HostAppearanceMenu(host.badgeDisplay, hostBadgeDisplays) { vm.setHostAppearance(host, badgeDisplay = it) } }
                    HostDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp).background(palette.surfaceSidebar).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("工作区预览", style = PaseoUiText)
                        HostIdentityBadge(host)
                    }
                }
            }
            if (connected && (state.features.bool("daemonSelfUpdate") || state.desktopManaged) && state.serverVersion.isNotBlank() &&
                state.serverVersion.removePrefix("v") != appVersion) HostSettingsCard {
                HostInlineRow("更新主机", "将主机更新到最新版本") {
                    HostSmallButton("更新", !state.desktopManaged) { lifecycleAction = "update" }
                }
            }
            HostSection("危险区域") {
                HostSettingsCard {
                    HostInlineRow("重启主机", "重启 Paseo 服务") {
                        HostSmallButton("重启", connected) { lifecycleAction = "restart" }
                    }
                    if (connected && state.features.bool("terminalsSurviveRestart")) {
                        HostDivider()
                        HostInlineRow("关闭终端并重启", "同时关闭此主机上的终端进程") {
                            HostSmallButton("关闭并重启") { lifecycleAction = "killTerminals" }
                        }
                    }
                }
                HostSettingsCard {
                    HostInlineRow("移除主机", "从此设备移除主机及其保存的连接") {
                        HostSmallButton("移除", destructive = true) { confirmRemove = true }
                    }
                }
            }
        } else if (section == "connections") {
            HostSection("连接") {
                HostSettingsCard {
                    host.savedConnections().forEachIndexed { index, connection ->
                        if (index > 0) HostDivider()
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(connection.displayLabel, Modifier.weight(1f), style = PaseoUiText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (connection.id !in probes) "…" else probes[connection.id]?.let { "$it ms" } ?: "超时",
                                style = PaseoUiText, color = if (connection.id in probes && probes[connection.id] == null) palette.destructive else palette.foregroundMuted)
                            HostSmallButton("移除", destructive = true, outline = false) { removing = connection }
                        }
                    }
                }
            }
        } else {
            HostSection(hostSettingsTitle(section)) {
                HostSettingsCard {
                    when (section) {
                        "projects" -> if (connected && state.projects.isNotEmpty()) state.projects.forEach { HostSettingsValueRow(it.projectDisplayName, it.projectRootPath) }
                            else HostSettingsInfo(if (connected) "此主机尚未提供项目。" else "连接此主机后可查看项目。")
                        "workspaces" -> if (connected && state.workspaces.isNotEmpty()) state.workspaces.forEach { HostSettingsValueRow(it.name, it.cwd) }
                            else HostSettingsInfo(if (connected) "此主机尚未提供工作区。" else "连接此主机后可查看工作区。")
                        else -> HostSettingsInfo("此分区尚未在 Android 客户端开放。")
                    }
                }
            }
        }
    }
    removing?.let { connection -> HostActionSheet("移除连接", "移除", { removing = null }, {
        vm.removeSavedConnection(host, connection.id)
        if (host.savedConnections().size == 1) onRemove()
    }, destructive = true) {
        Text("确定移除 ${connection.displayLabel}？", style = PaseoUiText)
    } }
    if (confirmRemove) HostActionSheet("移除主机", "移除", { confirmRemove = false }, {
        vm.removeSavedHost(host); onRemove()
    }, destructive = true) { Text("确定从此设备移除 ${host.displayName}？", style = PaseoUiText) }
    if (rename) HostActionSheet("重命名主机", "保存", { rename = false }, {
        vm.editHostName(host, draftName)
    }, valid = draftName.isNotBlank()) {
        PairingInput(draftName, { draftName = it }, "主机名称", "名称", enabled = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text)
    }
    lifecycleAction?.let { action -> HostActionSheet(if (action == "update") "更新主机" else "重启主机", "确认",
        { lifecycleAction = null }, { vm.performHostLifecycle(host, action) }, destructive = action != "update") {
        Text(when (action) {
            "update" -> "更新并重启此主机上的 Paseo 服务？"
            "killTerminals" -> "关闭此主机上的所有终端进程并重启 Paseo 服务？"
            else -> "重启 ${host.displayName} 上的 Paseo 服务？"
        }, style = PaseoUiText)
    } }
}

@Composable
private fun HostSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = LocalPaseoPalette.current.foregroundMuted)
        content()
    }
}

@Composable
private fun HostInlineRow(title: String, hint: String? = null, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = PaseoUiText)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalPaseoPalette.current.foregroundMuted) }
        }
        trailing()
    }
}

@Composable
private fun HostAppearanceMenu(selected: String, options: Map<String, String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { open = true }.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (options === hostColors) Box(Modifier.size(16.dp).border(1.dp, LocalPaseoPalette.current.border, RoundedCornerShape(50)).background(if (selected == "none") androidx.compose.ui.graphics.Color.Transparent else hostIdentityColor(selected), RoundedCornerShape(50)))
            Text(options[selected].orEmpty(), style = PaseoUiText, color = LocalPaseoPalette.current.foregroundMuted)
            PaseoGlyph(PaseoIcons.ChevronDown, "选择", Modifier.size(14.dp))
        }
        PaseoDropdownMenu(open, { open = false }) {
            options.forEach { (key, label) -> DropdownMenuItem(text = { Text(label, style = PaseoUiText) },
                leadingIcon = { if (options === hostColors) Box(Modifier.size(16.dp).background(if (key == "none") androidx.compose.ui.graphics.Color.Transparent else hostIdentityColor(key), RoundedCornerShape(50))) },
                trailingIcon = { if (key == selected) PaseoGlyph(PaseoIcons.Check, "已选择", Modifier.size(16.dp)) },
                onClick = { onSelect(key); open = false }) }
        }
    }
}

@Composable
private fun HostStatusPill(label: String, status: Boolean = false, connected: Boolean = false) {
    val palette = LocalPaseoPalette.current
    val color = if (status && connected) palette.statusSuccess else palette.foregroundMuted
    Row(Modifier.background(if (status && connected) color.copy(alpha = .1f) else palette.surface2, RoundedCornerShape(50))
        .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status) Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun HostDivider() { HorizontalDivider(color = LocalPaseoPalette.current.border) }

@Composable
private fun HostSmallButton(label: String, enabled: Boolean = true, destructive: Boolean = false, outline: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPaseoPalette.current
    Box(Modifier.heightIn(min = 32.dp).then(if (outline) Modifier.border(1.dp, palette.border, RoundedCornerShape(8.dp)) else Modifier)
        .clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (!enabled) palette.foregroundMuted.copy(alpha = .5f) else if (destructive) palette.destructive else palette.foreground)
    }
}

@Composable
private fun HostSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
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
private fun HostSettingsInfo(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = LocalPaseoPalette.current.surface1,
        border = BorderStroke(1.dp, LocalPaseoPalette.current.border),
    ) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = LocalPaseoPalette.current.foregroundMuted)
    }
}
