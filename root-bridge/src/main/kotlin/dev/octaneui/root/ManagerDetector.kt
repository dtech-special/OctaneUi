package dev.octaneui.root

import android.content.Context
import android.content.pm.PackageManager

/** Detects the installed root manager by package. This is a hint, not proof. */
class ManagerDetector(private val isInstalled: (String) -> Boolean) {
    fun detect(): RootManager = KNOWN.firstOrNull { isInstalled(it.first) }?.second ?: RootManager.NOT_DETECTED

    companion object {
        val KNOWN = listOf(
            "me.weishu.kernelsu" to RootManager.KERNELSU,
            "com.rifsxd.ksunext" to RootManager.KERNELSU_NEXT,
            "com.sukisu.ultra" to RootManager.SUKISU,
            "com.topjohnwu.magisk" to RootManager.MAGISK,
        )

        @Suppress("DEPRECATION")
        fun forContext(ctx: Context) = ManagerDetector { pkg ->
            try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        }
    }
}
