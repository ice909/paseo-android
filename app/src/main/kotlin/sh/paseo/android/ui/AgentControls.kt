@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import sh.paseo.android.core.*

/** Compact mobile controls: one model/effort trigger, with settings inside the model sheet. */
@Composable
fun CompactAgentControls(agent: Agent, models: List<ModelOption>, enabled: Boolean,
    loading: Boolean, modelError: String?, actionError: String?, onRetry: () -> Unit, modifier: Modifier = Modifier,
    onModel: (String) -> Unit, onThinking: (String) -> Unit, onMode: (String) -> Unit,
    onFeature: (String, JsonElement) -> Unit) {
    val palette = LocalPaseoPalette.current
    val selection = resolveAgentModelSelection(agent, models)
    var open by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("") }
    val thinking = selection.thinking
    Row(modifier.height(28.dp).clickable(role = Role.Button) { page = ""; open = true }
        .semantics { contentDescription = "模型和思考强度" }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PaseoGlyph(PaseoIcons.provider(agent.provider), modifier = Modifier.size(16.dp), tint = palette.foregroundMuted)
        Text(selection.modelLabel.substringAfterLast('/'), modifier = Modifier.weight(1f, fill = false),
            fontSize = 14.sp, color = palette.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (thinking != null) Text(thinking.displayName, fontSize = 14.sp, color = palette.foregroundExtraMuted, maxLines = 1)
    }
    if (!open) return
    // Keep nested choices in the same sheet so Back returns to the settings, not to the workspace.
    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface1, contentColor = palette.foreground) {
        BackHandler(page.isNotEmpty()) { page = "" }
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.isNotEmpty()) IconButton(onClick = { page = "" }) { PaseoGlyph(PaseoIcons.ArrowLeft, "返回模型设置", Modifier.size(18.dp)) }
                Text(when (page) { "model" -> "模型"; "thinking" -> "思考强度"; "mode" -> "权限模式"; "" -> "模型设置"; else -> agent.features.find { it.string("id") == page }?.string("label").orEmpty() }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { open = false }) { PaseoGlyph(PaseoIcons.X, "关闭模型设置", Modifier.size(18.dp)) }
            }
            if (actionError != null) Text(actionError, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (page.isEmpty()) {
                ControlSettingRow("模型", if (loading) "加载中…" else selection.modelLabel, PaseoIcons.provider(agent.provider), enabled && !loading) { page = "model" }
                if (modelError != null) {
                    Text(modelError, Modifier.padding(horizontal = 24.dp), color = palette.foregroundMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry, enabled = enabled && !loading, modifier = Modifier.padding(start = 12.dp)) { Text("重新加载模型") }
                }
                if (thinking != null) ControlSettingRow("思考强度", thinking.displayName, PaseoIcons.Brain, enabled) { page = "thinking" }
                if (agent.availableModes.isNotEmpty()) ControlSettingRow("权限模式", agent.availableModes.find { it.id == agent.currentModeId }?.displayName ?: "默认", PaseoIcons.Shield, enabled) { page = "mode" }
                agent.features.forEach { feature ->
                    val id = feature.string("id")
                    if (feature.string("type") == "toggle") ListItem(
                        headlineContent = { Text(feature.string("label")) },
                        trailingContent = { Switch(feature.bool("value"), { onFeature(id, JsonPrimitive(it)) }, enabled = enabled) },
                        colors = ListItemDefaults.colors(containerColor = palette.surface1))
                    else if (feature.string("type") == "select") {
                        val value = feature.array("options").mapNotNull { it as? JsonObject }.find { it.string("id") == feature.string("value") }
                        ControlSettingRow(feature.string("label"), value?.string("label").orEmpty(), null, enabled) { page = id }
                    }
                }
            } else {
                val choices = when (page) {
                    "model" -> models.filter { it.isSelectable }.map { ModeOption(it.id, it.displayName) }
                    "thinking" -> selection.thinkingOptions
                    "mode" -> agent.availableModes
                    else -> agent.features.find { it.string("id") == page }?.array("options").orEmpty().mapNotNull { it as? JsonObject }.map { ModeOption(it.string("id"), it.string("label"), it.string("description").ifBlank { null }) }
                }
                val selected = when (page) { "model" -> selection.modelId; "thinking" -> thinking?.id; "mode" -> agent.currentModeId; else -> agent.features.find { it.string("id") == page }?.string("value") }
                choices.forEach { option ->
                    ListItem(headlineContent = { Text(option.displayName) },
                        supportingContent = option.description?.let { description -> { Text(description) } },
                        trailingContent = { if (option.id == selected) PaseoGlyph(PaseoIcons.Check, modifier = Modifier.size(16.dp)) },
                        colors = ListItemDefaults.colors(containerColor = palette.surface1),
                        modifier = Modifier.clickable(enabled = enabled) {
                            when (page) { "model" -> onModel(option.id); "thinking" -> onThinking(option.id); "mode" -> onMode(option.id); else -> onFeature(page, JsonPrimitive(option.id)) }
                            page = ""
                        })
                }
                if (choices.isEmpty()) Text("没有可用选项", Modifier.padding(24.dp), color = palette.foregroundMuted)
            }
        }
    }
}

@Composable
private fun ControlSettingRow(label: String, value: String, icon: Int?, enabled: Boolean, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, supportingContent = { Text(value) },
        leadingContent = icon?.let { { PaseoGlyph(it, modifier = Modifier.size(18.dp)) } },
        trailingContent = { PaseoGlyph(PaseoIcons.ChevronRight, modifier = Modifier.size(16.dp)) },
        colors = ListItemDefaults.colors(containerColor = LocalPaseoPalette.current.surface1),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick))
}
