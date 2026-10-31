package sh.paseo.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Official menu/menu-overlay.tsx: surface1 with a borderAccent frame. */
@Composable
internal fun PaseoDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPaseoPalette.current
    CompositionLocalProvider(LocalContentColor provides palette.foreground) {
        DropdownMenu(expanded, onDismissRequest, modifier = modifier,
            shape = RoundedCornerShape(8.dp), containerColor = palette.surface1,
            tonalElevation = 0.dp, border = BorderStroke(1.dp, palette.borderAccent), content = content)
    }
}

/** Rows inherit the surrounding sheet/menu surface, preserving its theme layer. */
@Composable
internal fun paseoListItemColors() = with(LocalPaseoPalette.current) {
    ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = foreground,
        supportingColor = foregroundMuted, leadingIconColor = foregroundMuted, trailingIconColor = foregroundMuted)
}
