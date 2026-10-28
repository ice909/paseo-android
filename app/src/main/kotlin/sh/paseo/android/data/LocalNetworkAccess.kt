package sh.paseo.android.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.paseo.android.core.Host

object LocalNetworkAccess {
    fun permissionMissing(context: Context): Boolean = Build.VERSION.SDK_INT >= 37 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    /** Resolve away from the UI thread so public hosts don't require LAN permission. */
    suspend fun isLocalHost(host: Host): Boolean = withContext(Dispatchers.IO) {
        val name = URI(host.url).host.removeSurrounding("[", "]").trimEnd('.')
        if (name.equals("localhost", ignoreCase = true)) return@withContext false
        if (name.endsWith(".local", ignoreCase = true) || ('.' !in name && ':' !in name)) return@withContext true
        val addresses = try { InetAddress.getAllByName(name) } catch (_: java.net.UnknownHostException) { emptyArray() }
        addresses.any { address ->
            if (address.isLoopbackAddress) false else {
                val bytes = address.address
                address.isSiteLocalAddress || address.isLinkLocalAddress ||
                    (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) ||
                    (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64)
            }
        }
    }
}
