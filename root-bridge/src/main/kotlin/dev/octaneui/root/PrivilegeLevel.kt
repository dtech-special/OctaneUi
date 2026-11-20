package dev.octaneui.root

/** Actual privilege level. Determined by running `su -c id`, not by the presence of a manager app. */
enum class PrivilegeLevel { NON_ROOT, TEMPORARY_ROOT, ROOT }

/**
 * null means root has not been checked yet. If `su` really works but there is no known manager (KernelSU/Magisk/...),
 * root is considered temporary (it can be revoked); otherwise permanent. This is an estimate, not a guarantee.
 */
fun RootStatus?.privilegeLevel(): PrivilegeLevel? = when (this) {
    null -> null
    is RootStatus.Granted -> if (manager == RootManager.NOT_DETECTED) PrivilegeLevel.TEMPORARY_ROOT else PrivilegeLevel.ROOT
    else -> PrivilegeLevel.NON_ROOT
}
