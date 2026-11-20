package dev.octaneui.storage

import dev.octaneui.core.OctaneResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createsLayout() {
        val sm = StorageManager(File(tmp.root, "octaneui"))
        assertTrue(sm.ensureLayout() is OctaneResult.Ok)
        StorageManager.LAYOUT.forEach { assertTrue(sm.dir(it).isDirectory) }
    }

    @Test fun rejectsRelativeAndDotDot() {
        assertTrue(StorageManager(File("relative/path")).validateRoot() is OctaneResult.Fail)
        assertTrue(StorageManager(File("/a/../b")).validateRoot() is OctaneResult.Fail)
    }
}
