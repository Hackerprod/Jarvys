package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.StructStat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** API compatibility contracts at an explicit synthetic syscall boundary. No bitmap decoding,
 * real kernel pipes, device camera, selected media, or real provider access is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 30], application = Application::class,
    shadows = [FactoryPhotoCaptureTest.PipeDescriptor::class, FactoryPhotoCompatibilityTest.CompatibilityOs::class])
class FactoryPhotoCompatibilityTest {
    data class FcntlCall(val descriptor: FileDescriptor, val command: Int, val argument: Int)
    @Implements(Os::class)
    class CompatibilityOs {
        companion object {
            val fcntlCalls = java.util.Collections.synchronizedList(mutableListOf<FcntlCall>())
            val statCalls = java.util.Collections.synchronizedList(mutableListOf<FileDescriptor>())
            val readCalls = AtomicInteger()
            var existingFlags = 0
            var mode = 0
            var statFailure = false
            @Volatile var pollEvents = 0
            @Volatile var entered = CountDownLatch(1)
            @JvmStatic @Implementation fun fcntlInt(fd: FileDescriptor, command: Int, argument: Int): Int {
                check(Build.VERSION.SDK_INT >= 30) { "Public fcntlInt called below API30" }
                fcntlCalls += FcntlCall(fd, command, argument)
                return when (command) {
                    OsConstants.F_GETFL -> existingFlags
                    OsConstants.F_SETFL -> 0
                    else -> error("Unexpected fcntl command")
                }
            }
            @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat {
                statCalls += fd
                if (statFailure) throw ErrnoException("fstat", OsConstants.EBADF)
                return StructStat(0, 0, mode, 1, 0, 0, 0, 0, 0, 0, 0, 4096, 0)
            }
            @JvmStatic @Implementation fun poll(fds: Array<StructPollfd>, timeout: Int): Int {
                entered.countDown()
                if (pollEvents != 0) { fds.single().revents = pollEvents.toShort(); return 1 }
                return FactoryPhotoCaptureTest.PipeOs.poll(fds, timeout)
            }
            @JvmStatic @Implementation fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
                readCalls.incrementAndGet()
                return FactoryPhotoCaptureTest.PipeOs.read(fd, bytes, offset, count)
            }
        }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private var capture: FactoryPhotoCapture.Capture? = null
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        CompatibilityOs.fcntlCalls.clear(); CompatibilityOs.statCalls.clear(); CompatibilityOs.readCalls.set(0)
        CompatibilityOs.existingFlags = OsConstants.O_APPEND or OsConstants.O_RDWR
        CompatibilityOs.mode = OsConstants.S_IFREG or 0x180
        CompatibilityOs.statFailure = false; CompatibilityOs.pollEvents = 0; CompatibilityOs.entered = CountDownLatch(1)
    }
    @After fun cleanup() {
        capture?.let { FactoryPhotoCapture.cancel(it); if (it.opened) awaitCameraWorker() }
        descriptors.forEach { runCatching { it.close() } }
        FactoryPhotoCaptureTest.PipeDescriptor.descriptors.clear()
    }
    private fun pipe() = ParcelFileDescriptor.createReliablePipe().also { descriptors.addAll(it) }
    private fun awaitCameraWorker() {
        val executor = FactoryPhotoCapture::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(null) as ExecutorService
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var completed = false
        while (!completed && System.nanoTime() < deadline) {
            try { executor.submit {}.get(5, TimeUnit.SECONDS); completed = true }
            catch (_: java.util.concurrent.RejectedExecutionException) { Thread.sleep(5) }
        }
        assertTrue("Synthetic camera worker did not terminate", completed)
    }
    @Test @Config(sdk = [24, 29]) fun olderPrivatePipeUsesNeitherPublicFcntlNorForeignDescriptorMetadata() {
        CompatibilityOs.statFailure = true
        FactoryPhotoCapture.prepareDescriptor(FileDescriptor(), privatePipe = true)
        assertTrue(CompatibilityOs.fcntlCalls.isEmpty()); assertTrue(CompatibilityOs.statCalls.isEmpty())
    }
    @Test @Config(sdk = [24, 29]) fun olderRegularProviderIsAllowedWithoutCallingUnavailableFcntl() {
        val fd = FileDescriptor()
        FactoryPhotoCapture.prepareDescriptor(fd, privatePipe = false)
        assertEquals(listOf(fd), CompatibilityOs.statCalls); assertTrue(CompatibilityOs.fcntlCalls.isEmpty())
    }
    @Test @Config(sdk = [24, 29]) fun olderNonRegularOrUninspectableProviderIsRejected() {
        for (mode in listOf(OsConstants.S_IFIFO, OsConstants.S_IFSOCK, OsConstants.S_IFCHR, OsConstants.S_IFBLK, OsConstants.S_IFDIR, OsConstants.S_IFLNK)) {
            CompatibilityOs.mode = mode
            assertTrue("Non-regular provider must fail closed", runCatching { FactoryPhotoCapture.prepareDescriptor(FileDescriptor(), false) }.isFailure)
        }
        CompatibilityOs.statFailure = true
        assertTrue(runCatching { FactoryPhotoCapture.prepareDescriptor(FileDescriptor(), false) }.exceptionOrNull() is ErrnoException)
        assertTrue(CompatibilityOs.fcntlCalls.isEmpty())
    }
    @Test @Config(sdk = [30]) fun modernProviderAndPrivatePipePreserveExistingFlagsWhileAddingNonblock() {
        CompatibilityOs.statFailure = true
        for (privatePipe in listOf(false, true)) {
            CompatibilityOs.fcntlCalls.clear()
            val fd = FileDescriptor(); FactoryPhotoCapture.prepareDescriptor(fd, privatePipe)
            assertEquals(listOf(FcntlCall(fd, OsConstants.F_GETFL, 0),
                FcntlCall(fd, OsConstants.F_SETFL, CompatibilityOs.existingFlags or OsConstants.O_NONBLOCK)), CompatibilityOs.fcntlCalls)
        }
        assertTrue(CompatibilityOs.statCalls.isEmpty())
    }
    @Test fun idlePrivateCameraCanBeCancelledOnEverySupportedBranch() {
        val value = FactoryPhotoCapture.reserve(context, "example.synthetic.camera", Process.myUid()) { true }.also { capture = it }
        descriptors += FactoryPhotoCapture.open(value.uri, "w", value.uid)
        assertTrue(CompatibilityOs.entered.await(5, TimeUnit.SECONDS)); assertFalse(value.completed)
        FactoryPhotoCapture.cancel(value); awaitCameraWorker()
        assertTrue(value.completed); assertTrue(value.cancelled); assertNull(value.image)
        assertTrue(runCatching { FactoryPhotoCapture.take(value) }.isFailure)
        assertTrue(CompatibilityOs.statCalls.isEmpty())
        if (Build.VERSION.SDK_INT < 30) assertTrue(CompatibilityOs.fcntlCalls.isEmpty()) else assertEquals(2, CompatibilityOs.fcntlCalls.size)
    }
    @Test fun providerPollErrorsAndInvalidDescriptorsRejectBeforeAnyRead() {
        val pipe = pipe()
        for (flags in listOf(OsConstants.POLLERR, OsConstants.POLLNVAL, OsConstants.POLLHUP or OsConstants.POLLERR)) {
            CompatibilityOs.pollEvents = flags
            val failure = runCatching { FactoryPhotoCapture.readDescriptor(pipe[0], SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME) {} }.exceptionOrNull()
            assertEquals("Photo descriptor failed", failure?.message)
            assertEquals(0, CompatibilityOs.readCalls.get())
        }
    }
    @Test fun cameraPollErrorFailsBeforeReadAndNeverPublishesBytes() {
        CompatibilityOs.pollEvents = OsConstants.POLLNVAL
        val value = FactoryPhotoCapture.reserve(context, "example.synthetic.camera", Process.myUid()) { true }.also { capture = it }
        descriptors += FactoryPhotoCapture.open(value.uri, "w", value.uid)
        awaitCameraWorker()
        assertTrue(value.completed); assertTrue(value.failure); assertNull(value.image)
        assertEquals(0, CompatibilityOs.readCalls.get()); assertTrue(runCatching { FactoryPhotoCapture.take(value) }.isFailure)
    }
    @Test fun cleanHangupRemainsReadableAsEofRatherThanDescriptorError() {
        val pipe = pipe(); pipe[1].close(); CompatibilityOs.pollEvents = OsConstants.POLLHUP
        val failure = runCatching {
            FactoryPhotoCapture.readDescriptor(pipe[0], SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME) {
                if (CompatibilityOs.readCalls.get() > 0) error("Observed clean EOF before decoding")
            }
        }.exceptionOrNull()
        assertEquals("Observed clean EOF before decoding", failure?.message); assertEquals(1, CompatibilityOs.readCalls.get())
    }
}
