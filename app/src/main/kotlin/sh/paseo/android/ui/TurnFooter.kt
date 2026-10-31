package sh.paseo.android.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import sh.paseo.android.core.formatTurnDuration
import kotlin.math.roundToInt

internal val LocalTimelineActive = staticCompositionLocalOf { true }
private val dotOpacities = arrayOf(
    floatArrayOf(1f, 0f, .78f, 0f, .56f, .34f), floatArrayOf(.78f, 1f, .56f, 0f, .34f, 0f),
    floatArrayOf(.56f, .78f, .34f, 1f, 0f, 0f), floatArrayOf(.34f, .56f, 0f, .78f, 0f, 1f),
    floatArrayOf(0f, .34f, 0f, .56f, 1f, .78f), floatArrayOf(0f, 0f, 1f, .34f, .78f, .56f),
)

@Composable
internal fun RunningTurnFooter(startedAt: Long?, cancelling: Boolean, waitingPermission: Boolean) {
    val palette = LocalPaseoPalette.current
    val active = LocalTimelineActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, lifecycle, active, reduceMotion) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(if (reduceMotion) 1000 else 158)
            }
        }
    }
    Row(Modifier.padding(top = 13.dp, bottom = 32.dp).heightIn(min = 24.dp).semantics {
        contentDescription = when { cancelling -> "正在停止"; waitingPermission -> "等待确认"; else -> "正在工作" }
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(14.dp)) {
            val step = if (reduceMotion) 0 else ((now % 950) * 6 / 950).toInt()
            val gap = (14f * .12f).roundToInt().dp.toPx()
            val dot = (size.height - gap * 2) / 3
            val left = (size.width - dot * 2 - gap) / 2
            for (index in 0..5) drawCircle(palette.foreground.copy(alpha = dotOpacities[step][index]), dot / 2,
                Offset(left + (index % 2) * (dot + gap) + dot / 2, (index / 2) * (dot + gap) + dot / 2))
        }
        if (startedAt != null) Text(formatTurnDuration(now - startedAt), fontSize = 13.sp,
            color = palette.foregroundMuted, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** Actions align to the reply's left edge; timing toggles to the end timestamp for three seconds. */
@Composable
internal fun CompletedTurnFooter(footer: sh.paseo.android.core.AssistantFooter) {
    val palette = LocalPaseoPalette.current
    val duration = footer.durationMs?.let { "工作了 ${formatTurnDuration(it)}" }.orEmpty()
    val timestamp = footer.completedAt?.let(::formatMessageTime).orEmpty()
    val primary = duration.ifEmpty { timestamp }
    val canReveal = duration.isNotEmpty() && timestamp.isNotEmpty()
    var reveal by remember(footer) { mutableStateOf(false) }
    var pressVersion by remember(footer) { mutableIntStateOf(0) }
    LaunchedEffect(pressVersion) { if (pressVersion > 0) { delay(3000); reveal = false } }
    Row(Modifier.padding(top = 13.dp, bottom = 32.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        footer.content?.let { content ->
            MessageCopyButton(content, label = "复制回复", modifier = Modifier.layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                val inset = 4.dp.roundToPx()
                layout((child.width - inset).coerceAtLeast(0), child.height) { child.placeRelative(-inset, 0) }
            })
        }
        if (primary.isNotEmpty()) Box(Modifier.then(if (canReveal) Modifier.clickable(role = Role.Button) {
            reveal = !reveal; pressVersion++
        }.semantics { contentDescription = "$duration，结束于 $timestamp" } else Modifier)) {
            // Measure both labels without drawing to prevent adjacent controls moving on a tap.
            Text(primary, fontSize = 13.sp, color = Color.Transparent, modifier = Modifier.clearAndSetSemantics { })
            if (canReveal) Text(timestamp, fontSize = 13.sp, color = Color.Transparent, modifier = Modifier.clearAndSetSemantics { })
            Text(if (reveal) timestamp else primary, fontSize = 13.sp, color = palette.foregroundMuted)
        }
    }
}

internal fun formatMessageTime(millis: Long): String {
    val locale = java.util.Locale.getDefault()
    val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(date.toLocalDate(), java.time.LocalDate.now())
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale).format(date)
    return when {
        days == 0L -> time
        days in 1L..6L -> java.time.format.DateTimeFormatter.ofPattern("EEEE", locale).format(date) + " " + time
        else -> java.time.format.DateTimeFormatter.ofPattern("yyyy MMM d", locale).format(date) + ", " + time
    }
}
