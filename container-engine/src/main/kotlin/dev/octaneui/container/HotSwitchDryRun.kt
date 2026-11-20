package dev.octaneui.container

/**
 * Dry run of the future hot-switch. It is a diagnostic ONLY: it never stops a host service, never mounts anything,
 * never changes a partition and never starts a guest. Every prerequisite is evaluated; the verdict is fail-closed.
 *
 * Several prerequisites cannot be satisfied yet (no verified device/guest table, no storage guard patch, no
 * IInstalld/IVold compatibility table). They are reported as BLOCK on purpose: unknown means forbidden.
 */
enum class DryRunId {
    PREFLIGHT, DEVICE_SUPPORTED, GUEST_SUPPORTED, INSTALLD_ABI_TABLE, STORAGE_GUARD_PATCH,
    ISOLATED_USERDATA, DEV_POLICY, SELINUX_ENFORCING, SUPERVISOR_WATCHDOG,
}

data class DryRunStep(val id: DryRunId, val verdict: PreflightVerdict, val detail: String? = null)

data class DryRunReport(val steps: List<DryRunStep>) {
    /** Always false while any step blocks. The dry run itself never performs a switch. */
    val wouldStart: Boolean get() = steps.all { it.verdict == PreflightVerdict.PASS }
}

/** What is known about the host and the guest. Verified combinations come from [KnownCombinations]. */
data class DryRunInputs(
    val preflight: PreflightReport?,
    val hostFingerprint: String,
    val guestSdk: Int?,
    val guestImageDigest: String?,
    val guestStorageIsolated: Boolean,
)

object KnownCombinations {
    /** Verified (host fingerprint, guest SDK) pairs. Empty on purpose: nothing has been verified on a real device yet. */
    val verified: Set<Pair<String, Int>> = emptySet()

    /** SHA-256 of host services.jar for which an installd/vold Binder compatibility table exists. Empty: none yet. */
    val installdTables: Set<String> = emptySet()
}

object HotSwitchDryRun {
    private fun pass(id: DryRunId, d: String? = null) = DryRunStep(id, PreflightVerdict.PASS, d)
    private fun block(id: DryRunId, d: String) = DryRunStep(id, PreflightVerdict.BLOCK, d)

    fun evaluate(i: DryRunInputs): DryRunReport {
        val pf = i.preflight
        val steps = listOf(
            when {
                pf == null -> block(DryRunId.PREFLIGHT, "not run")
                !pf.allowed -> block(DryRunId.PREFLIGHT, pf.blockers.joinToString { it.id.name })
                else -> pass(DryRunId.PREFLIGHT)
            },
            if (KnownCombinations.verified.any { it.first == i.hostFingerprint }) pass(DryRunId.DEVICE_SUPPORTED)
            else block(DryRunId.DEVICE_SUPPORTED, "unknown host build: ${i.hostFingerprint}"),
            if (i.guestSdk != null && (i.hostFingerprint to i.guestSdk) in KnownCombinations.verified) pass(DryRunId.GUEST_SUPPORTED)
            else block(DryRunId.GUEST_SUPPORTED, "unknown host/guest combination"),
            block(DryRunId.INSTALLD_ABI_TABLE, "no verified IInstalld/IVold compatibility table"),
            block(DryRunId.STORAGE_GUARD_PATCH, "storage guard patch is not installed or not verified"),
            if (i.guestStorageIsolated) pass(DryRunId.ISOLATED_USERDATA) else block(DryRunId.ISOLATED_USERDATA, "no isolated guest userdata image"),
            block(DryRunId.DEV_POLICY, "no per-device /dev allow-list"),
            if (pf?.checks?.any { it.id == PreflightId.SELINUX_ENFORCING && it.verdict == PreflightVerdict.PASS } == true) pass(DryRunId.SELINUX_ENFORCING)
            else block(DryRunId.SELINUX_ENFORCING, "SELinux is not confirmed as Enforcing"),
            block(DryRunId.SUPERVISOR_WATCHDOG, "root supervisor and watchdog are not implemented"),
        )
        return DryRunReport(steps)
    }
}
