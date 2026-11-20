package dev.octaneui.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val binding = PreflightBinding("boot-1", "6.0.0", "fp", "sha256:aa")
    private val goodApp = AppInputs(deviceSecure = false, batteryPercent = 80, batteryTempC = 30f, freeBytes = 10L shl 30)
    private val goodRoot = RootInputs(true, "1", "", "100 99 0:1 / /data rw - ext4 x\n", "/data/user/0/dev.octaneui.app/files/octaneui")

    private fun verdict(r: PreflightReport, id: PreflightId) = r.checks.first { it.id == id }.verdict

    @Test fun lockedDeviceBlocks() {
        val r = Preflight.level1(goodApp.copy(deviceSecure = true), binding)
        assertEquals(PreflightVerdict.BLOCK, verdict(r, PreflightId.SCREEN_LOCK)); assertFalse(r.allowed)
    }

    @Test fun unknownLockStateBlocks() {
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level1(goodApp.copy(deviceSecure = null), binding), PreflightId.SCREEN_LOCK))
    }

    @Test fun batteryAndSpaceLimits() {
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level1(goodApp.copy(batteryPercent = 29), binding), PreflightId.BATTERY))
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level1(goodApp.copy(batteryTempC = 42f), binding), PreflightId.BATTERY))
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level1(goodApp.copy(freeBytes = 1L shl 30), binding), PreflightId.STORAGE_SPACE))
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level1(goodApp.copy(batteryPercent = null), binding), PreflightId.BATTERY))
    }

    @Test fun rootSideRechecksLockEvidence() {
        // The app says "not secure", but root finds a credential file: still BLOCK.
        val r = Preflight.level2(goodApp, goodRoot.copy(spblobListing = "abc.pwd\nabc.spblob\n"), binding)
        assertEquals(PreflightVerdict.BLOCK, verdict(r, PreflightId.SCREEN_LOCK))
        // Unreadable credential store is also BLOCK (fail-closed).
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level2(goodApp, goodRoot.copy(spblobListing = null), binding), PreflightId.SCREEN_LOCK))
    }

    @Test fun selinuxMustBeEnforcing() {
        assertEquals(PreflightVerdict.PASS, verdict(Preflight.level2(goodApp, goodRoot, binding), PreflightId.SELINUX_ENFORCING))
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level2(goodApp, goodRoot.copy(selinuxEnforce = "0"), binding), PreflightId.SELINUX_ENFORCING))
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level2(goodApp, goodRoot.copy(selinuxEnforce = null), binding), PreflightId.SELINUX_ENFORCING))
    }

    @Test fun foreignMountOfOurStorageBlocks() {
        val bad = goodRoot.copy(mountInfo = "1 2 0:3 / ${goodRoot.storagePath}/images/x/rootfs rw - ext4 a\n")
        assertEquals(PreflightVerdict.BLOCK, verdict(Preflight.level2(goodApp, bad, binding), PreflightId.MOUNTS_CLEAN))
    }

    @Test fun everythingGoodIsAllowed() {
        assertTrue(Preflight.level2(goodApp, goodRoot, binding).allowed)
    }

    @Test fun bindingMustMatchBootAppFingerprintAndImage() {
        assertTrue(Preflight.bindingMatches(binding, binding))
        assertFalse(Preflight.bindingMatches(binding, binding.copy(bootId = "boot-2")))
        assertFalse(Preflight.bindingMatches(binding, binding.copy(appVersion = "6.0.1")))
        assertFalse(Preflight.bindingMatches(binding, binding.copy(fingerprint = "other")))
        assertFalse(Preflight.bindingMatches(binding, binding.copy(imageDigest = "sha256:bb")))
        assertFalse("missing boot id is never valid", Preflight.bindingMatches(binding.copy(bootId = null), binding.copy(bootId = null)))
    }
}
