package sh.paseo.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFA8D5BA)
private val Dark = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF133524),
    primaryContainer = Color(0xFF253B30), onPrimaryContainer = Color(0xFFD0E9D8),
    secondaryContainer = Color(0xFF253B30), onSecondaryContainer = Color(0xFFD0E9D8),
    background = Color(0xFF121214), onBackground = Color(0xFFE4E4E7),
    surface = Color(0xFF18181B), onSurface = Color(0xFFE4E4E7),
    surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainer = Color(0xFF202023), surfaceContainerLow = Color(0xFF18181B),
    surfaceContainerHigh = Color(0xFF27272A), outline = Color(0xFF52525B), outlineVariant = Color(0xFF303034),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2D6848), primaryContainer = Color(0xFFD3EDDC), onPrimaryContainer = Color(0xFF133524),
    secondaryContainer = Color(0xFFD3EDDC), onSecondaryContainer = Color(0xFF133524),
    background = Color(0xFFFAFAFA), surface = Color.White, surfaceContainer = Color(0xFFF1F1F3),
    surfaceVariant = Color(0xFFF0F0F2), outlineVariant = Color(0xFFE4E4E7),
)
@Composable
fun PaseoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
