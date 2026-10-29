package sh.paseo.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import sh.paseo.android.core.*

/** Draft text and attachment references are private and encrypted, just like paired hosts. */
class ChatStore(context: Context) {
    private val mutex = Mutex()
    private val version = AtomicLong()
    private var writtenVersion = 0L
    private val file = AtomicFile(File(context.filesDir, "drafts.enc"))
    private val preferences = context.getSharedPreferences("conversation", Context.MODE_PRIVATE)
    var behavior: SendBehavior
        get() = runCatching { SendBehavior.valueOf(preferences.getString("sendBehavior", "Steer")!!) }.getOrDefault(SendBehavior.Steer)
        set(value) { preferences.edit().putString("sendBehavior", value.name).apply() }
    var modEnter: Boolean
        get() = preferences.getBoolean("modEnter", false)
        set(value) { preferences.edit().putBoolean("modEnter", value).apply() }
    var collapseTurns: Boolean
        get() = preferences.getBoolean("collapseTurns", true)
        set(value) { preferences.edit().putBoolean("collapseTurns", value).apply() }
    private fun key(): SecretKey {
        val alias = "paseo-native-drafts"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun load(): Map<ConversationKey, ConversationMemory> = withContext(Dispatchers.IO) { mutex.withLock {
        if (!file.baseFile.exists()) return@withLock emptyMap()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 12) { "草稿文件损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        wireJson.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()).jsonArray.associate {
            val entry = it.jsonObject
            wireJson.decodeFromJsonElement<ConversationKey>(entry.getValue("key")) to ConversationMemory(wireJson.decodeFromJsonElement<ChatDraft>(entry.getValue("draft")),
                entry["sentAttachments"]?.let { value -> wireJson.decodeFromJsonElement<Map<String, List<ChatAttachment>>>(value) }.orEmpty(), entry["pending"]?.let { value -> wireJson.decodeFromJsonElement<List<OutgoingMessage>>(value) }.orEmpty(),
                entry["failed"]?.takeUnless { value -> value is JsonNull }?.let { value -> wireJson.decodeFromJsonElement<OutgoingMessage>(value) })
        }
    } }
    suspend fun save(drafts: Map<ConversationKey, ConversationMemory>) {
        val revision = version.incrementAndGet()
        withContext(Dispatchers.IO) { mutex.withLock {
        if (revision < writtenVersion) return@withLock
        val payload = JsonArray(drafts.filterValues { !it.draft.empty || it.sentAttachments.isNotEmpty() || it.pending.isNotEmpty() || it.failed != null }.map { (key, memory) ->
            jsonObject("key" to wireJson.encodeToJsonElement(key), "draft" to wireJson.encodeToJsonElement(memory.draft), "sentAttachments" to wireJson.encodeToJsonElement(memory.sentAttachments), "pending" to wireJson.encodeToJsonElement(memory.pending), "failed" to memory.failed?.let { wireJson.encodeToJsonElement(it) })
        })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(payload.toString().toByteArray())
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream); writtenVersion = revision }
        catch (error: Exception) { file.failWrite(stream); throw error }
        } }
    }
}
