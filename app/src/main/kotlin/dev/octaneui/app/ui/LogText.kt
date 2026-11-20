package dev.octaneui.app.ui

import android.content.res.Resources
import dev.octaneui.core.LogEntry

/** Log line in the device language: resource `log_<key>`; if it is missing, the technical form. */
fun LogEntry.localized(res: Resources, pkg: String): String {
    @Suppress("DiscouragedApi")
    val id = res.getIdentifier("log_$key", "string", pkg)
    if (id == 0) return message
    return runCatching { res.getString(id, *args.toTypedArray()) }.getOrDefault(message)
}
