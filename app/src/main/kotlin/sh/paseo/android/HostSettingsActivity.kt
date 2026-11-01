package sh.paseo.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.appearance.PaseoThemes
import sh.paseo.android.core.Host
import sh.paseo.android.ui.*

class HostSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val applicationOwner = application as PaseoApplication
        val vm = ViewModelProvider(
            applicationOwner,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[AppViewModel::class.java]
        val hostId = intent.getStringExtra(EXTRA_HOST_ID).orEmpty()
        val section = intent.getStringExtra(EXTRA_SECTION).orEmpty().takeIf(::isHostSettingsSection) ?: "host"
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        applyWindowTheme(PaseoThemes.resolve(vm.theme.value, systemDark))
        lifecycle.addObserver(appForegroundObserver)
        setContent {
            val preference by vm.theme.collectAsStateWithLifecycle()
            PaseoTheme(preference) {
                val palette = LocalPaseoPalette.current
                DisposableEffect(palette) {
                    applyWindowTheme(palette)
                    onDispose { }
                }
                HostSettingsActivityContent(
                    vm = vm,
                    hostId = hostId,
                    section = section,
                    onBack = ::finish,
                    onOpenSection = { next -> open(this, hostId, next) },
                    onEdit = { openHostEditor(hostId) },
                    onAppearance = { openSettings() },
                    onRemove = { host -> vm.deleteHost(host); finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(appForegroundObserver)
        super.onDestroy()
    }

    private val appForegroundObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            val applicationOwner = application as PaseoApplication
            ViewModelProvider(
                applicationOwner,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[AppViewModel::class.java].onAppForegrounded()
        }
    }

    private fun openHostEditor(hostId: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT_HOST_ID, hostId))
    }

    private fun openSettings() {
        SettingsActivity.open(this)
    }

    companion object {
        private const val EXTRA_HOST_ID = "sh.paseo.android.HOST_ID"
        private const val EXTRA_SECTION = "sh.paseo.android.HOST_SECTION"

        fun open(context: Context, hostId: String, section: String = "host") {
            context.startActivity(Intent(context, HostSettingsActivity::class.java)
                .putExtra(EXTRA_HOST_ID, hostId)
                .putExtra(EXTRA_SECTION, section))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSettingsActivityContent(
    vm: AppViewModel,
    hostId: String,
    section: String,
    onBack: () -> Unit,
    onOpenSection: (String) -> Unit,
    onEdit: () -> Unit,
    onAppearance: () -> Unit,
    onRemove: (Host) -> Unit,
) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val initialized by vm.initialized.collectAsStateWithLifecycle()
    val host = hosts.find { it.id == hostId }
    val context = LocalContext.current
    var pickerOpen by remember { mutableStateOf(false) }
    var addHostOpen by remember { mutableStateOf(false) }
    val palette = LocalPaseoPalette.current

    Scaffold(
        containerColor = palette.surface0,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { PaseoGlyph(PaseoIcons.ArrowLeft, "返回", Modifier.size(18.dp)) }
                    Text(
                        if (section == "host") "主机" else hostSettingsTitle(section),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    IconButton(onClick = { pickerOpen = true }) { PaseoGlyph(PaseoIcons.ChevronsUpDown, "切换主机", Modifier.size(18.dp)) }
                    IconButton(onClick = { addHostOpen = true }) { PaseoGlyph(PaseoIcons.Plus, "添加主机", Modifier.size(18.dp)) }
                }
                HorizontalDivider(color = palette.border)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !initialized -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                host == null -> Text("找不到此主机。", Modifier.align(Alignment.Center), color = palette.foregroundMuted)
                else -> HostSettingsScreen(
                    host = host,
                    section = section,
                    state = state,
                    onSection = onOpenSection,
                    onEdit = onEdit,
                    onRemove = { onRemove(host) },
                    onAppearance = onAppearance,
                )
            }
        }
    }

    if (pickerOpen) ModalBottomSheet(
        onDismissRequest = { pickerOpen = false },
        containerColor = palette.surface0,
        contentColor = palette.foreground,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("切换主机", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            hosts.forEach { item ->
                ListItem(
                    headlineContent = { Text(item.displayName, maxLines = 1) },
                    supportingContent = { Text(item.url, maxLines = 1) },
                    leadingContent = { PaseoGlyph(PaseoIcons.Server, null, tint = palette.foregroundMuted) },
                    modifier = Modifier.clickable {
                        pickerOpen = false
                        HostSettingsActivity.open(context, item.id)
                    },
                )
            }
            ListItem(
                headlineContent = { Text("添加主机") },
                leadingContent = { PaseoGlyph(PaseoIcons.Plus, null) },
                modifier = Modifier.clickable { pickerOpen = false; addHostOpen = true },
            )
        }
    }
    if (addHostOpen) PairingSheet(
        vm = vm,
        chooseMethod = true,
        onDismiss = { addHostOpen = false },
    )
}
