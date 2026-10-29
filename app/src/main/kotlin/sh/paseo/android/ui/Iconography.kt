package sh.paseo.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import sh.paseo.android.core.string
import sh.paseo.android.core.bool

/** Android vectors preserve the official SVG geometry without an icon-font dependency. */
@Composable
fun PaseoGlyph(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(icon), description, modifier.size(18.dp), tint = tint)
}

@Composable
fun FileGlyph(name: String, directory: Boolean, modifier: Modifier = Modifier) {
    PaseoGlyph(if (directory) PaseoIcons.Folder else PaseoIcons.file(name), modifier = modifier.size(16.dp),
        tint = if (directory) LocalPaseoPalette.current.foregroundMuted else Color.Unspecified)
}

/** Mirrors official utils/tool-call-icon-name.ts; unknown tools use Wrench. */
@DrawableRes
fun toolCallIcon(name: String, detail: JsonObject): Int {
    val lower = name.trim().lowercase()
    val type = detail.string("type")
    if (type == "plain_text" && detail.string("icon").isNotEmpty()) return when (detail.string("icon")) {
        "square_terminal" -> PaseoIcons.SquareTerminal
        "eye" -> PaseoIcons.Eye
        "pencil" -> PaseoIcons.Pencil
        "search" -> PaseoIcons.Search
        "bot" -> PaseoIcons.Bot
        "sparkles" -> PaseoIcons.Sparkles
        "brain" -> PaseoIcons.Brain
        "mic_vocal" -> PaseoIcons.MicVocal
        else -> PaseoIcons.Wrench
    }
    if (lower == "thinking" && (type.isEmpty() || type == "unknown")) return PaseoIcons.Brain
    if (lower == "speak") return PaseoIcons.MicVocal
    val segments = lower.split("__").filter { it.isNotEmpty() }
    val leaf = Regex("[a-z0-9]+").findAll(lower).lastOrNull()?.value
    val paseo = if ("__" in lower) segments.size >= 3 && segments[0] == "mcp" && (segments[1] == "paseo" || segments[1].startsWith("paseo_"))
        else "." in lower && lower.substringBefore('.').let { it == "paseo" || it.startsWith("paseo_") }
    if (paseo && leaf != "speak") return PaseoIcons.Paseo
    if (lower == "task") return PaseoIcons.Bot
    return when (type) {
        "shell", "worktree_setup" -> PaseoIcons.SquareTerminal
        "read" -> PaseoIcons.Eye
        "edit", "write" -> PaseoIcons.Pencil
        "search", "fetch" -> PaseoIcons.Search
        "sub_agent" -> PaseoIcons.Bot
        "plan" -> PaseoIcons.Brain
        else -> PaseoIcons.Wrench
    }
}

@DrawableRes
fun fileChangeIcon(change: String): Int = when (change) {
    "added" -> PaseoIcons.SquarePlus
    "deleted" -> PaseoIcons.SquareMinus
    else -> PaseoIcons.SquareDot
}

/** Official git/file-header-presentation.ts reads these flags, not the payload's status field. */
fun diffFileChangeKind(file: JsonObject): String = when {
    file.bool("isNew") -> "added"
    file.bool("isDeleted") -> "deleted"
    else -> "modified"
}
