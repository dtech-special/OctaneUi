package dev.octaneui.app.system

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import dev.octaneui.container.AppInputs
import java.io.File

/** Collects the Level 1 preflight facts. Anything that cannot be read is reported as null (= BLOCK later). */
class PreflightCollector(private val context: Context) {

    fun appInputs(): AppInputs {
        // KeyguardManager.isDeviceSecure(): true for PIN, password or pattern. Missing service -> null (unknown).
        val km = context.getSystemService(KeyguardManager::class.java)
        val secure: Boolean? = runCatching { km?.isDeviceSecure }.getOrNull()

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null

        val free = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull()
        return AppInputs(secure, percent, tempC, free)
    }

    /** Identifies the current boot; a preflight result is only valid for the boot it was made in. */
    fun bootId(): String? = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.ifEmpty { null }
}
