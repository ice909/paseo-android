package sh.paseo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import sh.paseo.android.appearance.ThemePreference

@Composable
fun AppearanceSettingsScreen(preference: ThemePreference, onTheme: (ThemePreference) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("主题", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.semantics { contentDescription = "主题：${preference.label}" }) {
                            ThemeLeading(preference)
                            Text(preference.label, Modifier.padding(horizontal = 10.dp))
                            PaseoGlyph(PaseoIcons.ChevronDown, null, Modifier.size(16.dp))
                        }
                        PaseoDropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 200.dp)) {
                            ThemePreference.entries.forEach { option ->
                                if (option == ThemePreference.ZINC) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(text = { Text(option.label) },
                                    leadingIcon = { ThemeLeading(option) },
                                    trailingIcon = { if (option == preference) PaseoGlyph(PaseoIcons.Check, "已选择", Modifier.size(18.dp)) },
                                    modifier = Modifier.semantics { contentDescription = "选择主题：${option.label}" },
                                    onClick = { onTheme(option); expanded = false })
                            }
                        }
                    }
                }
                Text(if (preference == ThemePreference.AUTO) "跟随系统，在 Light 和 Dark 之间自动切换。" else "应用内所有界面立即使用此主题。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = LocalPaseoPalette.current.border)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("预览", style = MaterialTheme.typography.titleSmall)
                AppearancePreview()
            }
        }
    }
}

@Composable
private fun ThemeLeading(theme: ThemePreference) {
    val icon = when (theme) {
        ThemePreference.LIGHT -> PaseoIcons.Sun
        ThemePreference.DARK -> PaseoIcons.Moon
        ThemePreference.AUTO -> PaseoIcons.Monitor
        else -> null
    }
    if (icon != null) PaseoGlyph(icon, null, Modifier.size(18.dp), tint = LocalPaseoPalette.current.foregroundMuted)
    else Box(Modifier.size(16.dp).background(theme.swatch!!, CircleShape).border(1.dp, LocalPaseoPalette.current.border, CircleShape))
}

/** Static sample uses the same semantic tokens as the real sidebar, chat, diff and terminal. */
@Composable
private fun AppearancePreview() {
    val palette = LocalPaseoPalette.current
    Surface(shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        color = palette.surfaceWorkspace, contentColor = palette.foreground,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "主题预览" }) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier.width(76.dp).fillMaxHeight().background(palette.surfaceSidebar).padding(horizontal = 10.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("paseo", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                listOf("工作区", "应用", "文档").forEachIndexed { index, text ->
                    Text(text, fontSize = 10.sp, color = if (index == 1) palette.foreground else palette.foregroundMuted,
                        modifier = Modifier.fillMaxWidth().background(if (index == 1) palette.surface2 else palette.surfaceSidebar, RoundedCornerShape(4.dp)).padding(vertical = 6.dp, horizontal = 4.dp))
                }
            }
            Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("修复登录页面", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("帮我检查一下登录流程。", fontSize = 12.sp, color = palette.foreground,
                    modifier = Modifier.fillMaxWidth().background(palette.surface3, RoundedCornerShape(6.dp)).padding(10.dp))
                Text("已完成修改，所有检查通过。", fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaseoGlyph(PaseoIcons.CircleCheck, null, Modifier.size(13.dp), tint = palette.statusSuccess)
                    Text("更新文件", fontSize = 11.sp, color = palette.foregroundMuted)
                    Text("+12", fontSize = 11.sp, color = palette.statusSuccess)
                    Text("−3", fontSize = 11.sp, color = palette.statusDanger)
                }
                Column(Modifier.fillMaxWidth().background(palette.surface2, RoundedCornerShape(6.dp)).padding(10.dp)) {
                    Text("− const ready = false", color = palette.diffDeletion, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("+ const ready = true", color = palette.diffAddition, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Text("$ paseo\nReady.", color = palette.terminalForeground, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().background(palette.terminalBackground, RoundedCornerShape(6.dp)).padding(10.dp))
            }
        }
    }
}
