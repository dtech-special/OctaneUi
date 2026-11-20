package dev.octaneui.container

import dev.octaneui.core.OctaneResult

/** Common abstraction for launching guest userspace. Implementations do not know about each other. */
interface ContainerBackend {
    val kind: BackendKind
    val implemented: Boolean
    val experimental: Boolean

    /** Capability check on the current device (a pure function of the collected data). */
    fun evaluate(ctx: EvalContext): BackendCapability

    /** Preparation: image check and generation of service files. */
    suspend fun prepare(imageName: String): OctaneResult<Unit>
    suspend fun start(imageName: String): OctaneResult<Unit>
    suspend fun stop(): OctaneResult<Unit>
    suspend fun restart(imageName: String): OctaneResult<Unit>
    suspend fun status(): ContainerStatus

    /** Whether traces of a previous session exist (to restore state without an extra root request). */
    fun hasSession(): Boolean

    /** Name of the image the last session was (or is being) started from. */
    fun currentImage(): String?

    /** Last lines of the guest log. */
    suspend fun logs(): List<String>
}
