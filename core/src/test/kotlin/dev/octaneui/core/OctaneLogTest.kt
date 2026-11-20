package dev.octaneui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OctaneLogTest {
    @Test fun keepsAtMost1000Entries() {
        OctaneLog.clear()
        repeat(1200) { OctaneLog.i("t", "m$it") }
        assertEquals(1000, OctaneLog.entries.value.size)
        assertEquals("m1199", OctaneLog.entries.value.last().key)
    }

    @Test fun runOctaneCapturesException() {
        val r = runOctane { error("boom") }
        assertTrue(r is OctaneResult.Fail && r.message == "boom")
    }
}
