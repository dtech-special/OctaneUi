package dev.octaneui.detection

/** Image description in the catalog (the minimum needed to pick a version). */
data class CatalogImage(val name: String, val androidMajor: Int, val abi: String, val sizeBytes: Long?)

sealed interface Match {
    /** An image of the same major Android version as the host exists. */
    data class Exact(val image: CatalogImage) : Match
    /** No exact match, only the nearest versions (automatic download is forbidden). */
    data class Nearest(val candidates: List<CatalogImage>) : Match
    data object None : Match
}

object AndroidVersionMatcher {
    /** "16" -> 16, "14.0.1" -> 14, "abc" -> null */
    fun majorOf(release: String): Int? = release.trim().substringBefore('.').toIntOrNull()

    /**
     * Priorities: 1) Android version, 2) ABI arm64, 3) (backend is checked separately),
     * 4) minimal size, 5) no 32-bit (64only is preferred, judged by name).
     */
    fun select(hostMajor: Int, catalog: List<CatalogImage>): Match {
        val arm = catalog.filter { it.abi == "arm64-v8a" }
        val exact = arm.filter { it.androidMajor == hostMajor }
            .sortedWith(compareBy({ !it.name.contains("64only") }, { it.sizeBytes ?: Long.MAX_VALUE }))
        if (exact.isNotEmpty()) return Match.Exact(exact.first())
        val nearest = arm.sortedBy { kotlin.math.abs(it.androidMajor - hostMajor) }.take(3)
        return if (nearest.isEmpty()) Match.None else Match.Nearest(nearest)
    }
}
