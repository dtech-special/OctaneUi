package dev.octaneui.image

import dev.octaneui.core.ErrorCode
import dev.octaneui.core.OctaneLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Streaming download to a file. Safety rules of this project:
 *  - an existing destination file is NEVER replaced or deleted: if it already verifies, it is reused,
 *    otherwise the download is refused;
 *  - a partial file is only ever APPENDED to (resume); if the server cannot resume, a NEW part file is started;
 *  - a part file that fails verification is renamed to `*.bad.<time>` and kept, never deleted.
 */
class FileDownloader(private val http: OkHttpClient) {

    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        expectedSha256: String? = null,
        stage: Stage = Stage.DOWNLOADING,
        detail: String? = null,
        onProgress: ProgressListener = {},
    ): File {
        if (dest.exists()) {
            if (expectedSha256 != null) {
                val have = withContext(Dispatchers.IO) { Sha256.hexOf(dest) }
                if (have.equals(expectedSha256, ignoreCase = true)) {
                    OctaneLog.i("Downloader", "already_downloaded", dest.name)
                    return dest
                }
                throw ChecksumMismatchException(expectedSha256, have) // refuse to touch an existing, different file
            }
            throw CodedIOException(ErrorCode.RENAME_FAILED, "Destination already exists: ${dest.name}", listOf(dest.name))
        }
        dest.parentFile?.mkdirs()
        var part = File(dest.path + ".part")
        try {
            transfer(url, headers, part, stage, detail, resume = true, onProgress)
        } catch (_: RangeRejectedException) {
            part = nextFreePart(dest) // keep the old partial file untouched
            transfer(url, headers, part, stage, detail, resume = false, onProgress)
        }
        if (expectedSha256 != null) {
            onProgress(DownloadProgress(Stage.VERIFYING, detail, part.length(), part.length(), 0L, null))
            val actual = withContext(Dispatchers.IO) { Sha256.hexOf(part) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                withContext(Dispatchers.IO) { keepAsBad(part) }
                throw ChecksumMismatchException(expectedSha256, actual)
            }
        }
        withContext(Dispatchers.IO) {
            if (Files.exists(dest.toPath())) throw CodedIOException(ErrorCode.RENAME_FAILED, "Destination appeared during download: ${dest.name}", listOf(dest.name))
            Files.move(part.toPath(), dest.toPath()) // no REPLACE_EXISTING: fails instead of overwriting
        }
        return dest
    }

    private fun nextFreePart(dest: File): File {
        var i = 1
        while (true) { val f = File(dest.path + ".part$i"); if (!f.exists()) return f; i++ }
    }

    private fun keepAsBad(part: File) {
        val bad = File(part.path.removeSuffix(".part") + ".bad." + System.currentTimeMillis())
        if (!bad.exists()) Files.move(part.toPath(), bad.toPath())
    }

    private suspend fun transfer(
        url: String, headers: Map<String, String>, part: File,
        stage: Stage, detail: String?, resume: Boolean, onProgress: ProgressListener,
    ) = coroutineScope {
        val resumeFrom = if (resume && part.isFile) part.length() else 0L
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (resumeFrom > 0) rb.header("Range", "bytes=$resumeFrom-")
        val call = http.newCall(rb.build())
        // Cancelling the coroutine immediately aborts the network call instead of waiting for a read timeout.
        val watcher = launch(Dispatchers.Default) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { resp ->
                    if (resp.code == 416 && resumeFrom > 0) throw RangeRejectedException()
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
                    val body = resp.body ?: throw CodedIOException(ErrorCode.EMPTY_RESPONSE, "Empty server response")
                    // Server ignored the Range header: appending would corrupt the file and truncating is forbidden.
                    if (resumeFrom > 0 && resp.code != 206) throw RangeRejectedException()
                    val startOffset = resumeFrom
                    val len = body.contentLength()
                    val total = if (len >= 0) startOffset + len else null
                    val tracker = ProgressTracker(total, startOffset)
                    FileOutputStream(part, /* append = */ true).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = startOffset
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                ensureActive()
                                out.write(buf, 0, n)
                                done += n
                                val now = System.nanoTime()
                                if (now - lastEmit > 250_000_000L) {
                                    lastEmit = now
                                    onProgress(tracker.update(done, stage, detail))
                                }
                            }
                            onProgress(tracker.update(done, stage, detail))
                            if (total != null && done != total) {
                                throw CodedIOException(ErrorCode.INCOMPLETE_DOWNLOAD, "Incomplete download: $done of $total", listOf(done.toString(), total.toString()))
                            }
                        }
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }
}
