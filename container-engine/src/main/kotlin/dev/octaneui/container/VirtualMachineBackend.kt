package dev.octaneui.container

import dev.octaneui.core.OctaneResult
import dev.octaneui.core.ErrorCode
import dev.octaneui.core.octaneFail

/**
 * Virtual machine with its own kernel (KVM / AVF / crosvm).
 * HONESTLY: starting a VM is NOT implemented; it needs a guest kernel and disk, which redroid images do not contain.
 * Only real checks are implemented: /dev/kvm, AVF and crosvm presence. Unavailability of starting here is
 * "Not implemented", not a hardware incompatibility.
 */
class VirtualMachineBackend : ContainerBackend {
    override val kind = BackendKind.VIRTUAL_MACHINE
    override val implemented = false
    override val experimental = false

    override fun evaluate(ctx: EvalContext) = CompatibilityChecker.evaluate(kind, implemented, experimental, ctx)
    override suspend fun prepare(imageName: String): OctaneResult<Unit> = octaneFail(ErrorCode.NOT_IMPLEMENTED, "VM backend is not implemented")
    override suspend fun start(imageName: String): OctaneResult<Unit> = octaneFail(ErrorCode.NOT_IMPLEMENTED, "VM backend is not implemented")
    override suspend fun stop(): OctaneResult<Unit> = octaneFail(ErrorCode.NOT_RUNNING, "VM is not running")
    override suspend fun restart(imageName: String): OctaneResult<Unit> = octaneFail(ErrorCode.NOT_IMPLEMENTED, "VM backend is not implemented")
    override suspend fun status() = ContainerStatus(running = false)
    override fun hasSession() = false
    override fun currentImage(): String? = null
    override suspend fun logs(): List<String> = emptyList()
}
