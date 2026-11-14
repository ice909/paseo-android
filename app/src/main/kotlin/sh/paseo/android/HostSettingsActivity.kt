package sh.paseo.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                    onRemoved = ::finish,
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
    onRemoved: () -> Unit,
) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val initialized by vm.initialized.collectAsStateWithLifecycle()
    val host = hosts.find { it.id == hostId }
    val context = LocalContext.current
    LaunchedEffect(initialized, host) { if (initialized && host == null) onRemoved() }
    var pickerOpen by remember { mutableStateOf(false) }
    var sectionsOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LocalNetworkPermission(vm)
    var addHostOpen by remember { mutableStateOf(false) }
    val palette = LocalPaseoPalette.current

    Scaffold(
        containerColor = palette.surface0,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HostHeaderButton(PaseoIcons.ArrowLeft, "返回", onBack)
                    Row(Modifier.weight(1f).clickable { sectionsOpen = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(hostSettingsTitle(section), style = PaseoUiText, maxLines = 1)
                        PaseoGlyph(PaseoIcons.ChevronDown, "设置导航", Modifier.padding(start = 4.dp).size(14.dp))
                    }
                    Row(Modifier.widthIn(max = 150.dp).clickable { pickerOpen = true }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PaseoGlyph(PaseoIcons.Server, null, Modifier.size(16.dp))
                        Text(host?.displayName ?: "主机", Modifier.weight(1f, fill = false), style = PaseoUiText, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        PaseoGlyph(PaseoIcons.ChevronsUpDown, "切换主机", Modifier.size(14.dp))
                    }
                    HostHeaderButton(PaseoIcons.Plus, "添加主机") { addHostOpen = true }
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
                    vm = vm, host = host, section = section, state = state, onRemove = onRemoved,
                )
            }
        }
    }

    if (sectionsOpen) ModalBottomSheet(onDismissRequest = { sectionsOpen = false },
        containerColor = palette.surface0, contentColor = palette.foreground) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(host?.displayName.orEmpty(), Modifier.padding(16.dp), style = PaseoUiText, color = palette.foregroundMuted)
            (listOf(HostSettingsSection("host", "主机", PaseoIcons.Server, "")) + hostSettingsSections).forEach { item ->
                ListItem(headlineContent = { Text(item.title, style = PaseoUiText) },
                    leadingContent = { PaseoGlyph(item.icon, null, Modifier.size(18.dp)) },
                    trailingContent = { if (section == item.id) PaseoGlyph(PaseoIcons.Check, "当前页面", Modifier.size(16.dp)) },
                    modifier = Modifier.clickable { sectionsOpen = false; if (section != item.id) onOpenSection(item.id) })
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
                    trailingContent = { if (host?.id == item.id) PaseoGlyph(PaseoIcons.Check, "已选择", Modifier.size(16.dp)) },
                    leadingContent = { PaseoGlyph(PaseoIcons.Server, null, tint = palette.foregroundMuted) },
                    modifier = Modifier.clickable {
                        pickerOpen = false
                        HostSettingsActivity.open(context, item.id, section)
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
        onScanQr = { addHostOpen = false; PairScanActivity.open(context, fromSettings = true) },
        onDismiss = { addHostOpen = false },
        onHostSaved = { HostSettingsActivity.open(context, it.id, "connections") },
    )
}

@Composable
private fun HostHeaderButton(icon: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        PaseoGlyph(icon, label, Modifier.size(18.dp), LocalPaseoPalette.current.foregroundMuted)
    }
}
