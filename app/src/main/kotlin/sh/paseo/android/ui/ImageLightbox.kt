package sh.paseo.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.abs

/** Shared counterpart of official AttachmentLightbox and its native ZoomableViewport. */
@Composable
internal fun ImageLightbox(image: ImageBitmap?, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val palette = LocalPaseoPalette.current
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).safeDrawingPadding()) {
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val availableWidth = minOf((maxWidth - 32.dp).coerceAtLeast(0.dp), 960.dp)
                val availableHeight = minOf((maxHeight - 32.dp).coerceAtLeast(0.dp), 640.dp)
                val ratio = image?.let { it.width.toFloat() / it.height } ?: 1f
                val width = minOf(availableWidth, availableHeight * ratio)
                val height = width / ratio
                val viewportWidth = with(density) { maxWidth.toPx() }
                val viewportHeight = with(density) { maxHeight.toPx() }
                val fittedWidth = with(density) { width.toPx() }
                val fittedHeight = with(density) { height.toPx() }
                var scale by remember(image, viewportWidth, viewportHeight) { mutableFloatStateOf(1f) }
                var offset by remember(image, viewportWidth, viewportHeight) { mutableStateOf(Offset.Zero) }
                fun clamp(value: Offset, zoom: Float): Offset {
                    val x = ((fittedWidth * zoom - viewportWidth) / 2).coerceAtLeast(0f)
                    val y = ((fittedHeight * zoom - viewportHeight) / 2).coerceAtLeast(0f)
                    return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
                }
                fun reset() { scale = 1f; offset = Offset.Zero }
                fun zoom(factor: Float) {
                    val next = (scale * factor).coerceIn(1f, 8f)
                    offset = clamp(offset * (next / scale), next); scale = next
                }
                fun inside(point: Offset): Boolean =
                    abs(point.x - viewportWidth / 2 - offset.x) <= fittedWidth * scale / 2 &&
                        abs(point.y - viewportHeight / 2 - offset.y) <= fittedHeight * scale / 2
                val dismiss by rememberUpdatedState(onDismiss)
                val download by rememberUpdatedState(onDownload)
                Box(Modifier.fillMaxSize()
                    .pointerInput(image, viewportWidth, viewportHeight) {
                        detectTapGestures(
                            onTap = { if (!inside(it)) dismiss() },
                            onDoubleTap = { reset() },
                            onLongPress = { if (image != null && inside(it)) download() })
                    }
                    .pointerInput(image, viewportWidth, viewportHeight) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            val focal = centroid - Offset(viewportWidth / 2, viewportHeight / 2)
                            offset = clamp(focal - (focal - offset) * (next / scale) + pan, next)
                            scale = next
                        }
                    }, contentAlignment = Alignment.Center) {
                    if (image != null) Image(image, "图片预览", Modifier.size(width, height).graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    }) else CircularProgressIndicator(color = palette.foregroundMuted)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LightboxButton(PaseoIcons.ZoomIn, "放大", image != null && scale < 8f) { zoom(1.25f) }
                    LightboxButton(PaseoIcons.ZoomOut, "缩小", image != null && scale > 1f) { zoom(0.8f) }
                    LightboxButton(PaseoIcons.Scan, "重置缩放", scale != 1f) { reset() }
                    LightboxButton(PaseoIcons.X, "关闭图片预览", true, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun LightboxButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPaseoPalette.current
    // Match official compact toolbar geometry without Material IconButton's larger inner frame.
    Box(
        modifier = Modifier.size(32.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PaseoGlyph(icon, label, Modifier.size(18.dp), palette.foregroundMuted)
    }
}
