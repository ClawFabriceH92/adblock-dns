package io.github.clawfabriceh92.adblockdns.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import java.util.concurrent.ConcurrentHashMap

/** Nom lisible d'une application à partir de son UID ou de son nom de paquet. */
class AppResolver(private val context: Context) {

    data class AppIdentity(val packageName: String?, val label: String)

    data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

    private val packageManager: PackageManager = context.packageManager
    private val cache = ConcurrentHashMap<Int, AppIdentity>()

    fun forUid(uid: Int): AppIdentity = cache.getOrPut(uid) { resolve(uid) }

    /** Les UID peuvent être réattribués après une désinstallation : cache vidé à chaque démarrage. */
    fun invalidate() = cache.clear()

    private fun resolve(uid: Int): AppIdentity {
        if (uid < 0) return AppIdentity(null, "Application inconnue")
        if (uid < Process.FIRST_APPLICATION_UID) return AppIdentity("android", "Système Android")
        val packages = packageManager.getPackagesForUid(uid)?.sorted().orEmpty()
        val first = packages.firstOrNull() ?: return AppIdentity(null, "Application (uid $uid)")
        val label = labelOf(first)
        return AppIdentity(first, if (packages.size > 1) "$label (+${packages.size - 1})" else label)
    }

    @Suppress("DEPRECATION") // getApplicationInfo(String, Int) : la variante à flags exige l'API 33
    fun labelOf(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    /** Applications installées (hors celle-ci), applications utilisateur d'abord. */
    @Suppress("DEPRECATION") // getInstalledApplications(Int) : la variante à flags exige l'API 33
    fun installedApps(): List<InstalledApp> =
        packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = packageManager.getApplicationLabel(it).toString(),
                    isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy<InstalledApp> { it.isSystem }.thenBy { it.label.lowercase() })
            .toList()
}
