package dev.octaneui.container

import dev.octaneui.core.ErrorCode
import dev.octaneui.detection.HostInfo
import dev.octaneui.root.KernelNode
import dev.octaneui.root.RootStatus

enum class BackendKind { NATIVE_CONTAINER, SHARED_PID, VIRTUAL_MACHINE }

/** User choice: automatic or a specific backend. */
enum class BackendChoice { AUTO, NATIVE_CONTAINER, SHARED_PID, VIRTUAL_MACHINE }

/** Do not confuse: NOT_IMPLEMENTED means the app lacks the feature; UNSUPPORTED means the device does not fit. */
enum class Availability { AVAILABLE, EXPERIMENTAL, UNSUPPORTED, NOT_IMPLEMENTED, UNKNOWN }

enum class HardwareVerdict { OK, MISSING, UNKNOWN }

enum class CheckLevel { OK, WARN, FAIL, UNKNOWN }

enum class CheckId { ARM64, ROOT, IMAGE, TOOLS, NS_MNT, NS_PID, NS_OTHER, CGROUPS, SELINUX, STORAGE, BINDER, GRAPHICS, KVM, AVF, CROSVM, IMAGE_VERSION }

/** Reason category, so that the device is not called incompatible when only the implementation is missing. */
enum class IssueKind { HARDWARE, KERNEL, PRIVILEGE, ENVIRONMENT, IMAGE, NOT_IMPLEMENTED, UNKNOWN }

data class CompatCheck(val id: CheckId, val level: CheckLevel, val detail: String?, val required: Boolean) {
    val issueKind: IssueKind
        get() = if (level == CheckLevel.UNKNOWN) IssueKind.UNKNOWN else when (id) {
            CheckId.ARM64, CheckId.KVM -> IssueKind.HARDWARE
            CheckId.NS_MNT, CheckId.NS_PID, CheckId.NS_OTHER, CheckId.BINDER, CheckId.CGROUPS -> IssueKind.KERNEL
            CheckId.ROOT -> IssueKind.PRIVILEGE
            CheckId.TOOLS, CheckId.AVF, CheckId.CROSVM -> IssueKind.ENVIRONMENT
            CheckId.IMAGE -> IssueKind.IMAGE
            else -> IssueKind.KERNEL
        }
}

data class BackendCapability(
    val kind: BackendKind,
    val implemented: Boolean,
    val experimental: Boolean,
    val availability: Availability,
    val hardware: HardwareVerdict,
    val checks: List<CompatCheck>,
) {
    /** Mandatory checks that did not pass. */
    val blockers: List<CompatCheck> get() = checks.filter { it.required && it.level != CheckLevel.OK }
    val canStart: Boolean get() = availability == Availability.AVAILABLE || availability == Availability.EXPERIMENTAL
}

/** Data read with root rights (null/empty means it could not be read). */
data class KernelProbe(
    val filesystems: String?,
    val cgroups: String?,
    val initNamespaces: List<String>?,
    val visibleNodes: Set<KernelNode>,
    val selinuxEnforce: String?,
    val tools: Map<String, Boolean>,
)

enum class ImageIssue { NO_ROOTFS, NO_TOYBOX, TOYBOX_NOT_ARM64, NO_LINKER, NO_INIT }

data class ImageReport(
    val usable: Boolean,
    val issues: List<ImageIssue>,
    val androidRelease: String?,
    val apiLevel: Int?,
    val initPresent: Boolean,
    val apexFlattened: Boolean?,
)

data class EvalContext(
    val host: HostInfo,
    val root: RootStatus?,
    val probe: KernelProbe?,
    val image: ImageReport?,
    val imageMajor: Int?,
)

data class ContainerStatus(
    val running: Boolean,
    val pid: Int? = null,
    val uptimeSec: Long? = null,
    val processes: Int = 0,
    /** Set if the status could not be obtained (for example root was lost). */
    val errorCode: ErrorCode? = null,
)
