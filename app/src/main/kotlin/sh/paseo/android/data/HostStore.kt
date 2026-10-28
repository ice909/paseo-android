package sh.paseo.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import sh.paseo.android.core.*

/** Host credentials stay in app-private storage, encrypted by a non-exportable Keystore key. */
class HostStore(context: Context) {
    private val file = AtomicFile(java.io.File(context.filesDir, "hosts.enc"))
    private val alias = "paseo-native-hosts"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    data class Saved(val hosts: List<Host> = emptyList(), val lastHostId: String? = null)
    suspend fun load(): Saved = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext Saved()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 12) { "主机配置文件损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val payload = wireJson.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()).jsonObject
        Saved(wireJson.decodeFromJsonElement<List<Host>>(payload.getValue("hosts")), payload.string("lastHostId").ifBlank { null })
    }
    suspend fun save(hosts: List<Host>, lastHostId: String?) = withContext(Dispatchers.IO) {
        val payload = jsonObject("hosts" to wireJson.encodeToJsonElement(hosts), "lastHostId" to lastHostId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
