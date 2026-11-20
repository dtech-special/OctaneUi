package dev.octaneui.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotSwitchDryRunTest {
    @Test fun unknownCombinationAlwaysBlocksAndNeverStarts() {
        val r = HotSwitchDryRun.evaluate(DryRunInputs(null, "samsung/x/y", 36, "sha256:aa", guestStorageIsolated = false))
        assertFalse(r.wouldStart)
        assertTrue(r.steps.any { it.id == DryRunId.DEVICE_SUPPORTED && it.verdict == PreflightVerdict.BLOCK })
        assertTrue(r.steps.any { it.id == DryRunId.SUPERVISOR_WATCHDOG && it.verdict == PreflightVerdict.BLOCK })
    }

    @Test fun evenAPerfectPreflightCannotUnlockUnimplementedSteps() {
        val binding = PreflightBinding("b", "6.0.0", "fp", "sha256:aa")
        val pf = Preflight.level2(
            AppInputs(false, 90, 25f, 20L shl 30),
            RootInputs(true, "1", "", "", "/x"), binding,
        )
        val r = HotSwitchDryRun.evaluate(DryRunInputs(pf, "fp", 36, "sha256:aa", guestStorageIsolated = true))
        assertFalse("storage guard, installd table and watchdog are not implemented", r.wouldStart)
    }
}
