package com.jarvys.agent.apkfactory

import android.os.Build
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records requested flags only; does not claim real Android FD or PFD.dup close-on-exec proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = android.app.Application::class, shadows = [FactoryFileShareOsShadow::class])
internal class FactoryFileShareCompatibilityTest : FactoryFileShareTestSupport() {
    @Test fun everyOpenUsesAtomicCloseOnExecAndNoFollowWithoutNewerApiField() {
        val snapshot = stage()
        assertArrayEquals(bytes, read(snapshot.uri))
        val flags = FactoryFileShareOsShadow.shareOpenFlags()
        assertEquals("staging, immutable inode pin, and provider read", 3, flags.size)
        val closeOnExec = FactoryFileShareStore.ATOMIC_CLOSE_ON_EXEC
        assertEquals(0x80000, closeOnExec)
        if (Build.VERSION.SDK_INT >= 27) assertEquals(OsConstants.O_CLOEXEC, closeOnExec)
        assertEquals(OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or closeOnExec, flags[0].toInt())
        assertEquals(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec, flags[1].toInt())
        assertEquals(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec, flags[2].toInt())
    }
}
