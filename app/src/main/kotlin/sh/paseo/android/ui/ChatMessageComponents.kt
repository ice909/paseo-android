@file:Suppress("DEPRECATION")
package sh.paseo.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.layout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.paseo.android.core.*

@Composable
fun ChatComposerMenu(behavior: SendBehavior, onBehavior: (SendBehavior) -> Unit, onFiles: () -> Unit, onImages: () -> Unit, onVoice: () -> Unit = {}, onForge: () -> Unit = {}, collapse: Boolean = true, onCollapse: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) { PaseoGlyph(PaseoIcons.Plus, "添加附件和发送选项", Modifier.size(18.dp)) }
        PaseoDropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("添加图片") }, leadingIcon = { PaseoGlyph(PaseoIcons.ImagePlus) }, onClick = { open = false; onImages() })
            DropdownMenuItem(text = { Text("添加文件") }, leadingIcon = { PaseoGlyph(PaseoIcons.Paperclip) }, onClick = { open = false; onFiles() })
            DropdownMenuItem(text = { Text("Issue / Pull Request") }, leadingIcon = { PaseoGlyph(PaseoIcons.Link) }, onClick = { open = false; onForge() })
            DropdownMenuItem(text = { Text("语音对话") }, leadingIcon = { PaseoGlyph(PaseoIcons.MicVocal) }, onClick = { open = false; onVoice() })
            DropdownMenuItem(text = { Text("折叠已完成的对话过程") }, trailingIcon = { if (collapse) PaseoGlyph(PaseoIcons.Check) }, onClick = { open = false; onCollapse() })
            HorizontalDivider()
            Text("运行中发送消息", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = LocalPaseoPalette.current.foregroundMuted)
            SendBehavior.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) },
                trailingIcon = { if (behavior == choice) PaseoGlyph(PaseoIcons.Check) }, onClick = { onBehavior(choice); open = false }) }
        }
    }
}

@Composable
fun ChatAttachmentTray(attachments: List<ChatAttachment>, onRemove: ((String) -> Unit)? = null) {
    var preview by remember { mutableStateOf<ChatAttachment?>(null) }
    if (attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            Surface(shape = RoundedCornerShape(8.dp), color = LocalPaseoPalette.current.surface2, border = BorderStroke(1.dp, LocalPaseoPalette.current.border)) {
                Row(Modifier.widthIn(max = 200.dp).clickable { preview = attachment }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (attachment.isImage) {
                        val bitmap = attachmentBitmap(attachment.localPath, 160)
                        if (bitmap != null) Image(bitmap, attachment.name, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
                        else PaseoGlyph(PaseoIcons.ImagePlus)
                    } else PaseoGlyph(when (attachment.payload?.string("type")) {
                        "github_issue", "forge_issue" -> PaseoIcons.CircleDot
                        "github_pr", "forge_change_request" -> PaseoIcons.GitPullRequest
                        "review" -> PaseoIcons.MessageSquareCode
                        else -> if (attachment.payload?.obj("externalResource")?.isNotEmpty() == true) PaseoIcons.CircleDot else PaseoIcons.FileText
                    }, modifier = Modifier.size(16.dp), tint = LocalPaseoPalette.current.foregroundMuted)
                    Text(attachment.name, Modifier.weight(1f, fill = false).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (onRemove != null) IconButton(onClick = { onRemove(attachment.id) }, modifier = Modifier.size(28.dp)) { PaseoGlyph(PaseoIcons.X, "移除附件：${attachment.name}", Modifier.size(14.dp)) }
                }
            }
        }
    }
    preview?.let { AttachmentPreview(it) { preview = null } }
}

@Composable
private fun attachmentBitmap(path: String, maxEdge: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path, maxEdge) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
private fun AttachmentPreview(attachment: ChatAttachment, onDismiss: () -> Unit) {
    if (attachment.isImage) Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LocalPaseoPalette.current.surface0) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.name, Modifier.weight(1f).padding(16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { PaseoGlyph(PaseoIcons.X, "关闭预览") }
                }
                val bitmap = attachmentBitmap(attachment.localPath, 2048)
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)).pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
                }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap, attachment.name, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                    else Text("无法读取图片")
                }
            }
        }
    } else {
        val text by produceState<String?>(null, attachment.id) {
            value = withContext(Dispatchers.IO) {
                attachment.payload?.string("text")?.ifBlank { null } ?: if (attachment.localPath.isNotBlank() &&
                    (attachment.mimeType.startsWith("text/") || attachment.name.substringAfterLast('.') in setOf("kt", "java", "js", "ts", "json", "md", "py", "sh", "xml", "yml", "yaml", "toml", "rs", "go", "css", "html")))
                    runCatching { File(attachment.localPath).inputStream().use { val bytes = ByteArray(128 * 1024)
                        var length = 0
                        while (length < bytes.size) { val count = it.read(bytes, length, bytes.size - length); if (count < 0) break; length += count }
                        bytes.decodeToString(endIndex = length) } }.getOrNull() else null
            }
        }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(attachment.name) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("${attachment.mimeType} · ${attachment.size} B", style = MaterialTheme.typography.bodySmall)
                text?.let { SelectionContainer { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) } }
                attachment.payload?.string("path")?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    }
}

