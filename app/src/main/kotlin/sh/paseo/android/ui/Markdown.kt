package sh.paseo.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Block(val type: String, val text: String, val language: String = "")
private fun parseBlocks(text: String): List<Block> {
    val result = mutableListOf<Block>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var fenced = false
    var language = ""
    fun flush() { if (paragraph.isNotEmpty()) { result.add(Block("paragraph", paragraph.toString().trimEnd())); paragraph.clear() } }
    text.lineSequence().forEach { line ->
        when {
            line.trimStart().startsWith("```") -> {
                flush()
                if (fenced) { result.add(Block("code", code.toString().trimEnd('\n'), language)); code.clear() }
                else language = line.trim().removePrefix("```").trim()
                fenced = !fenced
            }
            fenced -> code.append(line).append('\n')
            line.isBlank() -> flush()
            line.startsWith('#') && line.takeWhile { it == '#' }.length in 1..6 && line.contains(' ') -> {
                flush(); result.add(Block("heading", line.substringAfter(' '), line.takeWhile { it == '#' }.length.toString()))
            }
            line.startsWith("> ") -> { flush(); result.add(Block("quote", line.removePrefix("> "))) }
            line == "---" || line == "***" -> { flush(); result.add(Block("divider", "")) }
            else -> paragraph.append(line).append('\n')
        }
    }
    flush()
    if (fenced) result.add(Block("code", code.toString().trimEnd('\n'), language))
    return result
}

private val inlinePattern = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*\\n]+\\*)")
private fun inlineText(text: String): AnnotatedString = buildAnnotatedString {
    var end = 0
    inlinePattern.findAll(text).forEach { match ->
        append(text.substring(end, match.range.first))
        val value = match.value
        when {
            value.startsWith('`') -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(value.drop(1).dropLast(1)) }
            value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value.drop(2).dropLast(2)) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(value.drop(1).dropLast(1)) }
        }
        end = match.range.last + 1
    }
    append(text.substring(end))
}

@Composable
fun MarkdownText(text: String) {
    // Parsing runs away from the UI thread; lazy timeline rows retain their stable identities.
    val blocks by produceState(emptyList<Block>(), text) { value = withContext(Dispatchers.Default) { parseBlocks(text) } }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { block -> when (block.type) {
                "code" -> CodeBlock(block.text, language = block.language)
                "heading" -> Text(inlineText(block.text), style = when (block.language) {
                    "1" -> MaterialTheme.typography.headlineSmall
                    "2" -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }, fontWeight = FontWeight.SemiBold)
                "quote" -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) {
                    Text(inlineText(block.text), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                "divider" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                else -> Text(inlineText(block.text), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
            } }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, language: String = "") {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(code.length > 16_000) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(language.ifBlank { "text" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.ContentCopy, "复制代码", Modifier.size(15.dp)) }
            }
            SelectionContainer {
                Text(if (expanded) code else code.take(16_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            }
            if (!expanded && code.length > 16_000) TextButton(onClick = { expanded = true }) { Text("展开完整内容") }
        }
    }
}
