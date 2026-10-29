package sh.paseo.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE4E4E7)
private val Dark = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF18181B),
    primaryContainer = Color(0xFF27272A), onPrimaryContainer = Color(0xFFE4E4E7),
    secondaryContainer = Color(0xFF27272A), onSecondaryContainer = Color(0xFFE4E4E7),
    background = Color(0xFF18181B), onBackground = Color(0xFFE4E4E7),
    surface = Color(0xFF18181B), onSurface = Color(0xFFE4E4E7),
    surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainer = Color(0xFF202023), surfaceContainerLow = Color(0xFF1F1F22),
    surfaceContainerHigh = Color(0xFF27272A), outline = Color(0xFF52525B), outlineVariant = Color(0xFF27272A),
)
private val Light = lightColorScheme(
    primary = Color(0xFF18181B), onPrimary = Color(0xFFFAFAFA), primaryContainer = Color(0xFFE4E4E7), onPrimaryContainer = Color(0xFF18181B),
    secondaryContainer = Color(0xFFF4F4F5), onSecondaryContainer = Color(0xFF18181B),
    background = Color.White, onBackground = Color(0xFF18181B),
    surface = Color.White, onSurface = Color(0xFF18181B), onSurfaceVariant = Color(0xFF71717A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F5), surfaceContainerHigh = Color(0xFFF4F4F5), surfaceContainerHighest = Color(0xFFE4E4E7),
    surfaceVariant = Color(0xFFF4F4F5), outline = Color(0xFFA1A1AA), outlineVariant = Color(0xFFE4E4E7),
)
@Composable
fun PaseoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
