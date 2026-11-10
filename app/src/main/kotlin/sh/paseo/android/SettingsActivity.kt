package sh.paseo.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.appearance.PaseoThemes
import sh.paseo.android.ui.*

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val applicationOwner = application as PaseoApplication
        val vm = ViewModelProvider(
            applicationOwner,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[AppViewModel::class.java]
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        applyWindowTheme(PaseoThemes.resolve(vm.theme.value, systemDark))
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.onAppForegrounded()
        })
        setContent {
            val preference by vm.theme.collectAsStateWithLifecycle()
            val sendBehavior by vm.sendBehavior.collectAsStateWithLifecycle()
            val conversationDisplayMode by vm.conversationDisplayMode.collectAsStateWithLifecycle()
            val update by vm.previewUpdateState.collectAsStateWithLifecycle()
            PaseoTheme(preference) {
                val palette = LocalPaseoPalette.current
                DisposableEffect(palette) {
                    applyWindowTheme(palette)
                    onDispose { }
                }
                Scaffold(
                    containerColor = palette.surface0,
                    topBar = {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = ::finish) { PaseoGlyph(PaseoIcons.ArrowLeft, "返回", Modifier.size(18.dp)) }
                                Text("设置", style = MaterialTheme.typography.titleSmall)
                            }
                            HorizontalDivider(color = palette.border)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        SettingsScreen(
                            preference = preference,
                            onTheme = vm::setTheme,
                            sendBehavior = sendBehavior,
                            onSendBehavior = vm::setSendBehavior,
                            conversationDisplayMode = conversationDisplayMode,
                            onConversationDisplayMode = vm::setConversationDisplayMode,
                            versionName = vm.appVersionName,
                            update = update,
                            onCheckUpdates = { vm.checkForPreviewUpdate() },
                            onInstallUpdate = vm::installPreviewUpdate,
                        )
                    }
                }
                PreviewUpdateDialog(vm)
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
