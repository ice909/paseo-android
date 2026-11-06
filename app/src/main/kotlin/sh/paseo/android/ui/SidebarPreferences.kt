package sh.paseo.android.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import sh.paseo.android.core.WorkspaceSort

internal class SidebarPreferences(context: Context) {
    private val storage = context.getSharedPreferences("sidebar_view", Context.MODE_PRIVATE)
    var sort by mutableStateOf(WorkspaceSort.fromKey(storage.getString("sort", null)))
        private set
    private val orders = mutableStateMapOf<String, List<String>>()

    fun select(value: WorkspaceSort) {
        sort = value
        storage.edit().putString("sort", value.key).apply()
    }

    fun order(host: String, project: String): List<String> {
        val key = JSONArray(listOf(host, project)).toString()
        return orders[key] ?: runCatching {
            val array = JSONArray(storage.getString(key, "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun reorder(host: String, project: String, value: List<String>) {
        val key = JSONArray(listOf(host, project)).toString()
        orders[key] = value
        storage.edit().putString(key, JSONArray(value).toString()).apply()
    }
}

private val WorkspaceSort.label: String get() = when (this) {
    WorkspaceSort.Manual -> "手动"
    WorkspaceSort.Created -> "创建时间"
    WorkspaceSort.LastPrompt -> "最近提示词"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SidebarPreferencesSheet(
    preferences: SidebarPreferences, automaticSortSupported: Boolean, connected: Boolean,
    onDismiss: () -> Unit, onSearch: () -> Unit, onCollapse: () -> Unit,
    onExpand: () -> Unit, onRefresh: () -> Unit,
) {
    var sorting by rememberSaveable { mutableStateOf(false) }
    val palette = LocalPaseoPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface0, contentColor = palette.foreground) {
        BackHandler(sorting) { sorting = false }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sorting) IconButton(onClick = { sorting = false }, modifier = Modifier.size(24.dp)) {
                PaseoGlyph(PaseoIcons.ChevronLeft, "返回", Modifier.size(18.dp), palette.foregroundMuted)
            }
            Text(if (sorting) "排序" else "显示", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (sorting) {
            WorkspaceSort.entries.filter { automaticSortSupported || it == WorkspaceSort.Manual }.forEach { sort ->
                PreferenceRow(sort.label, selected = preferences.sort == sort) { preferences.select(sort); onDismiss() }
            }
            if (!automaticSortSupported) PreferenceRow("请更新主机以排序工作区", enabled = false) {}
        } else {
            PreferenceRow("排序", value = if (automaticSortSupported || preferences.sort == WorkspaceSort.Manual) preferences.sort.label else "",
                navigate = true) { sorting = true }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = palette.border)
            PreferenceRow("搜索工作区") { onSearch(); onDismiss() }
            PreferenceRow("折叠所有项目") { onCollapse(); onDismiss() }
            PreferenceRow("展开所有项目") { onExpand(); onDismiss() }
            PreferenceRow("刷新工作区", enabled = connected) { onRefresh(); onDismiss() }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PreferenceRow(label: String, value: String? = null, navigate: Boolean = false,
    selected: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPaseoPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        .semantics { if (selected != null) this.selected = selected }
        .clickable(enabled = enabled, role = if (selected != null) Role.RadioButton else Role.Button, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp,
            color = palette.foreground.copy(alpha = if (enabled) 1f else .5f))
        if (value != null) Text(value, fontSize = 14.sp, color = palette.foregroundMuted)
        if (navigate) PaseoGlyph(PaseoIcons.ChevronRight, null, Modifier.size(14.dp), palette.foregroundMuted)
        if (selected == true) PaseoGlyph(PaseoIcons.Check, null, Modifier.size(14.dp), palette.foregroundMuted)
    }
}
