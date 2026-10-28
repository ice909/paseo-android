package sh.paseo.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.AppViewModel

@Composable
fun LocalNetworkPermission(vm: AppViewModel) {
    val host by vm.localNetworkPermissionRequest.collectAsStateWithLifecycle()
    var launchedForHost by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onLocalNetworkPermissionResult)
    LaunchedEffect(host) {
        val id = host?.id
        if (id == null) launchedForHost = null
        else if (Build.VERSION.SDK_INT >= 37 && launchedForHost != id) {
            launchedForHost = id
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