@Composable
fun ChatUserMessage(row: TimelineRow, localAttachments: List<ChatAttachment>, agent: Agent, connected: Boolean,
    vm: sh.paseo.android.AppViewModel, key: ConversationKey, onRetryPending: () -> Unit = {}, onEditPending: () -> Unit = {}, onRewind: (String) -> Unit) {
    val palette = LocalPaseoPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var rewindMenu by remember { mutableStateOf(false) }
    val attachments = localAttachments.ifEmpty { row.attachments.mapIndexed { index, payload -> ChatAttachment("${row.messageId}:$index",
        payload.string("fileName").ifBlank { payload.string("title").ifBlank { payload.string("type") } }, payload.string("mimeType"), payload = payload) } }
    val pending = row.status in setOf("sending", "accepted", "queued")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(color = palette.surface3, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp), modifier = Modifier.widthIn(max = 680.dp)) {
            Column(Modifier.padding(16.dp)) {
                ChatAttachmentTray(attachments)
                if (localAttachments.none { it.isImage }) row.images.forEachIndexed { index, image ->
                    val data = image.string("data")
                    if (data.isNotBlank()) MessageImage("data:${image.string("mimeType").ifBlank { "image/png" }};base64,$data", "图片", vm, connected, key.hostId, agent.cwd, "${agent.id}:${row.key}:$index", thumbnail = true)
                }
                if (row.text.isNotEmpty()) SelectionContainer {
                    Text(row.text, style = PaseoUiText, color = palette.foreground, maxLines = if (expanded) Int.MAX_VALUE else 12,
                        overflow = TextOverflow.Ellipsis, onTextLayout = { overflow = it.hasVisualOverflow })
                }
                if (overflow || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "展开消息") }
            }
        }
        if (row.status == "unconfirmed") Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发送结果尚未确认", color = palette.foregroundMuted, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onEditPending) { Text("编辑") }
            TextButton(onClick = onRetryPending, enabled = connected) { Text("重试发送") }
        } else if (pending) Text(when (row.status) { "sending" -> "发送中…"; "queued" -> "主机已排队"; else -> "已发送" }, Modifier.padding(top = 4.dp), color = palette.foregroundExtraMuted, style = MaterialTheme.typography.labelSmall)
        else if (row.text.isNotBlank()) Row(Modifier.padding(top = 8.dp).height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val time = remember(row.timestamp) { runCatching { formatMessageTime(Instant.parse(row.timestamp).toEpochMilli()) }.getOrDefault("") }
            Text(time, fontSize = 13.sp, color = palette.foregroundMuted)
            val modes = listOf("conversation" to "回退对话", "files" to "回退文件", "both" to "回退对话和文件").filter { (mode, _) ->
                agent.capabilities.bool(when (mode) { "conversation" -> "supportsRewindConversation"; "files" -> "supportsRewindFiles"; else -> "supportsRewindBoth" }) }
            if (row.messageId.isNotBlank() && modes.isNotEmpty()) Box {
                IconButton(onClick = { rewindMenu = true }, enabled = connected, modifier = Modifier.size(22.dp)) { PaseoGlyph(PaseoIcons.Undo2, "回退到这条消息", Modifier.size(14.dp)) }
                PaseoDropdownMenu(rewindMenu, { rewindMenu = false }) {
                    modes.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { rewindMenu = false; onRewind(mode) }) }
                }
            }
            MessageCopyButton(row.text, modifier = Modifier.layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout((child.width - 4.dp.roundToPx()).coerceAtLeast(0), child.height) { child.placeRelative(0, 0) }
            })
        }
    }
}

