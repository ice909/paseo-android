package sh.paseo.android.ui

import java.net.URLDecoder

internal data class MarkdownPart(val text: String, val imageSource: String? = null)
internal data class ImageFileTarget(val cwd: String, val path: String)

/** Split image nodes out of prose while leaving inline code and escaped markup alone. */
internal fun markdownImageParts(text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    var cursor = 0
    var plain = 0
    while (cursor < text.length) {
        if (text[cursor] == '\\') { cursor = (cursor + 2).coerceAtMost(text.length); continue }
        if (text[cursor] == '`') {
            val start = cursor
            while (cursor < text.length && text[cursor] == '`') cursor++
            val marker = text.substring(start, cursor)
            val end = text.indexOf(marker, cursor)
            if (end >= 0) cursor = end + marker.length
            continue
        }
        if (!text.startsWith("![", cursor)) { cursor++; continue }
        var closeAlt = cursor + 2
        while (closeAlt < text.length && text[closeAlt] != ']') {
            if (text[closeAlt] == '\\' && closeAlt + 1 < text.length) closeAlt++
            closeAlt++
        }
        if (closeAlt + 1 >= text.length || text[closeAlt + 1] != '(') { cursor++; continue }
        var end = closeAlt + 2
        var depth = 1
        var angle = false
        var quote: Char? = null
        while (end < text.length && depth > 0) {
            val c = text[end]
            if (c == '\\' && end + 1 < text.length) { end += 2; continue }
            if (quote != null) { if (c == quote) quote = null }
            else when {
                c == '<' -> angle = true
                c == '>' && angle -> angle = false
                !angle && (c == '"' || c == '\'') && end > closeAlt + 2 && text[end - 1].isWhitespace() -> quote = c
                !angle && c == '(' -> depth++
                !angle && c == ')' -> depth--
            }
            end++
        }
        if (depth != 0) { cursor++; continue }
        val destination = text.substring(closeAlt + 2, end - 1).trim()
        val source = if (destination.startsWith('<')) destination.substringAfter('<').substringBefore('>')
            else destination.takeWhile { !it.isWhitespace() }
        if (source.isEmpty()) { cursor++; continue }
        if (cursor > plain) parts += MarkdownPart(text.substring(plain, cursor))
        parts += MarkdownPart(text.substring(cursor + 2, closeAlt).replace("\\]", "]"), source.replace("\\(", "(").replace("\\)", ")"))
        cursor = end; plain = end
    }
    if (plain < text.length) parts += MarkdownPart(text.substring(plain))
    return parts
}

private fun decodedPath(value: String): String = try {
    URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
} catch (_: IllegalArgumentException) { value }

private fun normalizedPath(value: String): String {
    val path = value.replace('\\', '/')
    val drive = Regex("^[A-Za-z]:/").find(path)?.value
    val prefix = when { drive != null -> drive.uppercase(); path.startsWith("//") -> "//"; path.startsWith('/') -> "/"; else -> "" }
    val segments = mutableListOf<String>()
    path.removePrefix(drive ?: prefix).split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty() && segments.last() != "..") segments.removeAt(segments.lastIndex) else if (prefix.isEmpty()) segments += part
            else -> segments += part
        }
    }
    return prefix + segments.joinToString("/")
}

/** Resolve a Markdown image against the remote filesystem, never the Android device. */
internal fun imageFileTarget(source: String, workspaceRoot: String): ImageFileTarget? {
    var value = source.trim()
    if (value.isEmpty() || Regex("^(https?:|data:|blob:)", RegexOption.IGNORE_CASE).containsMatchIn(value)) return null
    value = value.substringBefore('#').substringBefore('?')
    if (value.startsWith("file://", ignoreCase = true)) {
        val rest = value.substring(7)
        value = if (rest.startsWith('/')) rest else "//$rest"
        if (Regex("^/[A-Za-z]:[/\\\\]").containsMatchIn(value)) value = value.drop(1)
    }
    value = decodedPath(value)
    if (value == "~" || value.startsWith("~/") || value.startsWith("~\\")) return ImageFileTarget("~", value)
    val path = normalizedPath(value)
    if (path.isEmpty()) return null
    val drive = Regex("^[A-Za-z]:/").find(path)?.value
    val absolute = path.startsWith('/') || drive != null
    val workspace = normalizedPath(workspaceRoot)
    if (!absolute) return workspace.takeIf { it.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(it) }?.let { ImageFileTarget(it, path) }
    val within = workspace.isNotEmpty() && (path == workspace || path.startsWith(workspace.trimEnd('/') + "/"))
    val root = when {
        within -> workspace
        drive != null -> drive
        path.startsWith("//") -> path.removePrefix("//").split('/').take(2).joinToString("/", prefix = "//")
        else -> "/"
    }
    return ImageFileTarget(root, path)
}
