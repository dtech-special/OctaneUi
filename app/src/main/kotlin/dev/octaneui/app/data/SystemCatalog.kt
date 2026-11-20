package dev.octaneui.app.data

/** A system catalog entry. [id] is the directory name in the storage (images/<id>). */
data class SystemEntry(val id: String, val title: String, val androidMajor: Int?, val ref: String, val custom: Boolean)

object SystemCatalog {
    /** Ready-made redroid images, 64-bit branch only. The tag is verified when the manifest is fetched. */
    val builtIn: List<SystemEntry> = listOf(16, 15, 14, 13).map {
        SystemEntry("redroid-$it", if (it == 16) "Pixel-Like Android 16" else "Android $it", it, "redroid/redroid:$it.0.0_64only-latest", false)
    }

    fun custom(ref: String) = SystemEntry(
        id = "custom-" + Integer.toHexString(ref.hashCode()).trimStart('-'),
        title = ref.substringAfterLast('/'), androidMajor = null, ref = ref, custom = true,
    )
}
