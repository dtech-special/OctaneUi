package dev.octaneui.core

/** Container states. */
enum class ContainerState {
    NOT_INSTALLED, DOWNLOADING, UNPACKING, PREPARING, READY, RUNNING, STOPPING, ERROR, UNSUPPORTED
}

/** Error code: libraries contain no translatable text; the UI picks the string in the device language by code. */
enum class ErrorCode {
    UNKNOWN,
    STORAGE_PATH_EMPTY, STORAGE_PATH_NOT_ABSOLUTE, STORAGE_PATH_DOTDOT, STORAGE_NOT_DIRECTORY,
    STORAGE_MKDIR_FAILED, STORAGE_DELETE_FAILED, STORAGE_BAD_IMAGE_NAME,
    HTTP_STATUS, RANGE_REJECTED, CHECKSUM_MISMATCH, RATE_LIMIT, NO_ARCH_VARIANT, MANIFEST_NO_CONFIG, MANIFEST_NO_LAYERS,
    EMPTY_RESPONSE, INCOMPLETE_DOWNLOAD, RENAME_FAILED, REGISTRY_TOKEN, UNSAFE_ARCHIVE_PATH, ZSTD_UNSUPPORTED,
    ROOTFS_EXISTS, BAD_SYSTEM_NAME, EMPTY_IMAGE_REF, NO_REPOSITORY, ONLY_SHA256,
    NOT_INSTALLED, IMAGE_NOT_INSTALLED, UNSUPPORTED_CONFIG, NOT_IMPLEMENTED, NOT_RUNNING,
    ROOT_REQUIRED, ROOT_LOST, GUEST_START_FAILED, GUEST_ALREADY_RUNNING, GUEST_STOP_FAILED, IMAGE_INVALID, BACKEND_NOT_AVAILABLE, IMAGE_IN_USE,
}

/** Any error with a code and arguments for a localized message. */
interface CodedError {
    val code: ErrorCode
    val args: List<String>
}

/** Exception with a code; [message] is technical text for logs, not for the user. */
class OctaneException(override val code: ErrorCode, message: String, override val args: List<String> = emptyList()) : Exception(message), CodedError

data class ErrorInfo(val code: ErrorCode, val args: List<String>, val message: String)

fun Throwable.toErrorInfo(): ErrorInfo =
    if (this is CodedError) ErrorInfo(code, args, message ?: code.name) else ErrorInfo(ErrorCode.UNKNOWN, emptyList(), message ?: javaClass.simpleName)

/** Typed result instead of exceptions in the business logic. */
sealed interface OctaneResult<out T> {
    data class Ok<T>(val value: T) : OctaneResult<T>
    data class Fail(
        val message: String,
        val cause: Throwable? = null,
        val code: ErrorCode = ErrorCode.UNKNOWN,
        val args: List<String> = emptyList(),
    ) : OctaneResult<Nothing> {
        fun toInfo() = ErrorInfo(code, args, message)
    }
}

fun octaneFail(code: ErrorCode, message: String, vararg args: String) = OctaneResult.Fail(message, null, code, args.toList())

inline fun <T> runOctane(block: () -> T): OctaneResult<T> =
    try { OctaneResult.Ok(block()) } catch (e: OctaneException) {
        OctaneResult.Fail(e.message ?: e.code.name, e, e.code, e.args)
    } catch (t: Throwable) { OctaneResult.Fail(t.message ?: t.javaClass.simpleName, t) }
