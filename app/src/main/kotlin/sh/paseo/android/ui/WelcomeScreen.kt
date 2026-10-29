package sh.paseo.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import sh.paseo.android.ui.PaseoText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.paseo.android.R

// Official native default UI font is 15sp. Keep Android's natural font padding,
// matching React Native Text rather than inheriting Material's line-height tokens.
val PaseoUiText = TextStyle(fontSize = 15.sp, platformStyle = PlatformTextStyle(includeFontPadding = true))

/** BasicText keeps Material's default line height / letter spacing out of the native copy. */
@Composable
fun PaseoText(text: String, modifier: Modifier = Modifier, style: TextStyle = PaseoUiText,
    color: Color = LocalContentColor.current, fontWeight: FontWeight? = null, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style.copy(color = color, fontWeight = fontWeight ?: style.fontWeight,
        textAlign = textAlign ?: style.textAlign, letterSpacing = 0.sp), maxLines = maxLines)
}

@Composable
fun PaseoButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null, primary: Boolean = true, enabled: Boolean = true, welcome: Boolean = false) {
    val p = LocalPaseoPalette.current
    val shape = RoundedCornerShape(if (welcome) 12.dp else 8.dp)
    val background = if (primary) p.accent else if (welcome) p.surface2 else p.surface3
    val foreground = if (primary) p.accentForeground else p.foreground
    Row(modifier.alpha(if (enabled) 1f else .5f).clip(shape).background(background)
        .border(1.dp, if (primary) p.accent else if (welcome) p.border else p.surface3, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp, vertical = if (welcome) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (welcome) 12.dp else 8.dp, Alignment.CenterHorizontally)) {
        if (icon != null) PaseoGlyph(icon, tint = foreground, modifier = Modifier.size(if (welcome) 18.dp else 16.dp))
        Text(label, style = PaseoUiText, fontWeight = FontWeight.Medium, color = foreground, textAlign = TextAlign.Center)
    }
}

/** Mirrors official components/welcome-screen.tsx, including its native action order. */
@Composable
fun WelcomeScreen(onScan: () -> Unit, onDirect: () -> Unit, onPasteLink: () -> Unit, onSettings: () -> Unit) {
    val p = LocalPaseoPalette.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    BoxWithConstraints(Modifier.fillMaxSize().background(p.surface0).safeDrawingPadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(painterResource(R.drawable.paseo_logo), "Paseo", Modifier.size(96.dp), tint = p.foreground)
                Column(Modifier.padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("欢迎使用 Paseo", style = PaseoUiText, fontWeight = FontWeight.Medium, color = p.foreground, textAlign = TextAlign.Center)
                    Text("连接你的电脑即可开始", style = PaseoUiText, color = p.foregroundMuted, textAlign = TextAlign.Center)
                    Row(Modifier.clickable(role = Role.Button) { uri.openUri("https://paseo.sh") }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("paseo.sh", style = PaseoUiText, color = p.accent, fontWeight = FontWeight.Medium)
                        PaseoGlyph(R.drawable.lucide_external_link, "打开 Paseo 网站", Modifier.size(14.dp), p.accent)
                    }
                }
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaseoButton("扫描二维码", onScan, Modifier.fillMaxWidth(), R.drawable.lucide_qr_code, welcome = true)
                    PaseoButton("直接连接", onDirect, Modifier.fillMaxWidth(), R.drawable.lucide_link_2, primary = false, welcome = true)
                    PaseoButton("粘贴配对链接", onPasteLink, Modifier.fillMaxWidth(), R.drawable.lucide_clipboard_paste, primary = false, welcome = true)
                }
                Row(Modifier.padding(top = 24.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onSettings).height(32.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaseoGlyph(R.drawable.lucide_settings, tint = p.foregroundMuted, modifier = Modifier.size(14.dp))
                    Text("设置", style = PaseoUiText, color = p.foregroundMuted)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("v$version", Modifier.padding(top = 24.dp), style = PaseoUiText.copy(fontSize = 13.sp), color = p.foregroundMuted, textAlign = TextAlign.Center)
        }
    }
}
