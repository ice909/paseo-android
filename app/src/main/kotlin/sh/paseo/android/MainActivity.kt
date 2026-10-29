package sh.paseo.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.appearance.PaseoPalette
import sh.paseo.android.appearance.PaseoThemes
import sh.paseo.android.ui.LocalPaseoPalette
import sh.paseo.android.ui.PaseoApp
import sh.paseo.android.ui.PaseoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this)[AppViewModel::class.java]
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        applyWindowTheme(PaseoThemes.resolve(vm.theme.value, systemDark))
        setContent {
            val preference by vm.theme.collectAsStateWithLifecycle()
            PaseoTheme(preference) {
                val palette = LocalPaseoPalette.current
                DisposableEffect(palette) {
                    applyWindowTheme(palette)
                    onDispose { }
                }
                PaseoApp(vm)
            }
        }
    }

    private fun applyWindowTheme(palette: PaseoPalette) {
        val transparent = android.graphics.Color.TRANSPARENT
        val bars = if (palette.dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.decorView.setBackgroundColor(palette.surface0.toArgb())
    }
}
