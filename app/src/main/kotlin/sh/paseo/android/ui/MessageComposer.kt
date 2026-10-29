@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package sh.paseo.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.paseo.android.core.SendBehavior

@Composable
fun MessageComposer(value: String, onValueChange: (String) -> Unit, placeholder: String,
    enabled: Boolean = true, canSend: Boolean, sending: Boolean, onSend: () -> Unit,
    returnIcon: Boolean = false, running: Boolean = false, onStop: () -> Unit = {}, sendDescription: String = "发送消息",
    modifier: Modifier = Modifier, expanded: Boolean = false, onExpand: (() -> Unit)? = null,
    onAlternateSend: (() -> Unit)? = null, behavior: SendBehavior = SendBehavior.Steer, modEnter: Boolean = false,
    cancelling: Boolean = false, hasAttachments: Boolean = false, submitOnEnter: Boolean = false,
    onReceiveFiles: ((List<android.net.Uri>) -> Unit)? = null, readSentMessages: (() -> List<String>)? = null, cursorRequest: Pair<String, Int>? = null, onSelection: (Int) -> Unit = {}, trailingControls: @Composable () -> Unit = {}, attachments: @Composable () -> Unit = {},
    options: @Composable RowScope.() -> Unit) {
    val receiveFiles by rememberUpdatedState(onReceiveFiles)
    val acceptsContent by rememberUpdatedState(enabled)
    val receiver = remember { object : ReceiveContentListener {
        override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
            if (!acceptsContent || receiveFiles == null) return transferableContent
            val uris = mutableListOf<android.net.Uri>()
            val remaining = transferableContent.consume { item ->
                val uri = item.uri
                if (uri?.scheme in setOf("content", "file")) { uris += requireNotNull(uri); true } else false
            }
            if (uris.isNotEmpty()) receiveFiles?.invoke(uris)
            return remaining
        }
    } }
    val palette = LocalPaseoPalette.current
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var lastValue by remember { mutableStateOf(value) }
    var recallIndex by remember { mutableIntStateOf(-1) }
    var recalled by remember { mutableStateOf("") }
    if (value != lastValue) {
        editor = TextFieldValue(value, TextRange((cursorRequest?.takeIf { it.first == value }?.second ?: value.length).coerceIn(0, value.length)))
        lastValue = value
    }
    SideEffect { onSelection(editor.selection.start) }
    fun edit(next: TextFieldValue) {
        editor = next; lastValue = next.text
        onValueChange(next.text); onSelection(next.selection.start)
    }
    val stop = running && value.isBlank() && !hasAttachments
    val queue = running && behavior == SendBehavior.Queue
    BackHandler(expanded && onExpand != null) { onExpand?.invoke() }
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp), color = palette.surface0,
        border = BorderStroke(1.dp, palette.border)) {
        Column(Modifier.padding(8.dp).then(if (expanded) Modifier.fillMaxHeight() else Modifier)) {
            attachments()
            Box(Modifier.fillMaxWidth().then(if (expanded) Modifier.weight(1f) else Modifier).padding(start = 7.dp, top = 9.dp, bottom = 9.dp, end = if (onExpand != null) 30.dp else 7.dp)) {
                BasicTextField(editor, { edit(it) }, enabled = enabled, maxLines = if (expanded) Int.MAX_VALUE else 6,
                    textStyle = PaseoUiText.copy(color = palette.foreground),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    cursorBrush = SolidColor(palette.accent), modifier = Modifier.fillMaxWidth()
                        .then(if (expanded) Modifier.fillMaxHeight() else Modifier.heightIn(min = 26.dp))
                        .then(if (onReceiveFiles != null) Modifier.contentReceiver(receiver) else Modifier)
                        .semantics { contentDescription = "消息输入框" }
                        .onPreviewKeyEvent { event ->
                            val hardware = event.nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD == 0
                            when {
                                event.type != KeyEventType.KeyDown || !hardware -> false
                                event.key == Key.Escape && expanded -> { onExpand?.invoke(); true }
                                event.key in setOf(Key.DirectionUp, Key.DirectionDown) && readSentMessages != null && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && editor.selection.collapsed -> {
                                    val activeRecall = recallIndex >= 0 && value == recalled
                                    val up = event.key == Key.DirectionUp
                                    val eligible = if (!activeRecall) up && value.isEmpty() else if (up) !value.substring(0, editor.selection.start).contains('\n') else !value.substring(editor.selection.start).contains('\n')
                                    if (!eligible) false else {
                                        val target = if (!activeRecall) 0 else if (up) recallIndex + 1 else recallIndex - 1
                                        val next = if (target < 0) "" else readSentMessages().getOrNull(target)
                                        if (next == null) false else {
                                            recallIndex = target; recalled = next; edit(TextFieldValue(next, TextRange(next.length))); true
                                        }
                                    }
                                }
                                event.key != Key.Enter || event.isShiftPressed || !submitOnEnter -> false
                                modEnter && !event.isCtrlPressed && !event.isMetaPressed -> false
                                else -> {
                                    if (canSend && !sending && !stop) {
                                        if (!modEnter && (event.isCtrlPressed || event.isMetaPressed) && running && onAlternateSend != null) onAlternateSend() else onSend()
                                    }
                                    true
                                }
                            }
                        }, decorationBox = { inner ->
                        if (value.isEmpty()) PaseoText(placeholder, color = palette.foregroundExtraMuted)
                        inner()
                    })
                if (onExpand != null) IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.TopEnd).offset(x = 28.dp).size(28.dp)) {
                    PaseoGlyph(if (expanded) PaseoIcons.Minimize2 else PaseoIcons.Maximize2, if (expanded) "收起输入框" else "展开输入框", Modifier.size(16.dp), palette.foregroundMuted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = options)
                trailingControls()
                val active = canSend && (!sending || stop) && !cancelling
                val label = if (stop) "停止生成" else if (queue) "加入队列" else sendDescription
                val alternateLabel = if (queue) "立即发送" else "加入队列"
                Box(Modifier.size(34.dp).clip(CircleShape)
                    .background(if (active) palette.accent else palette.surface2)
                    .combinedClickable(enabled = active, role = Role.Button,
                        onClick = { if (stop) onStop() else onSend() },
                        onLongClickLabel = if (running && !stop && onAlternateSend != null) alternateLabel else null,
                        onLongClick = if (running && !stop && onAlternateSend != null) onAlternateSend else null)
                    .semantics { contentDescription = label; if (!active) disabled() }, contentAlignment = Alignment.Center) {
                    if (sending && !stop || cancelling) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.accentForeground)
                    else PaseoGlyph(when { stop -> PaseoIcons.SquareFilled; returnIcon -> PaseoIcons.CornerDownLeft; else -> PaseoIcons.ArrowUp }, modifier = Modifier.size(18.dp), tint = if (active) palette.accentForeground else palette.foregroundExtraMuted)
                }
            }
        }
    }
}

@Composable
fun ComposerChoice(value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, icon: Int? = null, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
            if (icon != null) { PaseoGlyph(icon, modifier = Modifier.size(16.dp), tint = LocalPaseoPalette.current.foregroundMuted); Spacer(Modifier.width(6.dp)) }
            PaseoText(value, style = PaseoUiText.copy(fontSize = 13.sp), maxLines = 1)
            PaseoGlyph(PaseoIcons.ChevronDown, null, Modifier.size(14.dp), tint = LocalPaseoPalette.current.foregroundExtraMuted)
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (label == value) PaseoGlyph(PaseoIcons.Check, modifier = Modifier.size(16.dp)) }, onClick = { onChoose(id); expanded = false }) }
        }
    }
}
