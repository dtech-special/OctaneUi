package dev.octaneui.detection

/**
 * Snapshot of the host parameters. null = unavailable (the value could not be read,
 * and it is never invented).
 */
data class HostInfo(
    val deviceModel: String,
    val soc: String?,
    val androidRelease: String,
    val apiLevel: Int,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val kernelVersion: String?,
    val cpuCores: Int,
    val totalRamBytes: Long?,
    val freeStorageBytes: Long?,
    val selinux: SelinuxMode?,
    /** true only if the node is really visible to the app; SELinux may hide it from non-root. */
    val binderDeviceVisible: Boolean,
    val binderFsVisible: Boolean,
    val kvmVisible: Boolean,
    val namespaces: List<String>?,
    /** System feature Android Virtualization Framework (android.software.virtualization_framework). */
    val avfFeature: Boolean = false,
    val fingerprint: String = "",
    val codename: String = "",
    /** Samsung One UI version code from Build.VERSION.SEM_PLATFORM_INT, if present. */
    val oneUiPlatformInt: Int? = null,
) {
    val isArm64: Boolean get() = primaryAbi == "arm64-v8a"
    val androidMajor: Int? get() = AndroidVersionMatcher.majorOf(androidRelease)
}

enum class SelinuxMode { ENFORCING, PERMISSIVE }
