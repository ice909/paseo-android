package sh.paseo.android.data

import android.content.Context
import sh.paseo.android.core.WorkspaceSelection
import sh.paseo.android.core.wireJson

/** Device-local navigation only; credentials and conversation content remain in encrypted stores. */
class WorkspaceSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)
    var selection: WorkspaceSelection? = runCatching {
        preferences.getString("lastWorkspace", null)?.let { wireJson.decodeFromString<WorkspaceSelection>(it) }
    }.getOrNull()
        private set

    fun remember(value: WorkspaceSelection) {
        if (selection == value) return
        selection = value
        preferences.edit().putString("lastWorkspace", wireJson.encodeToString(value)).apply()
    }

    fun clear() {
        selection = null
        preferences.edit().remove("lastWorkspace").apply()
    }
}
