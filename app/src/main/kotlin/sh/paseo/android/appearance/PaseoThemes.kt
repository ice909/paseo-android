package sh.paseo.android.appearance

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Matches the official theme preference keys; auto follows Light/Paseo Dark. */
enum class ThemePreference(val key: String, val label: String, val dark: Boolean?, val swatch: Color?) {
    LIGHT("light", "Light", false, Color.White),
    DARK("dark", "Dark", true, Color(0xFF2D8B62)),
    AUTO("auto", "系统", null, null),
    ZINC("zinc", "Zinc", true, Color(0xFF808080)),
    MIDNIGHT("midnight", "Midnight", true, Color(0xFF4A6BA8)),
    CLAUDE("claude", "Claude", true, Color(0xFFD97757)),
    GHOSTTY("ghostty", "Ghostty", true, Color(0xFF8CAAEE)),
    PURE_BLACK("pureBlack", "纯黑", true, Color.Black);
    companion object {
        fun fromKey(key: String?): ThemePreference = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Semantic colors copied from the pinned official styles/theme.ts definitions. */
@Immutable
data class PaseoPalette(
    val dark: Boolean,
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val surfaceDiffEmpty: Color,
    val surfaceSidebar: Color,
    val foreground: Color,
    val foregroundMuted: Color,
    val foregroundExtraMuted: Color,
    val border: Color,
    val borderAccent: Color,
    val accent: Color,
    val accentBright: Color,
    val accentForeground: Color,
    val destructive: Color,
    val terminalBlack: Color,
    val terminalBrightBlack: Color,
    val ring: Color,
) {
    val surfaceWorkspace: Color get() = if (dark) surface1 else surface0
    val textSelectionBackground: Color get() = if (dark) Color.White.copy(alpha = .2f) else Color.Black.copy(alpha = .15f)
    val primary: Color get() = if (dark) foreground else Color(0xFF18181B)
    val onPrimary: Color get() = if (dark) surface0 else Color(0xFFFAFAFA)
    val statusSuccess: Color get() = if (dark) Color(0xFF6CB17B) else Color(0xFF3E704A)
    val statusDanger: Color get() = if (dark) Color(0xFFD8847B) else Color(0xFF9D433B)
    val statusWarning: Color get() = if (dark) Color(0xFFC09664) else Color(0xFF7B5D39)
    val statusDotSuccess: Color get() = if (dark) Color(0xFF35C264) else Color(0xFF299F51)
    val statusDotDanger: Color get() = if (dark) Color(0xFFF7796D) else Color(0xFFF12E2F)
    val statusDotWarning: Color get() = if (dark) Color(0xFFDB932E) else Color(0xFFB37824)
    val statusDotRunning: Color get() = if (dark) Color(0xFF5CAAF6) else Color(0xFF268AE0)
    val diffAddition: Color get() = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val diffDeletion: Color get() = if (dark) Color(0xFFEF4444) else Color(0xFFB91C1C)
    val terminalBackground: Color get() = surface0
    val terminalForeground: Color get() = foreground
}

object PaseoThemes {
    fun resolve(preference: ThemePreference, systemDark: Boolean): PaseoPalette = when (preference) {
        ThemePreference.AUTO -> if (systemDark) DARK else LIGHT
        ThemePreference.LIGHT -> LIGHT
        ThemePreference.DARK -> DARK
        ThemePreference.ZINC -> ZINC
        ThemePreference.MIDNIGHT -> MIDNIGHT
        ThemePreference.CLAUDE -> CLAUDE
        ThemePreference.GHOSTTY -> GHOSTTY
        ThemePreference.PURE_BLACK -> PURE_BLACK
    }

    val LIGHT = PaseoPalette(
        dark = false,
        surface0 = Color(0xFFFFFFFF),
        surface1 = Color(0xFFFAFAFA),
        surface2 = Color(0xFFF4F4F5),
        surface3 = Color(0xFFE4E4E7),
        surface4 = Color(0xFFD4D4D8),
        surfaceDiffEmpty = Color(0xFFF6F6F6),
        surfaceSidebar = Color(0xFFF4F4F5),
        foreground = Color(0xFF1A1A1E),
        foregroundMuted = Color(0xFF71717A),
        foregroundExtraMuted = Color(0xFFA1A1AA),
        border = Color(0xFFE4E4E7),
        borderAccent = Color(0xFFECECF1),
        accent = Color(0xFF20744A),
        accentBright = Color(0xFF239956),
        accentForeground = Color(0xFFFFFFFF),
        destructive = Color(0xFFB04138),
        terminalBlack = Color(0xFF1A1A1E),
        terminalBrightBlack = Color(0xFF3F3F46),
        ring = Color(0xFF18181B),
    )

    val DARK = PaseoPalette(
        dark = true,
        surface0 = Color(0xFF181B1A),
        surface1 = Color(0xFF1E2120),
        surface2 = Color(0xFF272A29),
        surface3 = Color(0xFF434645),
        surface4 = Color(0xFF595B5B),
        surfaceDiffEmpty = Color(0xFF252827),
        surfaceSidebar = Color(0xFF141716),
        foreground = Color(0xFFFAFAFA),
        foregroundMuted = Color(0xFFA1A5A4),
        foregroundExtraMuted = Color(0xFF717574),
        border = Color(0xFF252B2A),
        borderAccent = Color(0xFF2F3534),
        accent = Color(0xFF20744A),
        accentBright = Color(0xFF7CCBA0),
        accentForeground = Color(0xFFFFFFFF),
        destructive = Color(0xFFC64F43),
        terminalBlack = Color(0xFF141716),
        terminalBrightBlack = Color(0xFF434645),
        ring = Color(0xFFD4D4D8),
    )

    val ZINC = PaseoPalette(
        dark = true,
        surface0 = Color(0xFF18181B),
        surface1 = Color(0xFF1F1F22),
        surface2 = Color(0xFF27272A),
        surface3 = Color(0xFF3F3F46),
        surface4 = Color(0xFF52525B),
        surfaceDiffEmpty = Color(0xFF242427),
        surfaceSidebar = Color(0xFF131316),
        foreground = Color(0xFFFAFAFA),
        foregroundMuted = Color(0xFFA1A1AA),
        foregroundExtraMuted = Color(0xFF71717A),
        border = Color(0xFF27272A),
        borderAccent = Color(0xFF303036),
        accent = Color(0xFFE4E4E7),
        accentBright = Color(0xFFFAFAFA),
        accentForeground = Color(0xFF18181B),
        destructive = Color(0xFFC44A4A),
        terminalBlack = Color(0xFF131316),
        terminalBrightBlack = Color(0xFF3F3F46),
        ring = Color(0xFFD4D4D8),
    )

    val MIDNIGHT = PaseoPalette(
        dark = true,
        surface0 = Color(0xFF161820),
        surface1 = Color(0xFF1C1E27),
        surface2 = Color(0xFF252731),
        surface3 = Color(0xFF3C3E4C),
        surface4 = Color(0xFF535564),
        surfaceDiffEmpty = Color(0xFF222430),
        surfaceSidebar = Color(0xFF121420),
        foreground = Color(0xFFFAFAFA),
        foregroundMuted = Color(0xFF9A9DB0),
        foregroundExtraMuted = Color(0xFF6B6E82),
        border = Color(0xFF242636),
        borderAccent = Color(0xFF2E3040),
        accent = Color(0xFF3B6FCF),
        accentBright = Color(0xFF7EAAEB),
        accentForeground = Color(0xFFFFFFFF),
        destructive = Color(0xFFC44A52),
        terminalBlack = Color(0xFF121420),
        terminalBrightBlack = Color(0xFF3C3E4C),
        ring = Color(0xFFD4D4D8),
    )

    val CLAUDE = PaseoPalette(
        dark = true,
        surface0 = Color(0xFF1F1F1E),
        surface1 = Color(0xFF262523),
        surface2 = Color(0xFF2F2D2B),
        surface3 = Color(0xFF4A4745),
        surface4 = Color(0xFF605D5B),
        surfaceDiffEmpty = Color(0xFF2A2826),
        surfaceSidebar = Color(0xFF1A1918),
        foreground = Color(0xFFFAFAFA),
        foregroundMuted = Color(0xFFADA9A5),
        foregroundExtraMuted = Color(0xFF78746F),
        border = Color(0xFF2C2A27),
        borderAccent = Color(0xFF36332F),
        accent = Color(0xFFD97757),
        accentBright = Color(0xFFE89A7F),
        accentForeground = Color(0xFFFFFFFF),
        destructive = Color(0xFFCF513E),
        terminalBlack = Color(0xFF1A1918),
        terminalBrightBlack = Color(0xFF4A4745),
        ring = Color(0xFFD4D4D8),
    )

    val GHOSTTY = PaseoPalette(
        dark = true,
        surface0 = Color(0xFF282C34),
        surface1 = Color(0xFF2F333D),
        surface2 = Color(0xFF383C48),
        surface3 = Color(0xFF4A4F5E),
        surface4 = Color(0xFF5B6175),
        surfaceDiffEmpty = Color(0xFF323643),
        surfaceSidebar = Color(0xFF21252D),
        foreground = Color(0xFFFAFAFA),
        foregroundMuted = Color(0xFFC8CCD8),
        foregroundExtraMuted = Color(0xFFA0A4B2),
        border = Color(0xFF353A47),
        borderAccent = Color(0xFF3F4454),
        accent = Color(0xFF89B4FA),
        accentBright = Color(0xFFB4D0FC),
        accentForeground = Color(0xFFFFFFFF),
        destructive = Color(0xFFC44A55),
        terminalBlack = Color(0xFF21252D),
        terminalBrightBlack = Color(0xFF4A4F5E),
        ring = Color(0xFFD4D4D8),
    )

    val PURE_BLACK = PaseoPalette(
        dark = true,
        surface0 = Color(0xFF000000),
        surface1 = Color(0xFF0A0A0A),
        surface2 = Color(0xFF111111),
        surface3 = Color(0xFF202020),
        surface4 = Color(0xFF2D2D2D),
        surfaceDiffEmpty = Color(0xFF0C0C0C),
        surfaceSidebar = Color(0xFF000000),
        foreground = Color(0xFFFAFAFA),
        foregroundMuted = Color(0xFFA1A1AA),
        foregroundExtraMuted = Color(0xFF71717A),
        border = Color(0xFF1C1C1C),
        borderAccent = Color(0xFF242424),
        accent = Color(0xFF20744A),
        accentBright = Color(0xFF7CCBA0),
        accentForeground = Color(0xFFFFFFFF),
        destructive = Color(0xFFC44A4A),
        terminalBlack = Color(0xFF595959),
        terminalBrightBlack = Color(0xFF8A8A8A),
        ring = Color(0xFFD4D4D8),
    )
}
