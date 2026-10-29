@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.paseo.android.ui

import sh.paseo.android.ui.PaseoText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.paseo.android.AppViewModel
import sh.paseo.android.R

/** The native counterpart of PairLinkModal / AddHostModal. Host editing stays separate. */
@Composable
fun PairingSheet(vm: AppViewModel, direct: Boolean = false, initialLink: String = "", passwordRequired: Boolean = false,
    onDismiss: () -> Unit, onSaved: () -> Unit = onDismiss) {
    val p = LocalPaseoPalette.current
    val saving by vm.pairingBusy.collectAsStateWithLifecycle()
    val error by vm.pairingError.collectAsStateWithLifecycle()
    val needsPassword by vm.pairingPasswordNeeded.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf(initialLink) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("6767") }
    var ssl by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var uriOverride by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val dragThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentHeight = ((LocalConfiguration.current.screenHeightDp * if (expanded) .9f else .65f).dp - 24.dp - bottomInset).coerceAtLeast(120.dp)
    fun clear(resetPassword: Boolean = false) { localError = null; vm.clearPairingError(resetPassword) }
    fun dismiss() { if (!saving) { vm.cancelPairing(); onDismiss() } }
    fun submit() {
        localError = null
        val address = if (direct) {
            if (advanced && uriOverride.isNotBlank()) uriOverride.trim() else {
                val number = port.toIntOrNull() ?: 0
                if (host.isBlank()) { localError = "Host 必填"; return }
                if (number !in 1..65535) { localError = "端口必须在 1 到 65535 之间"; return }
                val hostname = host.trim().let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
                "tcp://$hostname:$number?ssl=$ssl"
            }
        } else link.trim()
        if (address.isBlank()) { localError = "请粘贴配对链接（.../#offer=...）"; return }
        vm.saveHost(null, "", address, password, onSaved)
    }
    LaunchedEffect(Unit) {
        if (!passwordRequired) vm.clearPairingError(resetPassword = true)
        if (!direct) { delay(300); focus.requestFocus() }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !saving })
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheet, containerColor = p.surface0, contentColor = p.foreground,
        sheetMaxWidth = Dp.Unspecified,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f),
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !p.dark, isAppearanceLightNavigationBars = !p.dark),
        dragHandle = { Box(Modifier.fillMaxWidth().height(24.dp)
            .semantics { contentDescription = if (expanded) "收起连接面板" else "展开连接面板" }
            .clickable(enabled = !saving, role = Role.Button) { expanded = !expanded }
            .pointerInput(expanded, saving) {
                var distance = 0f
                detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, amount ->
                    if (!saving) { change.consume(); distance += amount }
                }, onDragEnd = {
                    if (!saving) {
                        if (distance < -dragThreshold) expanded = true
                        else if (distance > dragThreshold) { if (expanded) expanded = false else dismiss() }
                    }
                })
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp, 4.dp).background(androidx.compose.ui.graphics.Color(0xFF52525B), RoundedCornerShape(50)))
        } }) {
        Column(Modifier.fillMaxWidth().height(contentHeight)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (direct) "直接连接" else "粘贴配对链接", style = PaseoUiText, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Box(Modifier.size(32.dp).clickable(enabled = !saving, role = Role.Button, onClick = ::dismiss), contentAlignment = Alignment.Center) {
                    PaseoGlyph(R.drawable.lucide_x, "关闭", Modifier.size(16.dp), p.foregroundMuted)
                }
            }
            HorizontalDivider(color = p.surface2)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (direct) "输入 Paseo server 的地址。" else "粘贴来自 server 的配对链接。", style = PaseoUiText, color = p.foregroundMuted)
                if (!direct) {
                    PairingField("配对链接", link, {
                        link = it; password = ""; clear(resetPassword = true)
                    }, "https://app.paseo.sh/#offer=...", Modifier.focusRequester(focus), enabled = !saving, keyboardType = KeyboardType.Uri, onSubmit = ::submit)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PairingField("Host", host, { host = it; uriOverride = ""; clear() }, "localhost", Modifier.weight(1f), enabled = !saving, keyboardType = KeyboardType.Uri)
                        PairingField("端口", port, { port = it; uriOverride = ""; clear() }, "6767", Modifier.width(112.dp), enabled = !saving, keyboardType = KeyboardType.Number)
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving, role = Role.Checkbox) { ssl = !ssl; uriOverride = ""; clear() }
                        .semantics { contentDescription = "使用 SSL"; toggleableState = if (ssl) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(22.dp).background(if (ssl) p.accent else p.surface0, RoundedCornerShape(2.dp)).border(1.dp, if (ssl) p.accent else p.border, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                            if (ssl) PaseoGlyph(R.drawable.lucide_check, modifier = Modifier.size(14.dp), tint = p.accentForeground)
                        }
                        Text("使用 SSL", style = PaseoUiText, color = p.foregroundMuted, fontWeight = FontWeight.Medium)
                    }
                }
                if (direct || needsPassword || passwordRequired) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (direct) "密码" else "主机密码", style = PaseoUiText, color = p.foregroundMuted, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PairingInput(password, { password = it; clear() }, if (direct) "可选" else "", "主机密码", Modifier.weight(1f), enabled = !saving,
                                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardType = KeyboardType.Password, onSubmit = ::submit)
                            if (direct) Box(Modifier.size(44.dp).background(p.surface2, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp))
                                .clickable(enabled = !saving, role = Role.Button) { visible = !visible }, contentAlignment = Alignment.Center) {
                                PaseoGlyph(if (visible) R.drawable.lucide_eye_off else R.drawable.lucide_eye, if (visible) "隐藏密码" else "显示密码", tint = p.foregroundMuted)
                            }
                        }
                    }
                }
                if (direct) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.clickable(enabled = !saving, role = Role.Button) { advanced = !advanced }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaseoGlyph(if (advanced) R.drawable.lucide_chevron_down else R.drawable.lucide_chevron_right, modifier = Modifier.size(16.dp), tint = p.foregroundMuted)
                        Text("高级", style = PaseoUiText, color = p.foregroundMuted)
                    }
                    if (advanced) PairingInput(uriOverride, { uriOverride = it; clear() }, "tcp://localhost:6767?ssl=true", "连接 URI", enabled = !saving, keyboardType = KeyboardType.Uri)
                }
                (localError ?: error)?.let { Text(it, style = PaseoUiText, color = p.destructive, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaseoButton("取消", ::dismiss, Modifier.weight(1f), primary = false, enabled = !saving)
                    PaseoButton(if (saving) { if (direct) "正在连接..." else "正在配对..." } else if (direct) "连接" else "配对",
                        ::submit, Modifier.weight(1f), icon = if (direct) R.drawable.lucide_link_2 else R.drawable.lucide_link, enabled = !saving)
                }
            }
        }
    }
}

@Composable
private fun PairingField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    enabled: Boolean, keyboardType: KeyboardType, onSubmit: () -> Unit = {}) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = PaseoUiText, color = LocalPaseoPalette.current.foregroundMuted, fontWeight = FontWeight.Medium)
        PairingInput(value, onValueChange, placeholder, label, enabled = enabled, keyboardType = keyboardType, onSubmit = onSubmit)
    }
}

@Composable
private fun PairingInput(value: String, onValueChange: (String) -> Unit, placeholder: String, label: String, modifier: Modifier = Modifier,
    enabled: Boolean, keyboardType: KeyboardType, visualTransformation: VisualTransformation = VisualTransformation.None, onSubmit: () -> Unit = {}) {
    val p = LocalPaseoPalette.current
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().background(p.surface2, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp))
        .padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = label },
        enabled = enabled, singleLine = true, textStyle = PaseoUiText.copy(color = p.foreground), cursorBrush = SolidColor(p.foreground),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }), visualTransformation = visualTransformation,
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, style = PaseoUiText, color = p.foregroundMuted, maxLines = 1); inner() })
}
