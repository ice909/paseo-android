package sh.paseo.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import sh.paseo.android.appearance.*

val LocalPaseoPalette = staticCompositionLocalOf { PaseoThemes.DARK }

private fun PaseoPalette.materialColors(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary, primaryContainer = surface2, onPrimaryContainer = foreground,
        inversePrimary = accent, secondary = accentBright, onSecondary = accentForeground,
        secondaryContainer = surface2, onSecondaryContainer = foreground,
        tertiary = statusWarning, onTertiary = surface0, tertiaryContainer = surface2, onTertiaryContainer = foreground,
        background = surface0, onBackground = foreground, surface = surface0, onSurface = foreground,
        surfaceVariant = surface2, onSurfaceVariant = foregroundMuted, surfaceTint = Color.Transparent,
        surfaceContainerLowest = surface0, surfaceContainerLow = surface1, surfaceContainer = surface1,
        surfaceContainerHigh = surface2, surfaceContainerHighest = surface3,
        surfaceBright = surface3, surfaceDim = surface0,
        outline = surface4, outlineVariant = border,
        error = destructive, onError = Color.White, errorContainer = destructive.copy(alpha = .16f), onErrorContainer = foreground,
        inverseSurface = if (dark) Color(0xFFFAFAFA) else Color(0xFF18181B),
        inverseOnSurface = if (dark) Color(0xFF18181B) else Color(0xFFFAFAFA), scrim = Color.Black,
    )
}

@Composable
fun PaseoTheme(preference: ThemePreference = ThemePreference.AUTO, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val palette = remember(preference, systemDark) { PaseoThemes.resolve(preference, systemDark) }
    val colors = remember(palette) { palette.materialColors() }
    val selection = remember(palette) { TextSelectionColors(palette.accentBright, palette.accentBright.copy(alpha = .25f)) }
    CompositionLocalProvider(LocalPaseoPalette provides palette) {
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
        }
    }
}
