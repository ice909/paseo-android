package sh.paseo.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.graphics.Color
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
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.Agent
import sh.paseo.android.core.ConversationKey
import sh.paseo.android.core.PaseoState

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
    return result.flatMap { block ->
        if (block.type !in setOf("paragraph", "heading", "quote")) listOf(block)
        else markdownImageParts(block.text).mapNotNull { part ->
            if (part.imageSource != null) Block("image", part.imageSource, part.text)
            else part.text.trim('\n').takeIf { it.isNotBlank() }?.let { block.copy(text = it) }
        }
    }
}

private val inlinePattern = Regex("""(`[^`]+`|\*\*[^*]+\*\*|\*[^*\n]+\*|\[[^\]]+\]\(https?://[^\s)]+\)|https?://[^\s<>]+)""")
private fun inlineText(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var end = 0
    inlinePattern.findAll(text).forEach { match ->
        append(text.substring(end, match.range.first))
        val value = match.value
        when {
            value.startsWith("[") -> {
                val label = value.substringAfter('[').substringBefore("](")
                val url = value.substringAfter("](").dropLast(1)
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor)))) { append(label) }
            }
            value.startsWith("http") -> {
                val url = value.trimEnd('.', ',', ';', ')')
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor)))) { append(url) }
                append(value.substring(url.length))
            }
            value.startsWith('`') -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(value.drop(1).dropLast(1)) }
            value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value.drop(2).dropLast(2)) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(value.drop(1).dropLast(1)) }
        }
        end = match.range.last + 1
    }
    append(text.substring(end))
}

@Composable
fun MarkdownText(text: String, vm: AppViewModel, state: PaseoState, agent: Agent, key: ConversationKey, occurrence: String) {
    MarkdownContent(text) { source, alt, index ->
        MessageImage(source, alt, vm, state.connected, key.hostId, agent.cwd, "$occurrence:$index")
    }
}

@Composable
fun MarkdownText(text: String) {
    MarkdownContent(text) { source, alt, _ -> Text(alt.ifBlank { source }, color = LocalPaseoPalette.current.foregroundMuted) }
}

@Composable
private fun MarkdownContent(text: String, image: @Composable (String, String, Int) -> Unit) {
    val palette = LocalPaseoPalette.current
    // Parsing runs away from the UI thread; lazy timeline rows retain their stable identities.
    val blocks by produceState(emptyList<Block>(), text) { value = withContext(Dispatchers.Default) { parseBlocks(text) } }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEachIndexed { index, block -> when (block.type) {
                "code" -> CodeBlock(block.text, language = block.language)
                "heading" -> Text(inlineText(block.text, palette.accentBright), style = PaseoUiText.copy(fontSize = when (block.language) {
                    "1" -> 24.sp; "2" -> 21.sp; "3" -> 18.sp; "4" -> 17.sp; else -> 15.sp
                }, lineHeight = when (block.language) { "1" -> 31.sp; "2" -> 27.sp; "3" -> 23.sp; else -> 20.sp }), fontWeight = FontWeight.Bold)
                "quote" -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) {
                    Text(inlineText(block.text, palette.accentBright), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                "divider" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                "image" -> image(block.text, block.language, index)
                else -> Text(inlineText(block.text, palette.accentBright), style = PaseoUiText, lineHeight = 21.sp)
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
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(36.dp)) { PaseoGlyph(PaseoIcons.Copy, "复制代码", Modifier.size(15.dp)) }
            }
            SelectionContainer {
                Text(if (expanded) code else code.take(16_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            }
            if (!expanded && code.length > 16_000) TextButton(onClick = { expanded = true }) { Text("展开完整内容") }
        }
    }
}
