package sh.paseo.android.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var grouping by mutableStateOf(storage.getString("grouping", "project") ?: "project")
        private set
    var titleSource by mutableStateOf(storage.getString("title", "title") ?: "title")
        private set
    var showBackground by mutableStateOf(storage.getBoolean("showBackground", false))
        private set
    var projectFilters by mutableStateOf(storage.getStringSet("projects", emptySet()).orEmpty().toSet())
        private set
    var rowItems by mutableStateOf(storage.getStringSet("rowItems", DEFAULT_ROW_ITEMS).orEmpty().toSet())
        private set
    private val orders = mutableStateMapOf<String, List<String>>()

    fun selectSort(value: WorkspaceSort) {
        sort = value
        storage.edit().putString("sort", value.key).apply()
    }

    fun selectGrouping(value: String) {
        grouping = value
        storage.edit().putString("grouping", value).apply()
    }

    fun selectTitleSource(value: String) {
        titleSource = value
        storage.edit().putString("title", value).apply()
    }

    fun toggleBackground() {
        showBackground = !showBackground
        storage.edit().putBoolean("showBackground", showBackground).apply()
    }

    fun toggleProject(projectId: String) {
        projectFilters = if (projectId in projectFilters) projectFilters - projectId else projectFilters + projectId
        storage.edit().putStringSet("projects", projectFilters).apply()
    }

    fun clearProjectFilters() {
        projectFilters = emptySet()
        storage.edit().remove("projects").apply()
    }

    fun toggleRowItem(item: String) {
        rowItems = if (item in rowItems) rowItems - item else rowItems + item
        storage.edit().putStringSet("rowItems", rowItems).apply()
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

    companion object {
        private val DEFAULT_ROW_ITEMS = setOf("host", "changeRequest", "services", "labels")
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
    preferences: SidebarPreferences, projects: List<Pair<String, String>>,
    automaticSortSupported: Boolean, onDismiss: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf("") }
    val palette = LocalPaseoPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface0,
        contentColor = palette.foreground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        BackHandler(page.isNotEmpty()) { page = "" }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page.isNotEmpty()) {
                Box(Modifier.size(32.dp).clickable(role = Role.Button) { page = "" }, contentAlignment = Alignment.CenterStart) {
                    PaseoGlyph(PaseoIcons.ChevronLeft, "返回", Modifier.size(18.dp), palette.foregroundMuted)
                }
            }
            Text(
                when (page) {
                    "grouping" -> "分组"
                    "sort" -> "排序"
                    "title" -> "标题"
                    "show" -> "显示"
                    "projects" -> "项目"
                    else -> "显示"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when (page) {
            "grouping" -> {
                PreferenceRow("项目", selected = preferences.grouping == "project") {
                    preferences.selectGrouping("project"); onDismiss()
                }
                PreferenceRow("状态", selected = preferences.grouping == "status") {
                    preferences.selectGrouping("status"); onDismiss()
                }
            }
            "sort" -> {
                WorkspaceSort.entries.filter { automaticSortSupported || it == WorkspaceSort.Manual }.forEach { sort ->
                    PreferenceRow(sort.label, selected = preferences.sort == sort) {
                        preferences.selectSort(sort); onDismiss()
                    }
                }
                if (!automaticSortSupported) PreferenceRow("请更新主机以排序工作区", enabled = false) {}
            }
            "title" -> {
                PreferenceRow("标题", selected = preferences.titleSource == "title") {
                    preferences.selectTitleSource("title"); onDismiss()
                }
                PreferenceRow("分支名称", selected = preferences.titleSource == "branch") {
                    preferences.selectTitleSource("branch"); onDismiss()
                }
            }
            "show" -> {
                listOf(
                    "branch" to "分支",
                    "project" to "项目",
                    "host" to "主机",
                    "changeRequest" to "拉取请求",
                    "services" to "服务",
                    "labels" to "标签",
                ).forEach { (key, label) ->
                    PreferenceRow(label, selected = key in preferences.rowItems, toggle = true) { preferences.toggleRowItem(key) }
                }
            }
            "projects" -> {
                PreferenceRow("所有项目", selected = preferences.projectFilters.isEmpty(), toggle = true) {
                    preferences.clearProjectFilters()
                }
                projects.forEach { (id, name) ->
                    PreferenceRow(name, selected = id in preferences.projectFilters, toggle = true) { preferences.toggleProject(id) }
                }
            }
            else -> {
                PreferenceRow("分组", value = if (preferences.grouping == "status") "状态" else "项目", navigate = true) { page = "grouping" }
                PreferenceRow("排序", value = if (automaticSortSupported || preferences.sort == WorkspaceSort.Manual) preferences.sort.label else "", navigate = true) {
                    page = "sort"
                }
                PreferenceRow("标题", value = if (preferences.titleSource == "branch") "分支名称" else "标题", navigate = true) { page = "title" }
                PreferenceRow("显示", navigate = true) { page = "show" }
                HorizontalDivider(color = palette.border)
                PreferenceRow("项目", navigate = true) { page = "projects" }
                HorizontalDivider(color = palette.border)
                PreferenceRow("显示后台工作区", selected = preferences.showBackground, toggle = true) { preferences.toggleBackground() }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PreferenceRow(
    label: String,
    value: String? = null,
    navigate: Boolean = false,
    selected: Boolean? = null,
    toggle: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPaseoPalette.current
    Row(
        Modifier.fillMaxWidth()
            .semantics { if (selected != null) this.selected = selected }
            .clickable(enabled = enabled, role = when {
                toggle -> Role.Checkbox
                selected != null -> Role.RadioButton
                else -> Role.Button
            }, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = palette.foreground.copy(alpha = if (enabled) 1f else .5f),
        )
        if (value != null) Text(value, fontSize = 16.sp, color = palette.foregroundMuted)
        if (navigate) PaseoGlyph(PaseoIcons.ChevronRight, null, Modifier.size(18.dp), palette.foregroundMuted)
        if (selected == true) PaseoGlyph(PaseoIcons.Check, null, Modifier.size(18.dp), palette.foregroundMuted)
    }
}
