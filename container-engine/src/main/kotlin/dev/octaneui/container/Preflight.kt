package dev.octaneui.container

/**
 * Mandatory pre-start checks, fail-closed: anything that cannot be determined is a BLOCK.
 *
 * Three levels re-check the critical conditions independently:
 *  - Level 1 (app, Kotlin UI): lock screen via KeyguardManager, battery, free space;
 *  - Level 2 (root preflight): re-checks lock-screen evidence, SELinux state, foreign mounts with root rights;
 *  - Level 3 (session scripts): the generated start script repeats the lock/SELinux/mount checks itself.
 * No setting, developer option or environment variable can skip any of them.
 */
enum class PreflightId { SCREEN_LOCK, SELINUX_ENFORCING, BATTERY, STORAGE_SPACE, MOUNTS_CLEAN, ROOT_SESSION, BINDING }

enum class PreflightVerdict { PASS, BLOCK }

data class PreflightCheck(val id: PreflightId, val verdict: PreflightVerdict, val detail: String? = null)

/** The result is valid only for this boot, this app version, this host build and this guest image. */
data class PreflightBinding(val bootId: String?, val appVersion: String, val fingerprint: String, val imageDigest: String?)

data class PreflightReport(val level: Int, val checks: List<PreflightCheck>, val binding: PreflightBinding) {
    val allowed: Boolean get() = checks.isNotEmpty() && checks.all { it.verdict == PreflightVerdict.PASS }
    val blockers: List<PreflightCheck> get() = checks.filter { it.verdict != PreflightVerdict.PASS }
}

/** Facts collected by the app without root. null means "could not be determined". */
data class AppInputs(
    val deviceSecure: Boolean?,
    val batteryPercent: Int?,
    val batteryTempC: Float?,
    val freeBytes: Long?,
)

/** Facts collected with root rights (read-only commands). null means "could not be determined". */
data class RootInputs(
    val rootGranted: Boolean,
    val selinuxEnforce: String?,
    /** Output of listing the synthetic-password directory; null if unreadable. */
    val spblobListing: String?,
    val mountInfo: String?,
    /** Absolute path of the OctaneUi storage; no mount of it may exist before a start. */
    val storagePath: String,
)

object Preflight {
    const val MIN_BATTERY = 30
    const val MAX_TEMP_C = 42f
    const val MIN_FREE_BYTES = 4L * 1024 * 1024 * 1024

    private fun pass(id: PreflightId, detail: String? = null) = PreflightCheck(id, PreflightVerdict.PASS, detail)
    private fun block(id: PreflightId, detail: String? = null) = PreflightCheck(id, PreflightVerdict.BLOCK, detail)

    fun level1(i: AppInputs, binding: PreflightBinding): PreflightReport {
        val checks = listOf(
            when (i.deviceSecure) { false -> pass(PreflightId.SCREEN_LOCK); true -> block(PreflightId.SCREEN_LOCK, "secure"); null -> block(PreflightId.SCREEN_LOCK, "unknown") },
            when {
                i.batteryPercent == null || i.batteryTempC == null -> block(PreflightId.BATTERY, "unknown")
                i.batteryPercent < MIN_BATTERY -> block(PreflightId.BATTERY, "${i.batteryPercent}%")
                i.batteryTempC >= MAX_TEMP_C -> block(PreflightId.BATTERY, "${i.batteryTempC}C")
                else -> pass(PreflightId.BATTERY, "${i.batteryPercent}%, ${i.batteryTempC}C")
            },
            when {
                i.freeBytes == null -> block(PreflightId.STORAGE_SPACE, "unknown")
                i.freeBytes < MIN_FREE_BYTES -> block(PreflightId.STORAGE_SPACE, "${i.freeBytes}")
                else -> pass(PreflightId.STORAGE_SPACE)
            },
        )
        return PreflightReport(1, checks, binding)
    }

    /** Names of credential files in the synthetic password store: `<handle>.pwd` exists only if a PIN/password/pattern is set. */
    fun credentialFilesIn(listing: String): List<String> = listing.lines().map { it.trim() }.filter { it.endsWith(".pwd") }

    fun level2(app: AppInputs, root: RootInputs, binding: PreflightBinding): PreflightReport {
        val l1 = level1(app, binding).checks.filter { it.id != PreflightId.SCREEN_LOCK }
        val lock = when {
            app.deviceSecure != false -> block(PreflightId.SCREEN_LOCK, if (app.deviceSecure == true) "secure" else "unknown")
            root.spblobListing == null -> block(PreflightId.SCREEN_LOCK, "root: credential store unreadable")
            credentialFilesIn(root.spblobListing).isNotEmpty() -> block(PreflightId.SCREEN_LOCK, "root: credential file present")
            else -> pass(PreflightId.SCREEN_LOCK)
        }
        val selinux = when (root.selinuxEnforce?.trim()) {
            "1" -> pass(PreflightId.SELINUX_ENFORCING)
            "0" -> block(PreflightId.SELINUX_ENFORCING, "permissive")
            else -> block(PreflightId.SELINUX_ENFORCING, "unknown")
        }
        val mounts = when {
            root.mountInfo == null -> block(PreflightId.MOUNTS_CLEAN, "unknown")
            root.mountInfo.lines().any { it.contains(root.storagePath) } -> block(PreflightId.MOUNTS_CLEAN, "foreign mount of OctaneUi storage")
            else -> pass(PreflightId.MOUNTS_CLEAN)
        }
        val session = if (root.rootGranted) pass(PreflightId.ROOT_SESSION) else block(PreflightId.ROOT_SESSION, "no root")
        return PreflightReport(2, listOf(lock) + l1 + listOf(selinux, mounts, session), binding)
    }

    /** A report is reusable only if nothing it was bound to has changed. */
    fun bindingMatches(a: PreflightBinding, b: PreflightBinding): Boolean =
        a.bootId != null && a.bootId == b.bootId && a.appVersion == b.appVersion && a.fingerprint == b.fingerprint &&
            a.imageDigest != null && a.imageDigest == b.imageDigest
}
