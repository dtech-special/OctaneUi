package dev.octaneui.storage

import dev.octaneui.core.OctaneLog
import dev.octaneui.core.OctaneException
import dev.octaneui.core.OctaneResult
import dev.octaneui.core.ErrorCode
import dev.octaneui.core.octaneFail
import dev.octaneui.core.runOctane
import java.io.File

/**
 * Separate OctaneUi storage. The default root is /data/adb/octaneui
 * (not accessible to a normal app: creating it there would be done by the root bridge).
 * Here is all the logic that works with any root (tested on a temporary folder).
 */
class StorageManager(val root: File) {

    companion object {
        val LAYOUT = listOf(
            "images/redroid-16", "images/custom", "containers/default",
            "cache", "downloads", "logs", "config", "metadata",
        )
        private const val TAG = "StorageManager"
    }

    fun dir(relative: String) = File(root, relative)

    /** Path check before creation: not empty, absolute, without "..". Error texts are produced by the UI from [ErrorCode]. */
    fun validateRoot(): OctaneResult<Unit> {
        val p = root.path
        return when {
            p.isBlank() -> octaneFail(ErrorCode.STORAGE_PATH_EMPTY, "Storage path is empty")
            !root.isAbsolute -> octaneFail(ErrorCode.STORAGE_PATH_NOT_ABSOLUTE, "Path must be absolute: $p", p)
            p.split('/').contains("..") -> octaneFail(ErrorCode.STORAGE_PATH_DOTDOT, "Path contains '..': $p", p)
            root.exists() && !root.isDirectory -> octaneFail(ErrorCode.STORAGE_NOT_DIRECTORY, "Not a directory: $p", p)
            else -> OctaneResult.Ok(Unit)
        }
    }

    /** Creates the directory structure. Existing data is not touched. */
    fun ensureLayout(): OctaneResult<Unit> {
        val v = validateRoot()
        if (v is OctaneResult.Fail) return v
        return runOctane {
            for (rel in LAYOUT) {
                val d = dir(rel)
                if (!d.exists() && !d.mkdirs()) throw OctaneException(ErrorCode.STORAGE_MKDIR_FAILED, "Failed to create ${d.path}", listOf(d.path))
            }
            OctaneLog.i(TAG, "storage_ready", root.path)
        }
    }

    fun usedBytes(relative: String? = null): Long {
        val base = if (relative == null) root else dir(relative)
        return if (base.exists()) base.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    fun freeBytes(): Long? {
        var f: File? = root
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace
    }
}
