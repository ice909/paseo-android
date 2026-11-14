@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package sh.paseo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Native counterpart of the official AdaptiveModalSheet confirmation and rename flows. */
@Composable
internal fun HostActionSheet(title: String, confirm: String, onDismiss: () -> Unit,
    onConfirm: suspend () -> Unit, destructive: Boolean = false,
    valid: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPaseoPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val dragThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val height = ((LocalConfiguration.current.screenHeightDp * if (expanded) .9f else .65f).dp - 24.dp - bottomInset).coerceAtLeast(120.dp)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = palette.surface0, contentColor = palette.foreground,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .45f),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(24.dp).clickable(enabled = !busy) { expanded = !expanded }
                .pointerInput(expanded, busy) {
                    var distance = 0f
                    detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, amount ->
                        if (!busy) { change.consume(); distance += amount }
                    }, onDragEnd = {
                        if (!busy) {
                            if (distance < -dragThreshold) expanded = true
                            else if (distance > dragThreshold) { if (expanded) expanded = false else onDismiss() }
                        }
                    })
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp, 4.dp).background(palette.foregroundMuted, RoundedCornerShape(50)))
            }
        }) {
        Column(Modifier.fillMaxWidth().height(height).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = PaseoUiText)
                Box(Modifier.size(32.dp).clickable(enabled = !busy, role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
                    PaseoGlyph(PaseoIcons.X, "关闭", Modifier.size(16.dp), palette.foregroundMuted)
                }
            }
            HorizontalDivider(color = palette.border)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                content()
                error?.let { Text(it, color = palette.destructive, style = PaseoUiText) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaseoButton("取消", onDismiss, Modifier.weight(1f), primary = false, enabled = !busy)
                    Box(Modifier.weight(1f)) {
                        PaseoButton(if (busy) "处理中…" else confirm, {
                            busy = true; error = null
                            scope.launch {
                                try { onConfirm(); onDismiss() }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "操作失败" }
                                finally { busy = false }
                            }
                        }, Modifier.fillMaxWidth(), enabled = valid && !busy, destructive = destructive)
                    }
                }
            }
        }
    }
}
