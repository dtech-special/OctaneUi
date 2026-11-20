package dev.octaneui.container

import dev.octaneui.core.ErrorCode
import dev.octaneui.core.LogLevel
import dev.octaneui.core.OctaneException
import dev.octaneui.core.OctaneLog
import dev.octaneui.core.OctaneResult
import dev.octaneui.core.octaneFail
import dev.octaneui.core.runOctane
import dev.octaneui.root.PrivilegedOp
import dev.octaneui.root.RootBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Shared implementation of the two backends that use the host kernel directly.
 *  - Native Container: mount + PID + IPC + UTS + network namespaces, own PID 1.
 *  - Shared PID: mount namespace only, the PID space is shared with the host (experimental).
 *
 * Safety policy implemented here:
 *  - every start creates a NEW session directory (`run/<kind>/s<millis>`), files inside are created with CREATE_NEW;
 *  - nothing is ever deleted or overwritten: a stopped session is marked with a `stopped` file and kept;
 *  - all log lines come from the real execution (guest.log is written by the scripts and the tools they run).
 */
class GuestBackend(
    override val kind: BackendKind,
    override val experimental: Boolean,
    private val unshareFlags: String,
    private val root: RootBridge,
    private val baseDir: File,
) : ContainerBackend {

    override val implemented = true
    private val tag = "Backend-${kind.name}"
    private val sessionsDir = File(baseDir, "run/${if (kind == BackendKind.NATIVE_CONTAINER) "native" else "shared"}")
    private val baseRoot get() = baseDir.path

    private fun rootfs(name: String) = File(baseDir, "images/$name/rootfs")

    /** Newest session directory, or null. Names are `s<epoch millis>`, so lexicographic order is chronological. */
    private fun latestSession(): File? =
        sessionsDir.listFiles { f -> f.isDirectory && f.name.startsWith("s") }?.maxByOrNull { it.name }

    /** Newest session that has not been marked as stopped. */
    private fun activeSession(): File? = latestSession()?.takeIf { !File(it, "stopped").exists() }

    override fun evaluate(ctx: EvalContext) = CompatibilityChecker.evaluate(kind, implemented, experimental, ctx)

    override fun hasSession() = activeSession() != null

    override fun currentImage(): String? =
        runCatching { latestSession()?.let { File(it, "image").readText().trim().ifEmpty { null } } }.getOrNull()

    private fun writeNew(f: File, text: String) {
        Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(text.toByteArray()) }
    }

    /** Creates a fresh session directory with the generated scripts. Never reuses or replaces an existing one. */
    override suspend fun prepare(imageName: String): OctaneResult<Unit> = withContext(Dispatchers.IO) {
        prepareSession(imageName).let { r -> if (r is OctaneResult.Fail) r else OctaneResult.Ok(Unit) }
    }

    private fun prepareSession(imageName: String): OctaneResult<File> = runOctane {
        val rf = rootfs(imageName)
        val report = ImageInspector.inspect(rf)
        OctaneLog.i(tag, "image_inspect", imageName, report.issues.joinToString(",").ifEmpty { "ok" })
        if (!report.usable) {
            throw OctaneException(ErrorCode.IMAGE_INVALID, "Image is not usable: ${report.issues}", listOf(report.issues.joinToString(",") { it.name }))
        }
        check(sessionsDir.isDirectory || sessionsDir.mkdirs()) { "Cannot create ${sessionsDir.path}" }
        var stamp = System.currentTimeMillis()
        var session = File(sessionsDir, "s$stamp")
        while (!session.mkdir()) { stamp++; session = File(sessionsDir, "s$stamp") } // mkdir fails if it exists: never reuse
        val canonRoot = rf.canonicalPath
        val prev = activeSession()?.takeIf { it != session }?.path
        writeNew(File(session, "start.sh"), GuestScripts.start(canonRoot, session.path, unshareFlags, kind.name.lowercase(), prev))
        writeNew(File(session, "enter.sh"), GuestScripts.enter(canonRoot, session.path))
        writeNew(File(session, "stop.sh"), GuestScripts.stop(canonRoot, session.path))
        writeNew(File(session, "status.sh"), GuestScripts.status(canonRoot, session.path))
        writeNew(File(session, "image"), imageName)
        OctaneLog.i(tag, "guest_prepare", session.path)
        session
    }

    override suspend fun start(imageName: String): OctaneResult<Unit> {
        // A guest that is already running is never duplicated.
        val before = status()
        if (before.errorCode != null) return octaneFail(ErrorCode.ROOT_REQUIRED, "Root is not available")
        if (before.running) return octaneFail(ErrorCode.GUEST_ALREADY_RUNNING, "Guest is already running")

        val prepared = withContext(Dispatchers.IO) { prepareSession(imageName) }
        val session = when (prepared) {
            is OctaneResult.Fail -> return prepared
            is OctaneResult.Ok -> prepared.value
        }

        OctaneLog.i(tag, "guest_start", kind.name)
        root.execute(PrivilegedOp.StartScript(File(session, "start.sh").path, baseRoot), 15_000)

        // Wait for the real outcome by polling the status (up to ~5 s), not for a fixed delay.
        var st = status()
        var waited = 0
        while (!st.running && waited < 5_000 && !guestFinished()) {
            delay(500); waited += 500
            st = status()
        }
        val lines = logs()
        mirrorToAppLog(lines)

        // RUNNING is reported only if a guest process really exists and its root is our rootfs.
        if (st.running) {
            OctaneLog.i(tag, "guest_started", st.pid ?: -1, st.processes)
            return OctaneResult.Ok(Unit)
        }
        val errors = GuestLogParser.parse(lines).filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARN }
        val reason = errors.takeLast(4).joinToString(" | ") { it.text }.ifEmpty { "no output" }
        OctaneLog.e(tag, "guest_start_failed", reason)
        return octaneFail(ErrorCode.GUEST_START_FAILED, "Guest process did not start: $reason", reason)
    }

    /** The script finished with an error line, waiting longer makes no sense. */
    private suspend fun guestFinished(): Boolean = logs().any { it.startsWith("[octaneui] error:") }

    /** Copies the real guest.log lines into the application log (levels come from the line prefix). */
    private fun mirrorToAppLog(lines: List<String>) {
        GuestLogParser.parse(lines).forEach { OctaneLog.log(it.level, "guest", "raw", listOf(it.text)) }
    }

    override suspend fun stop(): OctaneResult<Unit> {
        val session = activeSession() ?: return octaneFail(ErrorCode.NOT_RUNNING, "Container is not running")
        val r = root.execute(PrivilegedOp.RunScript(File(session, "stop.sh").path, baseRoot), 30_000)
        if (!r.ok) return octaneFail(ErrorCode.ROOT_LOST, "Cannot run stop script: ${r.stderr.trim()}")
        val res = GuestScripts.parseStop(r.stdout)
        if (res.leakedMounts > 0) OctaneLog.w(tag, "guest_mount_leak", res.leakedMounts)
        val st = status()
        return if (st.running) {
            OctaneLog.e(tag, "guest_stop_failed", st.processes)
            octaneFail(ErrorCode.GUEST_STOP_FAILED, "Guest processes are still alive: ${st.processes}", st.processes.toString())
        } else {
            OctaneLog.i(tag, "guest_stopped")
            OctaneResult.Ok(Unit)
        }
    }

    override suspend fun restart(imageName: String): OctaneResult<Unit> {
        val s = stop()
        if (s is OctaneResult.Fail && s.code != ErrorCode.NOT_RUNNING) return s
        return start(imageName)
    }

    override suspend fun status(): ContainerStatus {
        val session = activeSession() ?: return ContainerStatus(running = false)
        val r = root.execute(PrivilegedOp.RunScript(File(session, "status.sh").path, baseRoot), 15_000)
        if (!r.ok || !r.stdout.contains("uid=0")) return ContainerStatus(running = false, errorCode = ErrorCode.ROOT_LOST)
        return GuestScripts.parseStatus(r.stdout)
    }

    override suspend fun logs(): List<String> {
        val f = latestSession()?.let { File(it, "guest.log") } ?: return emptyList()
        if (!f.exists()) return emptyList()
        val r = root.execute(PrivilegedOp.TailFile(f.path, baseRoot, 80), 10_000)
        return if (r.ok) r.stdout.lines().filter { it.isNotBlank() } else emptyList()
    }
}
