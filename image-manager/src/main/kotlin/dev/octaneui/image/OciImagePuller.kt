package dev.octaneui.image

import dev.octaneui.core.OctaneException
import dev.octaneui.core.OctaneLog
import dev.octaneui.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

data class PullTarget(val blobsDir: File, val rootfsDir: File, val metadataDir: File)

@Serializable
data class ImageMetadata(
    val sourceRef: String,
    val manifestDigest: String,
    val configDigest: String,
    val layerDigests: List<String>,
    val totalLayerBytes: Long,
    val pulledAtMs: Long,
    val architecture: String = "arm64",
)

/**
 * Full pull of an OCI image: manifest -> linux/arm64 selection -> layers (with resume, digest check and
 * a shared blob cache) -> merge into a new temporary directory -> rename without replacing.
 */
class OciImagePuller(private val http: OkHttpClient) {
    private val json = Json { prettyPrint = true }

    suspend fun pull(
        imageRef: String, target: PullTarget, onProgress: ProgressListener = {},
    ): ImageMetadata {
        val ref = ImageRef.parse(imageRef)
        val registry = RegistryClient(http, ref.registryBaseUrl)
        OctaneLog.i("Puller", "pull_start", imageRef)
        onProgress(DownloadProgress(Stage.RESOLVING, null, 0, null, 0, null))
        val image = registry.resolve(ref.repository, ref.reference)
        val total = image.layers.sumOf { it.size }
        val n = image.layers.size

        withContext(Dispatchers.IO) {
            target.blobsDir.mkdirs(); target.metadataDir.mkdirs()
            writeNewOrVerify(File(target.metadataDir, "manifest.json"), image.manifestJson)
        }
        registry.downloadBlob(ref.repository, image.config, File(target.metadataDir, "config.json"))

        var base = 0L
        for ((i, layer) in image.layers.withIndex()) {
            val blob = File(target.blobsDir, layer.digest.removePrefix("sha256:"))
            val detail = "${i + 1}/$n"
            registry.downloadBlob(ref.repository, layer, blob, Stage.DOWNLOADING, detail) { p ->
                val done = base + p.downloadedBytes
                val eta = if (p.bytesPerSecond > 0) ((total - done) / p.bytesPerSecond).coerceAtLeast(0) else null
                onProgress(p.copy(downloadedBytes = done, totalBytes = total, etaSeconds = eta))
            }
            base += layer.size
            onProgress(DownloadProgress(Stage.DOWNLOADING, detail, base, total, 0, null))
        }

        val job = currentCoroutineContext().job
        onProgress(DownloadProgress(Stage.EXTRACTING, "0/$n", 0, n.toLong(), 0, null))
        withContext(Dispatchers.IO) {
            // Never replace or delete: an existing system directory stops the installation.
            if (target.rootfsDir.exists()) {
                throw OctaneException(ErrorCode.ROOTFS_EXISTS, "System directory already exists: ${target.rootfsDir}", listOf(target.rootfsDir.path))
            }
            // Unique temporary directory per attempt; a failed attempt leaves its directory behind (it is never auto-deleted).
            val tmp = File(target.rootfsDir.path + ".tmp-" + System.currentTimeMillis())
            val layersTopFirst = image.layers.reversed().map { File(target.blobsDir, it.digest.removePrefix("sha256:")) }
            LayerExtractor.extractMerged(layersTopFirst, tmp) { job.ensureActive() }
            if (Files.exists(target.rootfsDir.toPath())) throw OctaneException(ErrorCode.ROOTFS_EXISTS, "System directory appeared during extraction", listOf(target.rootfsDir.path))
            Files.move(tmp.toPath(), target.rootfsDir.toPath()) // no REPLACE_EXISTING
        }
        onProgress(DownloadProgress(Stage.EXTRACTING, "$n/$n", n.toLong(), n.toLong(), 0, null))

        val meta = ImageMetadata(imageRef, image.manifestDigest, image.config.digest, image.layers.map { it.digest }, total, System.currentTimeMillis())
        withContext(Dispatchers.IO) { writeNewOrVerify(File(target.metadataDir, "image.json"), json.encodeToString(meta)) }
        onProgress(DownloadProgress(Stage.SAVING, null, total, total, 0, 0))
        OctaneLog.i("Puller", "pull_done", image.manifestDigest)
        return meta
    }

    /** Creates the file; if it already exists with identical content it is left alone, otherwise the install stops. */
    private fun writeNewOrVerify(f: File, content: String) {
        if (f.exists()) {
            if (f.readText() != content) throw CodedIOException(ErrorCode.RENAME_FAILED, "Refusing to overwrite ${f.name}", listOf(f.name))
            return
        }
        Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(content.toByteArray()) }
    }
}