@Composable
fun MessageCopyButton(text: String, modifier: Modifier = Modifier, label: String = "复制消息") {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    var copyVersion by remember(text) { mutableIntStateOf(0) }
    // A repeated tap restarts the official 1.5-second checkmark feedback.
    LaunchedEffect(copyVersion) { if (copyVersion > 0) { kotlinx.coroutines.delay(1500); copied = false } }
    Box(modifier.size(22.dp).clickable(role = Role.Button) {
        if (text.isNotEmpty()) { clipboard.setText(AnnotatedString(text)); copied = true; copyVersion++ }
    }, contentAlignment = Alignment.Center) {
        PaseoGlyph(if (copied) PaseoIcons.Check else PaseoIcons.Copy, if (copied) "已复制" else label, Modifier.size(14.dp), LocalPaseoPalette.current.foregroundMuted)
    }
}

@Composable
fun ConversationAudioPanel(vm: sh.paseo.android.AppViewModel, state: sh.paseo.android.data.ConversationAudioState) {
    if (!state.active && state.error == null) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = LocalPaseoPalette.current.surface2) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (!state.active) "语音不可用" else when (state.phase) { "starting" -> "正在启动语音…"; "uploading" -> "正在转写…"; "playing" -> "正在播放…"; "failed" -> "语音失败"; else -> if (state.mode == "voice") (if (state.muted) "麦克风已静音" else "正在聆听…") else "录音中 ${state.duration / 60}:${(state.duration % 60).toString().padStart(2, '0')}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = vm.audio::cancel, modifier = Modifier.size(28.dp)) { PaseoGlyph(PaseoIcons.X, "取消语音", Modifier.size(16.dp)) }
            }
            if (state.phase in setOf("recording", "listening")) LinearProgressIndicator(progress = { (state.volume * 8).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (state.partial.isNotBlank()) Text(state.partial, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.mode == "dictation" && state.phase == "recording") {
                    TextButton(onClick = { vm.audio.confirm(false) }) { Text("转为文字") }
                    TextButton(onClick = { vm.audio.confirm(true) }) { Text("转写并发送") }
                }
                if (state.mode == "dictation" && state.phase == "failed") TextButton(onClick = vm.audio::retry) { Text("重试转写") }
                if (state.mode == "voice" && state.phase != "failed") {
                    TextButton(onClick = vm.audio::mute) { Text(if (state.muted) "取消静音" else "静音") }
                    TextButton(onClick = vm.audio::interrupt) { Text("打断") }
                }
            }
        }
    }
}

@Composable
fun ForgeAttachmentPicker(vm: sh.paseo.android.AppViewModel, agent: Agent, modern: Boolean, onSelect: (ChatAttachment) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<kotlinx.serialization.json.JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(200); loading = true; error = null
        try { items = vm.repository.forgeSearch(agent.cwd, query) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Issue / Pull Request") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索编号、标题或链接") }, singleLine = true)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                items.forEach { item -> ListItem(colors = paseoListItemColors(), headlineContent = { Text("#${item.long("number")} ${item.string("title")}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.string("url"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.clickable { onSelect(forgeAttachment(item, modern)) }) }
                if (!loading && error == null && items.isEmpty()) Text("没有匹配项", Modifier.padding(vertical = 12.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
