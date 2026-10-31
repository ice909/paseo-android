package sh.paseo.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val panelEasing = CubicBezierEasing(.25f, .1f, .25f, 1f)
private fun panelAnimation() = tween<Float>(durationMillis = 220, easing = panelEasing)

/** Full-window compact explorer; the workspace remains mounted beneath it. */
@Composable
internal fun ExplorerOverlay(open: Boolean, enabled: Boolean, onOpen: () -> Unit, onClose: () -> Unit,
    workspace: @Composable () -> Unit, panel: @Composable () -> Unit) {
    val position = remember { Animatable(if (open) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(open, enabled) {
        if (open && enabled) { keyboard?.hide(); focus.clearFocus() }
        position.animateTo(if (open && enabled) 1f else 0f, panelAnimation())
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val gesture = Modifier.pointerInput(open, enabled, width, density) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val origin = down.position
                val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var dragging = false
                var cancelled = false
                var delta = 0f
                val initial = if (open) 1f else 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    velocity.addPosition(change.uptimeMillis, change.position)
                    delta = change.position.x - origin.x
                    val vertical = change.position.y - origin.y
                    if (!dragging) {
                        val directed = if (open) delta else -delta
                        if (change.isConsumed || directed < -10.dp.toPx() || (abs(vertical) > 10.dp.toPx() && abs(vertical) > abs(delta))) { cancelled = true; break }
                        if (directed >= 15.dp.toPx() && abs(delta) > abs(vertical)) dragging = true
                    }
                    if (dragging) {
                        change.consume()
                        val next = (initial - delta / width).coerceIn(0f, 1f)
                        scope.launch { position.snapTo(next) }
                    }
                    if (!change.pressed) break
                }
                if (dragging) {
                    val speed = velocity.calculateVelocity().x / density
                    val switch = !cancelled && if (open) delta > width / 3f || speed > 500f else delta < -width / 3f || speed < -500f
                    if (switch) { if (open) onClose() else onOpen() }
                    else scope.launch { position.animateTo(if (open) 1f else 0f, panelAnimation()) }
                }
            }
        }
        Box(Modifier.fillMaxSize().then(gesture)) {
            Box(Modifier.fillMaxSize().then(if (open) Modifier.clearAndSetSemantics { } else Modifier)) { CompositionLocalProvider(LocalTimelineActive provides !open) { workspace() } }
            if (enabled && (open || position.value > 0f)) {
                BackHandler(open, onBack = onClose)
                Surface(Modifier.fillMaxSize().offset { IntOffset((width * (1f - position.value)).roundToInt(), 0) }
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    color = LocalPaseoPalette.current.surfaceSidebar,
                    contentColor = LocalPaseoPalette.current.foreground) { panel() }
            }
        }
    }
}
