package dev.octaneui.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBridgeTest {
    private class Fake(val result: ShellResult) : ShellRunner {
        var last: List<String>? = null
        override suspend fun run(argv: List<String>, timeoutMs: Long): ShellResult { last = argv; return result }
    }

    private fun detect(r: ShellResult, installed: Set<String> = emptySet()) = runBlocking {
        RootBridge(Fake(r), ManagerDetector { it in installed }).detect()
    }

    @Test fun grantedWhenUidZero() {
        val s = detect(ShellResult(0, "uid=0(root) gid=0(root)", ""), setOf("me.weishu.kernelsu"))
        assertEquals(RootStatus.Granted(0, RootManager.KERNELSU), s)
    }

    @Test fun uidNotZeroIsFailure() {
        assertTrue(detect(ShellResult(0, "uid=2000(shell)", "")) is RootStatus.Failed)
    }

    @Test fun suMissing() = assertEquals(RootStatus.SuNotFound, detect(ShellResult(127, "", "x")))

    @Test fun denied() {
        assertTrue(detect(ShellResult(1, "", "Permission denied")) is RootStatus.Denied)
        assertTrue(detect(ShellResult(-1, "", "timeout", timedOut = true)) is RootStatus.Denied)
    }

    @Test fun executeUsesCommandOfOp() {
        val f = Fake(ShellResult(0, "", ""))
        runBlocking { RootBridge(f).execute(PrivilegedOp.Id) }
        assertEquals(listOf("su", "-c", "id"), f.last)
    }

    @Test fun pathGuardRejectsUnsafe() {
        val root = "/data/adb/octaneui"
        PrivilegedOp.MkdirP("$root/images/redroid-16")
        listOf("/etc", "$root/../etc", "$root/a b", "$root/x;rm", "$root/", "relative", "/data/adb/octaneui2/x")
            .forEach { p -> assertThrows(IllegalArgumentException::class.java) { PrivilegedOp.MkdirP(p) } }
    }

    @Test fun noDestructiveOperationExists() {
        // Policy: the privileged operation set must not contain any removal or overwrite primitive.
        val forbidden = listOf("rm ", "rm -", "dd ", "mkfs", "truncate", "wipefs", "mv -f", "cp -f")
        val ops = listOf(
            PrivilegedOp.Id, PrivilegedOp.ProbeTools,
            PrivilegedOp.CatNode(KernelNode.PROC_FILESYSTEMS), PrivilegedOp.ListNode(KernelNode.DEV_KVM),
            PrivilegedOp.ListDirNode(KernelNode.PROC_INIT_NS),
            PrivilegedOp.MkdirP("/data/adb/octaneui/images/x"), PrivilegedOp.DiskUsage("/data/adb/octaneui"),
        )
        ops.forEach { op -> forbidden.forEach { f -> assertTrue("${op.command()} contains '$f'", !op.command().contains(f)) } }
    }

    @Test fun readOnlyLookupsAreAllowListed() {
        assertEquals("getprop init.svc.zygote", PrivilegedOp.GetProp("init.svc.zygote").command())
        assertThrows(IllegalArgumentException::class.java) { PrivilegedOp.GetProp("persist.sys.usb.config; reboot") }
        assertEquals("pidof system_server", PrivilegedOp.Pidof("system_server").command())
        assertThrows(IllegalArgumentException::class.java) { PrivilegedOp.Pidof("init") }
    }

    @Test fun nodesAreWhitelisted() {
        assertEquals("cat /proc/filesystems", PrivilegedOp.CatNode(KernelNode.PROC_FILESYSTEMS).command())
    }
}
