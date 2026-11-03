package sh.paseo.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.paseo.android.AppViewModel

@Composable
fun PreviewUpdateDialog(vm: AppViewModel) {
    val updateState by vm.previewUpdateState.collectAsStateWithLifecycle()
    val dismissedVersion by vm.dismissedPreviewUpdateVersion.collectAsStateWithLifecycle()
    val update = updateState.update ?: return
    if (dismissedVersion == update.versionCode) return

    AlertDialog(
        onDismissRequest = { vm.dismissPreviewUpdate(update.versionCode) },
        title = { Text("发现新版本：${update.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(update.notes.ifBlank { "有新版本可供下载。" })
                if (updateState.downloading) {
                    LinearProgressIndicator(progress = { updateState.progress }, modifier = Modifier.fillMaxWidth())
                }
                updateState.message?.let {
                    Text(it, color = if (updateState.downloadedApk != null) LocalPaseoPalette.current.statusSuccess else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !updateState.checking && !updateState.downloading, onClick = {
                if (updateState.downloadedApk == null) vm.downloadPreviewUpdate() else vm.installPreviewUpdate()
            }) {
                Text(when {
                    updateState.downloading -> "下载中…"
                    updateState.downloadedApk != null -> "安装更新"
                    else -> "下载并校验"
                })
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissPreviewUpdate(update.versionCode) }) { Text("稍后") }
        },
    )
}
