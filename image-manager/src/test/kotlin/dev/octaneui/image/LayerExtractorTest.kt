package dev.octaneui.image

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** All tests use extractMerged: layers are given TOP-FIRST and nothing is ever deleted or overwritten. */
class LayerExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun layer(name: String, gz: Boolean = true, block: TarArchiveOutputStream.() -> Unit): File {
        val f = File(tmp.root, name)
        val raw = f.outputStream().let { if (gz) GZIPOutputStream(it) else it }
        TarArchiveOutputStream(raw).use { it.block() }
        return f
    }

    private fun TarArchiveOutputStream.file(path: String, text: String) {
        val b = text.toByteArray()
        putArchiveEntry(TarArchiveEntry(path).apply { size = b.size.toLong(); mode = 420 })
        write(b); closeArchiveEntry()
    }

    private fun root() = File(tmp.root, "rootfs")

    @Test fun whiteoutHidesFileOfLowerLayerWithoutDeletingAnything() {
        val lower = layer("l1.tar.gz") { file("etc/a.conf", "A"); file("etc/b.conf", "B"); file("keep.txt", "K") }
        val upper = layer("l2.tar.gz", gz = false) { file("etc/.wh.a.conf", "") }
        val stats = LayerExtractor.extractMerged(listOf(upper, lower), root())
        assertFalse(File(root(), "etc/a.conf").exists())
        assertEquals("B", File(root(), "etc/b.conf").readText())
        assertEquals("K", File(root(), "keep.txt").readText())
        assertTrue(stats.whiteouts >= 1 && stats.skipped >= 1)
    }

    @Test fun directoryWhiteoutHidesWholeSubtree() {
        val lower = layer("l1.tar.gz") { file("d/x/1.txt", "1"); file("d/2.txt", "2"); file("other.txt", "o") }
        val upper = layer("l2.tar.gz") { file(".wh.d", "") }
        LayerExtractor.extractMerged(listOf(upper, lower), root())
        assertFalse(File(root(), "d").exists())
        assertTrue(File(root(), "other.txt").exists())
    }

    @Test fun opaqueWhiteoutHidesLowerContentButKeepsOwn() {
        val lower = layer("l1.tar.gz") { file("d/old.txt", "old") }
        val upper = layer("l2.tar.gz") { file("d/.wh..wh..opq", ""); file("d/new.txt", "new") }
        LayerExtractor.extractMerged(listOf(upper, lower), root())
        assertFalse(File(root(), "d/old.txt").exists())
        assertEquals("new", File(root(), "d/new.txt").readText())
    }

    @Test fun upperLayerWinsAndNothingIsOverwritten() {
        val lower = layer("l1.tar.gz") { file("f", "1") }
        val upper = layer("l2.tar.gz") { file("f", "2") }
        LayerExtractor.extractMerged(listOf(upper, lower), root())
        assertEquals("2", File(root(), "f").readText())
    }

    @Test fun refusesNonEmptyTargetInsteadOfReplacing() {
        File(root(), "existing").apply { parentFile.mkdirs(); writeText("x") }
        assertThrows(dev.octaneui.core.OctaneException::class.java) {
            LayerExtractor.extractMerged(listOf(layer("l.tar.gz") { file("a", "a") }), root())
        }
        assertEquals("x", File(root(), "existing").readText())
    }

    @Test fun rejectsPathTraversal() {
        assertThrows(IOException::class.java) { LayerExtractor.extractMerged(listOf(layer("bad.tar.gz") { file("../evil.txt", "x") }), root()) }
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun symlinkEscapeIsSkipped() {
        val l = layer("s.tar.gz") {
            putArchiveEntry(TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply { linkName = tmp.root.path })
            closeArchiveEntry()
            file("link/pwned.txt", "x")
        }
        val st = LayerExtractor.extractMerged(listOf(l), root())
        assertTrue(st.skipped >= 1)
        assertFalse(File(tmp.root, "pwned.txt").exists())
    }

    @Test fun hardlinkToLowerLayerIsResolvedAtTheEnd() {
        val lower = layer("l1.tar.gz") { file("bin/real", "payload") }
        val upper = layer("l2.tar.gz") {
            putArchiveEntry(TarArchiveEntry("bin/alias", TarConstants.LF_LINK).apply { linkName = "bin/real" })
            closeArchiveEntry()
        }
        LayerExtractor.extractMerged(listOf(upper, lower), root())
        assertEquals("payload", File(root(), "bin/alias").readText())
    }
}
