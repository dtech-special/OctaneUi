package dev.octaneui.image

import dev.octaneui.core.ErrorCode
import dev.octaneui.core.OctaneLog
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

data class ExtractStats(var files: Int = 0, var dirs: Int = 0, var symlinks: Int = 0, var whiteouts: Int = 0, var skipped: Int = 0)

/**
 * Merges OCI layers into a NEW root file system without deleting or overwriting anything.
 *
 * Classic extraction applies layers bottom-up and deletes lower-layer files on whiteouts. That needs recursive
 * deletion, which this project forbids. Instead the layers are processed TOP-FIRST:
 *  - an entry from a higher layer always wins, a lower layer never replaces an existing path;
 *  - `.wh.<name>` hides `<name>` (and everything below it) in all LOWER layers;
 *  - `.wh..wh..opq` hides the whole content of its directory in all LOWER layers.
 * Every file is created with CREATE_NEW, so an existing file can never be replaced.
 * Ownership, xattrs and SELinux labels are not applied (not possible without root).
 */
object LayerExtractor {
    private const val TAG = "LayerExtractor"
    private const val WH = ".wh."
    private const val OPAQUE = ".wh..wh..opq"

    private enum class Kind { FILE, DIR, LINK }

    private class State(val root: File) {
        val hidden = HashSet<String>()      // whiteouted paths coming from higher layers
        val opaque = HashSet<String>()      // opaque directories coming from higher layers
        val kinds = HashMap<String, Kind>() // what has already been written, by relative path
        val pendingHardlinks = ArrayList<Pair<String, String>>() // dest rel -> target rel, resolved at the very end
        val stats = ExtractStats()
    }

    /** [layersTopFirst]: the topmost (last applied) layer first. [rootfs] must not exist or must be empty. */
    fun extractMerged(layersTopFirst: List<File>, rootfs: File, checkCancelled: () -> Unit = {}): ExtractStats {
        if (rootfs.exists() && (rootfs.list()?.isNotEmpty() == true)) {
            throw dev.octaneui.core.OctaneException(ErrorCode.ROOTFS_EXISTS, "Target directory is not empty: $rootfs", listOf(rootfs.path))
        }
        rootfs.mkdirs()
        val st = State(rootfs.canonicalFile)
        for (layer in layersTopFirst) {
            val layerHidden = HashSet<String>()
            val layerOpaque = HashSet<String>()
            openTar(layer).use { tar ->
                while (true) {
                    checkCancelled()
                    val entry = tar.nextEntry as TarArchiveEntry? ?: break
                    handle(tar, entry, st, layerHidden, layerOpaque)
                }
            }
            // Whiteouts of this layer only affect layers BELOW it.
            st.hidden += layerHidden
            st.opaque += layerOpaque
        }
        resolveHardlinks(st)
        OctaneLog.i(TAG, "layers_merged", layersTopFirst.size, st.stats.files, st.stats.dirs, st.stats.symlinks, st.stats.whiteouts, st.stats.skipped)
        return st.stats
    }

    private fun openTar(layer: File): TarArchiveInputStream {
        val raw = BufferedInputStream(layer.inputStream(), 128 * 1024)
        raw.mark(4)
        val b0 = raw.read(); val b1 = raw.read(); val b2 = raw.read(); val b3 = raw.read()
        raw.reset()
        val stream: InputStream = when {
            b0 == 0x1f && b1 == 0x8b -> GZIPInputStream(raw, 64 * 1024)
            b0 == 0x28 && b1 == 0xb5 && b2 == 0x2f && b3 == 0xfd -> {
                raw.close(); throw UnsupportedImageException(ErrorCode.ZSTD_UNSUPPORTED, "zstd layers are not supported yet")
            }
            else -> raw
        }
        return TarArchiveInputStream(stream)
    }

    private fun normalize(name: String): String {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw CodedIOException(ErrorCode.UNSAFE_ARCHIVE_PATH, "Unsafe path in archive: $name", listOf(name))
        return parts.joinToString("/")
    }

    /** All proper ancestors of a relative path, e.g. "a/b/c" -> "a", "a/b". */
    private fun ancestors(rel: String): List<String> {
        val parts = rel.split('/')
        return (1 until parts.size).map { parts.subList(0, it).joinToString("/") }
    }

