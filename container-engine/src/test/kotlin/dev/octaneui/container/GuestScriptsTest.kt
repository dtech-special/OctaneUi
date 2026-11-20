package dev.octaneui.container

import dev.octaneui.core.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestScriptsTest {
    private val root = "/data/user/0/dev.octaneui.app/files/octaneui/images/redroid-16/rootfs"
    private val run = "/data/user/0/dev.octaneui.app/files/octaneui/run/shared"

    private fun noPlaceholders(s: String) = assertFalse("placeholders must be replaced", s.contains("@ROOT@") || s.contains("@RUN@") || s.contains("@PREV@") || s.contains('§'))

    @Test fun startScriptHasSingleInstanceGuardAndEntersNamespace() {
        val s = GuestScripts.start(root, run, "-m", "shared_pid")
        noPlaceholders(s)
        assertTrue(s.startsWith("#!/system/bin/sh"))
        assertTrue(s.contains("unshare -m --"))
        assertTrue("duplicate guard must exist", s.contains("not starting a second one"))
        assertTrue(s.contains("enter.sh"))
        assertFalse("Android init is not started", s.contains("/init"))
    }

    @Test fun noScriptContainsDeletionOrOverwritePrimitives() {
        val all = listOf(
            GuestScripts.start(root, run, "-m", "shared_pid", "$run-prev"),
            GuestScripts.enter(root, run), GuestScripts.stop(root, run), GuestScripts.status(root, run),
        )
        val forbidden = listOf("rm ", "rm -", "dd ", "mkfs", "mke2fs", "truncate", "wipefs", "cp -f", "mv -f", "shutil")
        all.forEach { s -> forbidden.forEach { f -> assertFalse("script contains '$f'", s.contains(f)) } }
    }

    @Test fun startScriptRepeatsPreflightBeforeAnyWork() {
        val s = GuestScripts.start(root, run, "-m", "shared_pid")
        val unshare = s.indexOf("exec unshare")
        listOf("/sys/fs/selinux/enforce", "spblob", ".pwd", "/proc/self/mountinfo").forEach { m ->
            assertTrue("level 3 must check $m", s.indexOf(m) in 0 until unshare)
        }
        assertTrue(s.contains("exit 31") && s.contains("exit 32") && s.contains("exit 33") && s.contains("exit 34"))
    }

    @Test fun startScriptUsesNoclobber() {
        assertTrue(GuestScripts.start(root, run, "-m", "shared_pid").contains("set -C"))
    }

    @Test fun stopMarksSessionInsteadOfDeletingState() {
        val s = GuestScripts.stop(root, run)
        assertTrue(s.contains("echo stopped >") && s.contains("/stopped"))
        // a stopped session reports zero processes
        assertTrue(GuestScripts.status(root, run).contains("/stopped"))
    }

    @Test fun nativeFlagsAccepted() {
        assertTrue(GuestScripts.start(root, run, "-m -p -f -i -u -n", "native").contains("unshare -m -p -f -i -u -n --"))
    }

    @Test fun enterMakesMountsPrivateBeforeAnyBindAndNeverRelyOnFstab() {
        val s = GuestScripts.enter(root, run)
        noPlaceholders(s)
        val firstBind = s.indexOf("mount -o rbind")
        // all attempts to make propagation private come before the first bind mount
        listOf("mount --make-rprivate /", "mount -o rprivate none /").forEach { cmd ->
            assertTrue("missing variant $cmd", s.contains(cmd))
            assertTrue("$cmd must come before the first bind", s.indexOf(cmd) in 0 until firstBind)
        }
        // the result is verified through mountinfo, not only by the return code
        assertTrue(s.contains("! grep -q ' shared:' /proc/self/mountinfo"))
        assertTrue("an unreadable mountinfo must not count as private", s.contains("[ -r /proc/self/mountinfo ]"))
        // refusal before any mount on failure
        assertTrue(s.indexOf("exit 21") in 0 until firstBind)
        // isolation check against the host mount namespace
        assertTrue(s.contains("/proc/1/mountinfo"))
        // the real mount output goes to the log
        assertTrue(s.contains("echo \"[octaneui] mount: "))
        assertFalse(s.contains("/init"))
    }

    @Test fun rejectsInjection() {
        assertThrows(IllegalArgumentException::class.java) { GuestScripts.start("$root; rm -rf /", run, "-m", "x") }
        assertThrows(IllegalArgumentException::class.java) { GuestScripts.start(root, run, "-m; reboot", "x") }
        assertThrows(IllegalArgumentException::class.java) { GuestScripts.stop("/x y", run) }
        assertThrows(IllegalArgumentException::class.java) { GuestScripts.enter(root, "$run'") }
    }

    @Test fun parsesStatus() {
        val running = GuestScripts.parseStatus("uid=0\nprocs=1\npid=4321\nstarted=100\nnow=160\n")
        assertTrue(running.running); assertEquals(4321, running.pid); assertEquals(60L, running.uptimeSec)
        val stopped = GuestScripts.parseStatus("uid=0\nprocs=0\npid=\nnow=160\n")
        assertFalse(stopped.running); assertEquals(null, stopped.uptimeSec)
    }

    @Test fun parsesStopAndLeaks() {
        assertEquals(GuestScripts.StopResult(true, 0), GuestScripts.parseStop("stopped=ok\nleaked_mounts=0\n"))
        assertEquals(GuestScripts.StopResult(true, 3), GuestScripts.parseStop("stopped=none\nleaked_mounts=3\n"))
        assertFalse(GuestScripts.parseStop("garbage").stopped)
    }

    @Test fun guestLogLevels() {
        val parsed = GuestLogParser.parse(listOf(
            "[octaneui] step: propagation: trying mount --make-rprivate /",
            "[octaneui] mount: mount: bad /etc/fstab: No such file or directory",
            "[octaneui] warn: mount --make-rprivate / failed (rc=1)",
            "[octaneui] error: cannot make mounts private",
            "chroot: exec failed",
            "",
        ))
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.WARN, LogLevel.ERROR, LogLevel.WARN), parsed.map { it.level })
        assertEquals("mount: bad /etc/fstab: No such file or directory", parsed[1].text.removePrefix("mount: "))
    }
}
