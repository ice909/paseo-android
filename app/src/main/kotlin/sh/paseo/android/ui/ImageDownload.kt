package sh.paseo.android.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.paseo.android.core.PaseoRepository

/** Keep the original file in cache while the system document picker owns the screen. */
@Composable
internal fun rememberImageDownload(loadBytes: suspend () -> ByteArray): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestLoad by rememberUpdatedState(loadBytes)
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val path = pendingPath
        pendingPath = null
        if (path != null) scope.launch {
            busy = true
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output -> File(path).inputStream().use { it.copyTo(output) } }
                            ?: throw IOException("无法写入所选位置")
                    }
                    Toast.makeText(context, "图片已保存", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show() }
            finally { withContext(Dispatchers.IO) { File(path).delete() }; busy = false }
        }
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false }, title = { Text("图片") },
        confirmButton = { TextButton(onClick = {
            menu = false; busy = true
            scope.launch {
                var temporary: File? = null
                try {
                    val file = withContext(Dispatchers.IO) {
                        val bytes = latestLoad()
                        require(bytes.size <= PaseoRepository.MAX_DISPLAY_IMAGE_BYTES) { "图片过大" }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(bounds.outMimeType) ?: "img"
                        File.createTempFile("Paseo-", ".$extension", context.cacheDir).also { temporary = it; it.writeBytes(bytes) }
                    }
                    pendingPath = file.absolutePath
                    picker.launch(file.name)
                } catch (e: CancellationException) { temporary?.delete(); throw e }
                catch (_: Exception) {
                    temporary?.delete(); pendingPath = null
                    Toast.makeText(context, "无法下载图片，请检查连接后重试", Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }) { Text("下载图片") } },
        dismissButton = { TextButton(onClick = { menu = false }) { Text("取消") } })
    return { if (!busy && pendingPath == null) menu = true }
}