    private fun blockedByHigherLayers(rel: String, st: State): Boolean {
        if (rel in st.hidden) return true
        for (a in ancestors(rel)) {
            if (a in st.hidden || a in st.opaque) return true
            val k = st.kinds[a]
            if (k != null && k != Kind.DIR) return true // a higher layer put a file/link where this layer has a directory
        }
        return false
    }

    private fun handle(tar: TarArchiveInputStream, e: TarArchiveEntry, st: State, layerHidden: MutableSet<String>, layerOpaque: MutableSet<String>) {
        val rel = normalize(e.name)
        if (rel.isEmpty()) return
        val name = rel.substringAfterLast('/')
        val parentRel = rel.substringBeforeLast('/', "")

        if (name == OPAQUE) { layerOpaque += parentRel; st.stats.whiteouts++; return }
        if (name.startsWith(WH)) {
            layerHidden += if (parentRel.isEmpty()) name.removePrefix(WH) else "$parentRel/${name.removePrefix(WH)}"
            st.stats.whiteouts++
            return
        }
        if (blockedByHigherLayers(rel, st)) { st.stats.skipped++; return }

        val dest = File(st.root, rel)
        // The parent must really live inside the root (guards against symlink traversal through earlier entries).
        val parent = dest.parentFile
        if (parent == null || !inside(st.root, parent)) {
            OctaneLog.w(TAG, "skip_symlink", rel); st.stats.skipped++; return
        }
        val existing = st.kinds[rel]
        when {
            e.isDirectory -> {
                if (existing == null) { dest.mkdirs(); st.kinds[rel] = Kind.DIR; st.stats.dirs++ }
                else if (existing != Kind.DIR) st.stats.skipped++
            }
            existing != null -> st.stats.skipped++ // a higher layer already provided this path; never overwrite
            e.isSymbolicLink -> {
                ensureParents(st, parentRel)
                Files.createSymbolicLink(dest.toPath(), Paths.get(e.linkName)); st.kinds[rel] = Kind.LINK; st.stats.symlinks++
            }
            e.isLink -> st.pendingHardlinks += rel to normalize(e.linkName)
            e.isFile -> {
                ensureParents(st, parentRel)
                Files.newOutputStream(dest.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out -> tar.copyTo(out, 64 * 1024) }
                dest.setReadable(true, false)
                if (e.mode and 0b001_001_001 != 0) dest.setExecutable(true, false)
                st.kinds[rel] = Kind.FILE; st.stats.files++
            }
            else -> { OctaneLog.w(TAG, "skip_special", rel); st.stats.skipped++ }
        }
    }

    private fun ensureParents(st: State, parentRel: String) {
        if (parentRel.isEmpty()) return
        val parts = parentRel.split('/')
        for (i in 1..parts.size) {
            val p = parts.subList(0, i).joinToString("/")
            if (st.kinds[p] == null) { File(st.root, p).mkdirs(); st.kinds[p] = Kind.DIR }
        }
    }

    /** Hard links may point into a lower layer that has not been extracted yet, so they are resolved last. */
    private fun resolveHardlinks(st: State) {
        for ((destRel, targetRel) in st.pendingHardlinks) {
            val dest = File(st.root, destRel)
            val target = File(st.root, targetRel)
            if (st.kinds[destRel] != null || st.kinds[targetRel] != Kind.FILE || !inside(st.root, target.parentFile) || !inside(st.root, dest.parentFile)) {
                OctaneLog.w(TAG, "skip_hardlink", destRel, targetRel); st.stats.skipped++; continue
            }
            ensureParents(st, destRel.substringBeforeLast('/', ""))
            Files.createLink(dest.toPath(), target.toPath()); st.kinds[destRel] = Kind.FILE; st.stats.files++
        }
    }

    private fun inside(root: File, f: File?): Boolean {
        if (f == null) return false
        val c = f.canonicalFile.path
        return c == root.path || c.startsWith(root.path + File.separator)
    }

    /** True if the path exists, without following symlinks (used by callers for pre-checks). */
    fun existsNoFollow(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
}
