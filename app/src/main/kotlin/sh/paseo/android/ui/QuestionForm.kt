package sh.paseo.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.paseo.android.core.Question

@Composable
fun QuestionForm(questions: List<Question>, enabled: Boolean, onSubmit: (Map<String, String>) -> Unit) {
    // Primitive lists survive rotation while a question is pending.
    var selected by rememberSaveable { mutableStateOf(List(questions.size) { "" }) }
    var texts by rememberSaveable { mutableStateOf(List(questions.size) { "" }) }
    fun selections(index: Int): Set<Int> = selected[index].split(',').mapNotNull(String::toIntOrNull).toSet()
    questions.forEachIndexed { index, q ->
        Text(q.text, style = MaterialTheme.typography.bodyMedium)
        q.options.forEachIndexed { optionIndex, option ->
            val checked = optionIndex in selections(index)
            fun toggle() {
                val next = if (q.multiSelect) (if (checked) selections(index) - optionIndex else selections(index) + optionIndex) else setOf(optionIndex)
                selected = selected.mapIndexed { i, value -> if (i == index) next.joinToString(",") else value }
                if (!q.multiSelect) texts = texts.mapIndexed { i, value -> if (i == index) "" else value }
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = if (q.multiSelect) Role.Checkbox else Role.RadioButton) { toggle() }.semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }, verticalAlignment = Alignment.CenterVertically) {
                val palette = LocalPaseoPalette.current
                val shape = if (q.multiSelect) RoundedCornerShape(4.dp) else CircleShape
                Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp).size(18.dp)
                    .background(if (checked && q.multiSelect) palette.accent else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .border(1.dp, if (checked) palette.accent else palette.foregroundExtraMuted, shape), contentAlignment = Alignment.Center) {
                    if (checked && q.multiSelect) PaseoGlyph(PaseoIcons.Check, modifier = Modifier.size(12.dp), tint = palette.accentForeground)
                    else if (checked) Box(Modifier.size(8.dp).background(palette.accent, CircleShape))
                }
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (q.showsText) OutlinedTextField(texts[index], { text -> texts = texts.mapIndexed { i, value -> if (i == index) text else value } },
            enabled = enabled, label = { Text(q.placeholder.ifBlank { if (q.options.isEmpty()) "你的回答" else "其他回答" }) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
    }
    Button(onClick = { onSubmit(questions.mapIndexed { i, q -> q.header to q.answer(selections(i), texts[i]) }.toMap()) },
        enabled = enabled && questions.indices.all { questions[it].answered(selections(it), texts[it]) }) { Text("提交回答") }
}
