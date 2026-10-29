package sh.paseo.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MessageComposer(value: String, onValueChange: (String) -> Unit, placeholder: String,
    enabled: Boolean = true, canSend: Boolean, sending: Boolean, onSend: () -> Unit,
    running: Boolean = false, onStop: () -> Unit = {}, sendDescription: String = "发送消息", options: @Composable RowScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 9.dp)) {
                BasicTextField(value, onValueChange, enabled = enabled, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp),
                    decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = options)
                if (running && value.isBlank()) FilledIconButton(onClick = onStop, enabled = canSend, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Stop, "停止生成", Modifier.size(17.dp)) }
                else FilledIconButton(onClick = onSend, enabled = canSend && !sending, modifier = Modifier.size(34.dp)) {
                    if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.ArrowUpward, sendDescription, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun ComposerChoice(value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
            Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChoose(id); expanded = false }) }
        }
    }
}
