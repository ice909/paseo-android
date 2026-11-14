package sh.paseo.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.paseo.android.core.PaseoState

/** Official TimelineSyncStatus: a panel toast whose lifetime follows the sync state. */
@Composable
internal fun ChatSyncStatus(state: PaseoState, modifier: Modifier = Modifier) {
    val label = when {
        !state.connected -> "正在重新连接主机"
        (state.recoveringForeground || state.syncingTimeline) && (!state.loadingTimeline || state.timeline.isNotEmpty()) -> "正在更新消息"
        else -> return
    }
    val palette = LocalPaseoPalette.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = palette.surface0, contentColor = palette.foreground,
            shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, palette.border), shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = palette.foreground, strokeWidth = 2.dp)
                Text(label, style = PaseoUiText)
            }
        }
    }
}
