package sh.paseo.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.paseo.android.core.*

object ChatAttachments {
    const val MAX_BYTES = 50L * 1024 * 1024
    suspend fun import(context: Context, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        var name = "attachment"
        var declaredSize: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredSize = cursor.getLong(sizeColumn)
            }
        }
        require(declaredSize == null || declaredSize!! <= MAX_BYTES) { "文件不能超过 50 MB：$name" }
        val id = newId()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(directory, id)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取：$name")
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "文件不能超过 50 MB：$name" }
                    output.write(buffer, 0, count)
                }
            } }
            ChatAttachment(id, name, context.contentResolver.getType(uri) ?: "application/octet-stream", file.path, file.length())
        } catch (error: Exception) { file.delete(); throw error }
    }
    suspend fun encodeImage(attachment: ChatAttachment) = withContext(Dispatchers.IO) {
        // Keep small originals intact. Bound decoded pixels for camera photos and large pasted images.
        val file = File(attachment.localPath)
        if (file.length() <= 4 * 1024 * 1024) return@withContext jsonObject("data" to Base64.getEncoder().encodeToString(file.readBytes()), "mimeType" to attachment.mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片：${attachment.name}" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法解码图片")
        try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            jsonObject("data" to Base64.getEncoder().encodeToString(output.toByteArray()), "mimeType" to "image/jpeg")
        } finally { bitmap.recycle() }
    }
}
