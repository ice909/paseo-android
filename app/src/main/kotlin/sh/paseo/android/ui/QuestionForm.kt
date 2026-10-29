package sh.paseo.android.ui

import androidx.compose.foundation.clickable
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
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                if (q.multiSelect) Checkbox(checked, { toggle() }, enabled = enabled) else RadioButton(checked, { toggle() }, enabled = enabled)
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
