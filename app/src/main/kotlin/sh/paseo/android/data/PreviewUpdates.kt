package sh.paseo.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreviewUpdate(
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    val apkUrl: String,
    val checksumUrl: String,
)

data class PreviewUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val checked: Boolean = false,
    val update: PreviewUpdate? = null,
    val downloadedApk: File? = null,
    val message: String? = null,
)

class PreviewUpdates(private val context: Context) {
    suspend fun check(currentVersionCode: Long): PreviewUpdate? = withContext(Dispatchers.IO) {
        val json = request("https://api.github.com/repos/ice909/paseo-android/releases?per_page=20", 1_000_000)
        val releases = org.json.JSONArray(json)
        val release = (0 until releases.length()).asSequence().map(releases::getJSONObject)
            .firstOrNull { it.optBoolean("prerelease") && !it.optBoolean("draft") } ?: return@withContext null
        val tagParts = Regex("^preview-(\\d+)-(\\d+)-").find(release.optString("tag_name"))?.groupValues
            ?: return@withContext null
        val runNumber = tagParts[1].toLongOrNull() ?: return@withContext null
        val runAttempt = tagParts[2].toLongOrNull() ?: return@withContext null
        val versionCode = 100_000L + runNumber * 100 + runAttempt
        if (versionCode <= currentVersionCode) return@withContext null
        val assets = release.getJSONArray("assets")
        var apkUrl: String? = null
        var checksumUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.optString("name")) {
                APK_NAME -> apkUrl = asset.optString("browser_download_url")
                CHECKSUM_NAME -> checksumUrl = asset.optString("browser_download_url")
            }
        }
        PreviewUpdate(
            versionName = release.optString("name").ifBlank { release.optString("tag_name") },
            versionCode = versionCode,
            notes = release.optString("body"),
            apkUrl = requireNotNull(apkUrl) { "预发布版本缺少 APK" },
            checksumUrl = requireNotNull(checksumUrl) { "预发布版本缺少 APK 校验文件" },
        )
    }

    suspend fun download(update: PreviewUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建更新目录")
        val temporary = File(directory, "$APK_NAME.part")
        val target = File(directory, APK_NAME)
        try {
            val checksumText = request(update.checksumUrl, 1024).trim()
            val expectedHash = checksumText.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) { "更新校验文件格式无效" }
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = open(update.apkUrl)
            try {
                require(connection.responseCode in 200..299) { "下载 APK 失败（HTTP ${connection.responseCode}）" }
                require(connection.url.protocol == "https") { "下载服务器重定向到非安全连接" }
                val total = connection.contentLengthLong
                require(total in 1..MAX_APK_BYTES) { "APK 大小无效或超过限制" }
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_APK_BYTES && copied <= total) { "APK 下载内容超过预期大小" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                        require(copied == total) { "APK 下载不完整" }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(expectedHash, ignoreCase = true)) { "APK SHA-256 校验失败" }
            if (target.exists() && !target.delete()) throw IOException("无法替换旧更新文件")
            if (!temporary.renameTo(target)) throw IOException("无法保存下载的 APK")
            target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    fun install(apk: File): Boolean {
        require(apk.isFile) { "更新 APK 不存在" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(install)
        return true
    }

    private fun request(url: String, maxBytes: Int): String {
        require(url.startsWith("https://")) { "更新地址必须使用 HTTPS" }
        val connection = open(url)
        try {
            require(connection.responseCode in 200..299) { "检查更新失败（HTTP ${connection.responseCode}）" }
            require(connection.url.protocol == "https") { "更新服务器重定向到非安全连接" }
            require(connection.contentLengthLong <= maxBytes) { "更新信息过大" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "更新信息过大" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "Paseo-Android")
    }

    companion object {
        const val APK_NAME = "paseo-release.apk"
        const val CHECKSUM_NAME = "$APK_NAME.sha256"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MAX_APK_BYTES = 250L * 1024 * 1024
    }
}
