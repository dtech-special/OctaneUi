package dev.octaneui.app.system

import android.content.Context
import dev.octaneui.app.R
import java.security.MessageDigest

/** Verifies that bundled fonts and license files are exactly the ones that were shipped (SHA-256). */
object ResourceIntegrity {
    enum class Status { OK, MISMATCH, UNREADABLE }
    data class Entry(val name: String, val status: Status)

    private class Expected(val name: String, val sha256: String, val fontRes: Int? = null, val asset: String? = null)

    private val EXPECTED = listOf(
        Expected("Inter", "4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf", fontRes = R.font.inter_variable),
        Expected("Google Sans Flex", "a9ac770798857d5e8ac593ced42b22e40a7c08cec98319f92b0eccc2783a77ba", fontRes = R.font.google_sans_flex),
        Expected("JetBrains Mono", "3cfafa86e28b87184d592fef82846e8c10cb48653c62efcda34f082da225ec34", fontRes = R.font.jetbrains_mono),
        Expected("OFL-Inter.txt", "262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a", asset = "licenses/OFL-Inter.txt"),
        Expected("OFL-GoogleSansFlex.txt", "fc13d69f63e36d284b6e383d4d1463d8ad404f0aa065e57cf961252d51063137", asset = "licenses/OFL-GoogleSansFlex.txt"),
        Expected("OFL-JetBrainsMono.txt", "a76abf002c49097d146e86740a3105a5d00450b1592e820a1109a8c5680cd697", asset = "licenses/OFL-JetBrainsMono.txt"),
        Expected("MaterialSymbols-Apache-2.0.txt", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", asset = "licenses/MaterialSymbols-Apache-2.0.txt"),
    )

    /** Blocking: call from a background dispatcher. Streams the data, nothing is loaded into memory at once. */
    fun verify(ctx: Context): List<Entry> = EXPECTED.map { e ->
        val status = runCatching {
            val stream = if (e.fontRes != null) ctx.resources.openRawResource(e.fontRes) else ctx.assets.open(e.asset!!)
            stream.use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex == e.sha256) Status.OK else Status.MISMATCH
            }
        }.getOrDefault(Status.UNREADABLE)
        Entry(e.name, status)
    }
}
