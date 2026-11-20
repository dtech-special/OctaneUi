package dev.octaneui.root

import dev.octaneui.core.OctaneLog

/**
 * The only place through which OctaneUi uses the root of the main system
 * (KernelSU already installed by the user). There is no root manager of its own.
 */
class RootBridge(
    private val shell: ShellRunner = ProcessShellRunner(),
    private val managers: ManagerDetector = ManagerDetector { false },
) {
    private companion object {
        const val TAG = "RootBridge"
        val UID = Regex("""uid=(\d+)""")
        const val PROMPT_TIMEOUT_MS = 30_000L // give the user time to answer the KernelSU prompt
    }

    /** Asks the root manager for access and verifies that the UID is really 0. Call on every launch. */
    suspend fun detect(): RootStatus {
        OctaneLog.i(TAG, "root_check_start")
        val r = shell.run(listOf("su", "-c", "id"), PROMPT_TIMEOUT_MS)
        val status = when {
            r.timedOut -> RootStatus.Denied("No response from the root manager (timeout)")
            r.exitCode == 127 -> RootStatus.SuNotFound
            r.ok -> {
                val uid = UID.find(r.stdout)?.groupValues?.get(1)?.toIntOrNull()
                when (uid) {
                    0 -> RootStatus.Granted(0, managers.detect())
                    null -> RootStatus.Failed("Cannot parse id output: ${r.stdout.trim()}")
                    else -> RootStatus.Failed("su returned uid=$uid instead of 0")
                }
            }
            else -> RootStatus.Denied(r.stderr.trim().ifBlank { "su exited with code ${r.exitCode}" })
        }
        when (status) {
            is RootStatus.Granted -> OctaneLog.i(TAG, "root_granted", status.manager)
            RootStatus.SuNotFound -> OctaneLog.w(TAG, "su_not_found")
            is RootStatus.Denied -> OctaneLog.w(TAG, "root_denied_log", status.details)
            is RootStatus.Failed -> OctaneLog.e(TAG, "root_failed_log", status.message)
        }
        return status
    }

    /** Executes one of the predefined operations. */
    suspend fun execute(op: PrivilegedOp, timeoutMs: Long = 60_000): ShellResult {
        val cmd = op.command()
        OctaneLog.d(TAG, "su_command", cmd)
        return shell.run(listOf("su", "-c", cmd), timeoutMs)
    }
}
