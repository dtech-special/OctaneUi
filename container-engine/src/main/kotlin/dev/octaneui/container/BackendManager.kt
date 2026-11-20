package dev.octaneui.container

import dev.octaneui.core.LogLevel
import dev.octaneui.core.OctaneLog
import dev.octaneui.detection.HostDetector
import dev.octaneui.detection.HostInfo
import dev.octaneui.root.KernelNode
import dev.octaneui.root.PrivilegedOp
import dev.octaneui.root.RootBridge
import dev.octaneui.root.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Collects device data once, evaluates all backends and selects a suitable one. */
class BackendManager(
    private val detector: HostDetector,
    private val root: RootBridge,
    private val baseDir: File,
    /** Level 1 facts (KeyguardManager, battery, free space) are collected by the app. */
    private val appInputs: () -> AppInputs = { AppInputs(null, null, null, null) },
    private val appVersion: String = "",
    private val bootId: () -> String? = { null },
) {
    data class Snapshot(
        val host: HostInfo,
        val root: RootStatus?,
        val image: ImageReport?,
        val capabilities: Map<BackendKind, BackendCapability>,
        /** Level 2 report when root is available, otherwise Level 1. Bound to boot, app version, host build and image. */
        val preflight: PreflightReport? = null,
        val rootInputs: RootInputs? = null,
    )

    private val backends: Map<BackendKind, ContainerBackend> = listOf<ContainerBackend>(
        GuestBackend(BackendKind.NATIVE_CONTAINER, experimental = false, unshareFlags = "-m -p -f -i -u -n", root = root, baseDir = baseDir),
        GuestBackend(BackendKind.SHARED_PID, experimental = true, unshareFlags = "-m", root = root, baseDir = baseDir),
        VirtualMachineBackend(),
    ).associateBy { it.kind }

    val all: List<ContainerBackend> get() = backends.values.toList()
    fun backend(kind: BackendKind): ContainerBackend = backends.getValue(kind)

    @Volatile private var lastRoot: RootStatus? = null

    /**
     * @param askRoot true: ask the root manager for root (shows the KernelSU prompt if needed);
     * false: use the last known result without calling su.
     */
    suspend fun refresh(imageName: String?, imageMajor: Int?, askRoot: Boolean, imageDigest: String? = null): Snapshot {
        val host = withContext(Dispatchers.IO) { detector.detect() }
        if (askRoot) lastRoot = root.detect()
        val rs = lastRoot
        val probe = if (rs is RootStatus.Granted) probeKernel() else null
        val image = imageName?.let { withContext(Dispatchers.IO) { ImageInspector.inspect(File(baseDir, "images/$it/rootfs")) } }
        val ctx = EvalContext(host, rs, probe, image, imageMajor)
        val caps = backends.mapValues { (_, b) -> b.evaluate(ctx) }
        caps.values.forEach { c -> OctaneLog.i("Backends", "backend_capability", c.kind.name, c.availability.name, c.hardware.name) }
        val binding = PreflightBinding(bootId(), appVersion, host.fingerprint, imageDigest)
        val app = appInputs()
        val rootInputs = if (rs is RootStatus.Granted) collectRootInputs() else null
        val preflight = if (rootInputs != null) Preflight.level2(app, rootInputs, binding) else Preflight.level1(app, binding)
        preflight.checks.forEach { c -> OctaneLog.log(if (c.verdict == PreflightVerdict.PASS) LogLevel.INFO else LogLevel.WARN, "Backends", "preflight_check", listOf(preflight.level.toString(), c.id.name, c.verdict.name, c.detail ?: "")) }
        return Snapshot(host, rs, image, caps, preflight, rootInputs)
    }

    fun rootStatus(): RootStatus? = lastRoot

    /** Level 2 facts, read-only commands only. */
    private suspend fun collectRootInputs(): RootInputs = RootInputs(
        rootGranted = true,
        selinuxEnforce = root.execute(PrivilegedOp.CatNode(KernelNode.SELINUX_ENFORCE)).takeIf { it.ok }?.stdout,
        spblobListing = root.execute(PrivilegedOp.ListSpblob).takeIf { it.ok }?.stdout,
        mountInfo = root.execute(PrivilegedOp.CatNode(KernelNode.PROC_MOUNTINFO)).takeIf { it.ok }?.stdout,
        storagePath = baseDir.canonicalPath,
    )

    private suspend fun probeKernel(): KernelProbe {
        suspend fun cat(n: KernelNode) = root.execute(PrivilegedOp.CatNode(n)).takeIf { it.ok }?.stdout
        val ns = root.execute(PrivilegedOp.ListDirNode(KernelNode.PROC_INIT_NS)).takeIf { it.ok }
            ?.stdout?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
        val visible = mutableSetOf<KernelNode>()
        for (n in listOf(KernelNode.DEV_BINDER, KernelNode.DEV_BINDERFS, KernelNode.DEV_KVM, KernelNode.APEX, KernelNode.LINKERCONFIG, KernelNode.CROSVM)) {
            if (root.execute(PrivilegedOp.ListNode(n)).ok) visible += n
        }
        val tools = root.execute(PrivilegedOp.ProbeTools).takeIf { it.ok }?.stdout?.lines()
            ?.mapNotNull { l -> l.split(':').takeIf { it.size == 2 }?.let { it[0].trim() to (it[1].trim() == "ok") } }?.toMap() ?: emptyMap()
        return KernelProbe(cat(KernelNode.PROC_FILESYSTEMS), cat(KernelNode.PROC_CGROUPS), ns, visible, cat(KernelNode.SELINUX_ENFORCE), tools)
    }

    fun resolve(choice: BackendChoice, caps: Map<BackendKind, BackendCapability>): BackendKind = BackendSelector.resolve(choice, caps)
}

object BackendSelector {
    /**
     * AUTO: Native Container if it is really available; otherwise Shared PID (it only needs a mount namespace, the weakest kernel requirement);
     * otherwise VM if available; otherwise Shared PID (to show the concrete reasons).
     */
    fun resolve(choice: BackendChoice, caps: Map<BackendKind, BackendCapability>): BackendKind = when (choice) {
        BackendChoice.NATIVE_CONTAINER -> BackendKind.NATIVE_CONTAINER
        BackendChoice.SHARED_PID -> BackendKind.SHARED_PID
        BackendChoice.VIRTUAL_MACHINE -> BackendKind.VIRTUAL_MACHINE
        BackendChoice.AUTO -> when {
            caps[BackendKind.NATIVE_CONTAINER]?.availability == Availability.AVAILABLE -> BackendKind.NATIVE_CONTAINER
            caps[BackendKind.SHARED_PID]?.canStart == true -> BackendKind.SHARED_PID
            caps[BackendKind.VIRTUAL_MACHINE]?.availability == Availability.AVAILABLE -> BackendKind.VIRTUAL_MACHINE
            else -> BackendKind.SHARED_PID
        }
    }
}
