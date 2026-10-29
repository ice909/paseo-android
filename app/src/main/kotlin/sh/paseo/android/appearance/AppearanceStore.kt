package sh.paseo.android.appearance

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Device-local appearance preference. Reading it before mounting Compose avoids an in-app theme flash. */
class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val writes = Mutex()
    private val _theme = MutableStateFlow(ThemePreference.fromKey(preferences.getString("theme", "auto")))
    val theme = _theme.asStateFlow()
    fun select(value: ThemePreference) { _theme.value = value }
    suspend fun persist() = writes.withLock {
        withContext(Dispatchers.IO) {
            // Read the latest selection under the write lock: rapid selections cannot
            // leave an earlier queued preference as the final disk value.
            if (!preferences.edit().putString("theme", _theme.value.key).commit()) throw IOException("主题设置未能保存，请稍后重试")
        }
    }
}
