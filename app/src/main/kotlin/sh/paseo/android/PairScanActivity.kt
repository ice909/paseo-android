package sh.paseo.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.appearance.PaseoThemes
import sh.paseo.android.ui.LocalNetworkPermission
import sh.paseo.android.ui.LocalPaseoPalette
import sh.paseo.android.ui.PairScanScreen
import sh.paseo.android.ui.PaseoTheme

class PairScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val applicationOwner = application as PaseoApplication
        val vm = ViewModelProvider(
            applicationOwner,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[AppViewModel::class.java]
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        applyWindowTheme(PaseoThemes.resolve(vm.theme.value, systemDark))
        setContent {
            val preference by vm.theme.collectAsStateWithLifecycle()
            PaseoTheme(preference) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val palette = LocalPaseoPalette.current
                DisposableEffect(palette) {
                    applyWindowTheme(palette)
                    onDispose { }
                }
                DisposableEffect(lifecycle, vm) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) vm.onAppForegrounded()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                LocalNetworkPermission(vm)
                BackHandler {
                    vm.cancelPairing()
                    finish()
                }
                PairScanScreen(vm, onBack = {
                    vm.cancelPairing()
                    finish()
                }, onPaired = ::finish)
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, PairScanActivity::class.java))
        }
    }
}
