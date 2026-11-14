package sh.paseo.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.paseo.android.core.Host

internal val hostColors = linkedMapOf("none" to "无", "violet" to "紫罗兰", "sky" to "天蓝", "emerald" to "翠绿",
    "orange" to "橙色", "pink" to "粉色", "indigo" to "靛蓝", "teal" to "青色", "red" to "红色", "amber" to "琥珀", "blue" to "蓝色")
internal val hostBadgeDisplays = linkedMapOf("name" to "名称", "icon" to "仅图标", "hidden" to "隐藏")

@Composable
internal fun hostIdentityColor(name: String): Color {
    val light = listOf(0xFF6d49b5, 0xFF3d6985, 0xFF3e6e5d, 0xFF845838, 0xFF974168, 0xFF5251c2, 0xFF3e6d6c, 0xFF9c4243, 0xFF716239, 0xFF39649e)
    val dark = listOf(0xFFa392d5, 0xFF6aa6ce, 0xFF6cae96, 0xFFcc8f64, 0xFFd87da3, 0xFF9299d5, 0xFF6cacab, 0xFFd88381, 0xFFb29d64, 0xFF7ba1d5)
    val index = hostColors.keys.drop(1).indexOf(name)
    return if (index < 0) LocalPaseoPalette.current.foregroundMuted else Color((if (LocalPaseoPalette.current.dark) dark else light)[index])
}

/** Shared with the sidebar so the settings preview uses the actual badge. */
@Composable
internal fun HostIdentityBadge(host: Host) {
    if (host.badgeDisplay == "hidden") return
    val color = hostIdentityColor(host.color)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        PaseoGlyph(PaseoIcons.Server, host.displayName.takeIf { host.badgeDisplay == "icon" }, Modifier.size(12.dp), color)
        if (host.badgeDisplay == "name") Text(host.displayName, fontSize = 12.sp, lineHeight = 16.sp, color = color, maxLines = 1)
    }
}
