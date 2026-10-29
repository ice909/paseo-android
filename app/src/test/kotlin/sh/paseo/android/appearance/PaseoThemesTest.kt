package sh.paseo.android.appearance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class PaseoThemesTest {
    @Test fun preferenceKeysAreStableAndUnknownValuesFollowSystem() {
        assertEquals(listOf("light", "dark", "auto", "zinc", "midnight", "claude", "ghostty", "pureBlack"), ThemePreference.entries.map { it.key })
        ThemePreference.entries.forEach { assertEquals(it, ThemePreference.fromKey(it.key)) }
        listOf(null, "", "plugin", "not-a-theme").forEach { assertEquals(ThemePreference.AUTO, ThemePreference.fromKey(it)) }
    }

    @Test fun systemModeUsesOfficialLightAndPaseoDark() {
        assertSame(PaseoThemes.LIGHT, PaseoThemes.resolve(ThemePreference.AUTO, false))
        assertSame(PaseoThemes.DARK, PaseoThemes.resolve(ThemePreference.AUTO, true))
        assertEquals(Color(0xFF181B1A), PaseoThemes.resolve(ThemePreference.AUTO, true).surface0)
        assertNotEquals(PaseoThemes.ZINC, PaseoThemes.resolve(ThemePreference.AUTO, true))
    }

    @Test fun explicitChoicesIgnoreSystemAppearance() {
        ThemePreference.entries.filter { it != ThemePreference.AUTO }.forEach { preference ->
            val selected = PaseoThemes.resolve(preference, false)
            assertSame(selected, PaseoThemes.resolve(preference, true))
            assertEquals(preference != ThemePreference.LIGHT, selected.dark)
        }
        assertEquals(Color.White, PaseoThemes.resolve(ThemePreference.LIGHT, true).surface0)
        assertEquals(Color.Black, PaseoThemes.resolve(ThemePreference.PURE_BLACK, false).surface0)
        assertEquals(Color.Black, PaseoThemes.resolve(ThemePreference.PURE_BLACK, false).surfaceSidebar)
    }

    @Test fun bodyAndTerminalRemainReadableInEveryTheme() {
        ThemePreference.entries.filter { it != ThemePreference.AUTO }.forEach { preference ->
            val colors = PaseoThemes.resolve(preference, false)
            listOf(colors.surface0, colors.surfaceWorkspace, colors.surfaceSidebar, colors.surface2).forEach { background ->
                assertTrue("${preference.key}: body text contrast", contrast(colors.foreground, background) >= 4.5f)
            }
            assertTrue("${preference.key}: terminal contrast", contrast(colors.terminalForeground, colors.terminalBackground) >= 4.5f)
            assertTrue("${preference.key}: primary button contrast", contrast(colors.primary, colors.onPrimary) >= 4.5f)
        }
    }

    @Test fun DiffAndStatusColorsStaySemanticInNeutralThemes() {
        val zinc = PaseoThemes.ZINC
        assertEquals(Color(0xFF4ADE80), zinc.diffAddition)
        assertEquals(Color(0xFFEF4444), zinc.diffDeletion)
        assertEquals(Color(0xFF6CB17B), zinc.statusSuccess)
        assertNotEquals(zinc.primary, zinc.diffAddition)
        val light = PaseoThemes.LIGHT
        assertEquals(Color(0xFF15803D), light.diffAddition)
        assertEquals(Color(0xFF299F51), light.statusDotSuccess)
        assertNotEquals(light.diffAddition, zinc.diffAddition)
        assertNotEquals(light.statusDotSuccess, zinc.statusDotSuccess)
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
}
