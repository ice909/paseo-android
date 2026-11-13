package sh.paseo.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.paseo.android.AppViewModel
import sh.paseo.android.core.PaseoRepository

private object MessageImageCache : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

private fun imageBytesFromNetwork(source: String): ByteArray {
    val connection = URL(source).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000; connection.readTimeout = 15_000
    try {
        require(connection.responseCode in 200..299) { "图片请求失败" }
        require(connection.contentLengthLong <= PaseoRepository.MAX_DISPLAY_IMAGE_BYTES) { "图片过大" }
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= PaseoRepository.MAX_DISPLAY_IMAGE_BYTES) { "图片过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}

private suspend fun messageImageBytes(source: String, vm: AppViewModel, hostId: String, cwd: String): ByteArray = withContext(Dispatchers.IO) {
    val bytes = when {
        source.startsWith("data:image/", ignoreCase = true) -> {
            val metadata = source.substringBefore(',')
            require(metadata.contains(";base64", ignoreCase = true) && source.contains(',')) { "不支持的图片格式" }
            val data = source.substringAfter(',')
            require(data.length <= ((PaseoRepository.MAX_DISPLAY_IMAGE_BYTES + 2L) / 3L) * 4L) { "图片过大" }
            Base64.getDecoder().decode(data)
        }
        source.startsWith("https://", true) || source.startsWith("http://", true) -> imageBytesFromNetwork(source)
        else -> {
            val target = imageFileTarget(source, cwd) ?: throw IOException("图片路径无效")
            vm.repository.readImage(hostId, target.cwd, target.path)
        }
    }
    require(bytes.size <= PaseoRepository.MAX_DISPLAY_IMAGE_BYTES) { "图片过大" }
    bytes
}

private suspend fun decodeMessageImage(source: String, vm: AppViewModel, hostId: String, cwd: String): Bitmap = withContext(Dispatchers.IO) {
    val bytes = messageImageBytes(source, vm, hostId, cwd)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("无法读取图片")
}

/** Images from Markdown use daemon RPC for host paths and direct loading for URLs and data URIs. */
@Composable
internal fun MessageImage(source: String, alt: String, vm: AppViewModel, connected: Boolean, hostId: String,
    cwd: String, occurrence: String, thumbnail: Boolean = false) {
    val download = rememberImageDownload { messageImageBytes(source, vm, hostId, cwd) }
    val palette = LocalPaseoPalette.current
    val cacheKey = remember(hostId, cwd, occurrence, source) { "$hostId\u0000$cwd\u0000$occurrence\u0000${source.length}:${source.hashCode()}" }
    var retry by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf(false) }
    var failed by remember(source, connected, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, cacheKey, connected, retry) {
        value = MessageImageCache.get(cacheKey)
        if (value == null) {
            try { value = decodeMessageImage(source, vm, hostId, cwd).also { MessageImageCache.put(cacheKey, it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    if (bitmap != null) {
        val image = requireNotNull(bitmap).asImageBitmap()
        if (thumbnail) Image(image, "图片：$alt", Modifier.padding(top = 4.dp).size(112.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { preview = true }, onLongClickLabel = "下载图片", onLongClick = download), contentScale = ContentScale.Crop)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ratio = image.width.toFloat() / image.height.toFloat()
            val width = minOf(maxWidth, 400.dp * ratio)
            Image(image, if (alt.isBlank()) "图片" else "图片：$alt", Modifier.width(width).height(width / ratio).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { preview = true }, onLongClickLabel = "下载图片", onLongClick = download), contentScale = ContentScale.Fit)
        }
        if (preview) ImageLightbox(image, onDismiss = { preview = false }, onDownload = download)
    } else if (failed) {
        TextButton(onClick = { failed = false; retry++ }, contentPadding = PaddingValues(0.dp)) {
            PaseoGlyph(PaseoIcons.ImagePlus, null, Modifier.size(16.dp), palette.foregroundMuted)
            PaseoText(" ${alt.ifBlank { "图片" }}加载失败，点击重试", color = palette.foregroundMuted)
        }
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        PaseoText("正在加载图片…", color = palette.foregroundMuted)
    }
}
