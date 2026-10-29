@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mirrors new-workspace/form/field-trigger.tsx, including its compact 56 dp height. */
@Composable
internal fun WorkspaceField(label: String, value: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val p = LocalPaseoPalette.current
    Row(Modifier.fillMaxWidth().height(56.dp).clipWorkspaceField().background(p.surface1)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PaseoText(label, color = p.foregroundMuted, style = PaseoUiText.copy(fontSize = 12.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
                PaseoText(value, maxLines = 1, color = p.foreground, style = PaseoUiText.copy(fontSize = 14.sp))
            }
        }
        PaseoGlyph(PaseoIcons.ChevronDown, null, Modifier.offset(y = 1.dp).size(14.dp), p.foregroundExtraMuted)
    }
}

private fun Modifier.clipWorkspaceField() = clip(RoundedCornerShape(12.dp))

@Composable
internal fun WorkspacePicker(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaseoPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface0, contentColor = p.foreground, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !p.dark, isAppearanceLightNavigationBars = !p.dark)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PaseoText(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss) { PaseoGlyph(PaseoIcons.X, "关闭选择面板", Modifier.size(20.dp)) }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 20.dp), content = content)
    }
}

@Composable
internal fun WorkspaceOption(label: String, selected: Boolean = false, detail: String? = null, icon: Int = PaseoIcons.Folder, onClick: () -> Unit) {
    val p = LocalPaseoPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clipWorkspaceField().clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaseoGlyph(icon, null, Modifier.size(16.dp), p.foregroundMuted)
        Column(Modifier.weight(1f)) {
            PaseoText(label, maxLines = 1, color = p.foreground, style = PaseoUiText.copy(fontSize = 14.sp))
            if (!detail.isNullOrBlank()) PaseoText(detail, maxLines = 1, color = p.foregroundMuted, style = PaseoUiText.copy(fontSize = 12.sp))
        }
        if (selected) PaseoGlyph(PaseoIcons.Check, null, Modifier.size(16.dp), p.foregroundMuted)
    }
}

@Composable
internal fun WorkspaceSearch(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val p = LocalPaseoPalette.current
    BasicTextField(value, onValueChange, singleLine = true, textStyle = PaseoUiText.copy(color = p.foreground),
        cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clipWorkspaceField()
            .background(p.surface1).padding(12.dp).semantics { contentDescription = placeholder },
        decorationBox = { inner -> Box { if (value.isEmpty()) PaseoText(placeholder, color = p.foregroundExtraMuted); inner() } })
}
