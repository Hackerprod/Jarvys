package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration

/** Native bitmap decoding and explicitly synthetic syscall pipes. Robolectric does not implement
 * Os.poll/read/fcntlInt, so the syscall boundary is simulated; production worker loops and limits
 * run unchanged. This does not claim real-kernel pipe or real-camera validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryPhotoCaptureTest.PipeDescriptor::class, FactoryPhotoCaptureTest.PipeOs::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactoryPhotoCaptureTest {
    class PipeState {
        var bytes = ByteArray(0)
        var offset = 0
        @Volatile var eof = false
        @Volatile var readClosed = false
        @Volatile var reliableError = false
        @Volatile var pollEntered: java.util.concurrent.CountDownLatch? = null
        @Volatile var pollRelease: java.util.concurrent.CountDownLatch? = null
    }
    @org.robolectric.annotation.Implements(ParcelFileDescriptor::class)
    class PipeDescriptor {
        lateinit var state: PipeState
        lateinit var descriptor: java.io.FileDescriptor
        var reading = false
        @org.robolectric.annotation.Implementation fun getFileDescriptor() = descriptor
        @org.robolectric.annotation.Implementation fun close() { if (reading) state.readClosed = true else state.eof = true }
        @org.robolectric.annotation.Implementation fun checkError() { check(!state.reliableError) { "Synthetic reliable pipe error" } }
        companion object {
            val descriptors = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<java.io.FileDescriptor, PipeState>())
            @JvmStatic @org.robolectric.annotation.Implementation fun createReliablePipe(): Array<ParcelFileDescriptor> {
                val shared = PipeState()
                return Array(2) { index ->
                    org.robolectric.shadow.api.Shadow.newInstanceOf(ParcelFileDescriptor::class.java).also { pfd ->
                        val shadow = org.robolectric.shadow.api.Shadow.extract<PipeDescriptor>(pfd)
                        shadow.state = shared; shadow.reading = index == 0; shadow.descriptor = java.io.FileDescriptor()
                        descriptors[shadow.descriptor] = shared
                    }
                }
            }
            fun supply(pfd: ParcelFileDescriptor, bytes: ByteArray) {
                val state = org.robolectric.shadow.api.Shadow.extract<PipeDescriptor>(pfd).state
                synchronized(state) { state.bytes = bytes.copyOf(); state.offset = 0 }
            }
        }
    }
    @org.robolectric.annotation.Implements(android.system.Os::class)
    class PipeOs {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation fun fcntlInt(fd: java.io.FileDescriptor, cmd: Int, arg: Int) = 0
            @JvmStatic @org.robolectric.annotation.Implementation fun poll(fds: Array<android.system.StructPollfd>, timeout: Int): Int {
                val state = PipeDescriptor.descriptors[fds.single().fd] ?: error("Unknown synthetic descriptor")
                state.pollEntered?.countDown(); state.pollRelease?.let { check(it.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                synchronized(state) { if (state.offset < state.bytes.size || state.eof) return 1 }
                Thread.sleep(minOf(timeout.toLong(), 10L)); return 0
            }
            @JvmStatic @org.robolectric.annotation.Implementation fun read(fd: java.io.FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
                val state = PipeDescriptor.descriptors[fd] ?: error("Unknown synthetic descriptor")
                return synchronized(state) {
                    check(!state.readClosed)
                    val size = minOf(count, state.bytes.size - state.offset)
                    if (size == 0 && !state.eof) throw android.system.ErrnoException("read", android.system.OsConstants.EAGAIN)
                    state.bytes.copyInto(bytes, offset, state.offset, state.offset + size); state.offset += size; size
                }
            }
        }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var capture: FactoryPhotoCapture.Capture? = null
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private fun rejects(action: () -> Unit) { assertTrue("Expected fail-closed rejection", runCatching(action).isFailure) }
    @Before fun setup() { FactoryStartupTestIsolation.releaseCompletedSharingStartup() }
    @After fun cleanup() {
        descriptors.forEach { runCatching { it.close() } }
        capture?.let { FactoryPhotoCapture.cancel(it); if (it.opened) await(it) }
        PipeDescriptor.descriptors.clear()
    }
    private fun reserve(verify: () -> Boolean = { true }): FactoryPhotoCapture.Capture =
        FactoryPhotoCapture.reserve(context, "example.camera", Process.myUid(), verify).also { capture = it }
    private fun await(value: FactoryPhotoCapture.Capture) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!value.completed && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Bounded pipe worker did not terminate", value.completed)
    }
    private fun image(width: Int = 2, height: Int = 3, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 90, it)) }.toByteArray() } finally { bitmap.recycle() }
    }
    @Test fun exactUriUidModeAndFreshIdentityAreRequiredBeforePipeCreation() {
        var trusted = true; val value = reserve { trusted }
        for (mode in listOf("r", "rw", "rwt", "wa", "wt", "")) rejects { FactoryPhotoCapture.open(value.uri, mode, value.uid) }
        for (uri in listOf(value.uri.buildUpon().appendPath("child").build(), value.uri.buildUpon().query("x=1").build(), value.uri.buildUpon().fragment("x").build(), Uri.parse("file:///capture"))) {
            rejects { FactoryPhotoCapture.open(uri, "w", value.uid) }
        }
        rejects { FactoryPhotoCapture.open(value.uri, "w", value.uid + 1) }
        trusted = false; rejects { FactoryPhotoCapture.open(value.uri, "w", value.uid) }
        assertFalse(value.opened)
    }
    @Test fun reservationIsSingleFlightAndCancelledOrExpiredTokensNeverOpen() {
        val value = reserve(); rejects { reserve() }
        FactoryPhotoCapture.cancel(value); rejects { FactoryPhotoCapture.open(value.uri, "w", value.uid) }
        val next = reserve(); assertNotEquals(value.uri, next.uri)
        ShadowSystemClock.advanceBy(Duration.ofMillis(FactoryPhotoCapture.LIFETIME))
        rejects { FactoryPhotoCapture.open(next.uri, "w", next.uid) }
    }
    @Test fun oneOpenOnlyAndCancellationTerminatesAnIdleWriter() {
        val value = reserve()
        descriptors += FactoryPhotoCapture.open(value.uri, "w", value.uid)
        rejects { FactoryPhotoCapture.open(value.uri, "w", value.uid) }
        FactoryPhotoCapture.cancel(value); await(value)
        rejects { FactoryPhotoCapture.take(value) }
        reserve()
    }
    @Test fun cleanEofCannotCreateAnImageFromEmptyOrCorruptCameraBytes() {
        val value = reserve(); val write = FactoryPhotoCapture.open(value.uri, "w", value.uid)
        PipeDescriptor.supply(write, byteArrayOf(1, 2, 3)); write.close()
        await(value); assertTrue(value.failure); rejects { FactoryPhotoCapture.take(value) }
    }
    @Test fun cameraPipeProducesExactValidatedBytesOnlyAfterEofAndConsumesOnce() {
        val bytes = image(); val value = reserve()
        val write = FactoryPhotoCapture.open(value.uri, "w", value.uid)
        rejects { FactoryPhotoCapture.take(value) }
        PipeDescriptor.supply(write, bytes); write.close()
        await(value)
        val result = FactoryPhotoCapture.take(value)
        assertArrayEquals(bytes, result.bytes); assertEquals("image/png", result.mimeType)
        rejects { FactoryPhotoCapture.take(value) }
        rejects { FactoryPhotoCapture.open(value.uri, "w", value.uid) }
    }
    @Test fun oversizedCameraPipeTerminatesWithoutPublishingPartialBytes() {
        val value = reserve(); val write = FactoryPhotoCapture.open(value.uri, "w", value.uid)
        descriptors += write
        PipeDescriptor.supply(write, ByteArray(FactoryPhotoCapture.MAX_BYTES + 1)); write.close()
        await(value); assertTrue(value.failure); assertNull(value.image)
        rejects { FactoryPhotoCapture.take(value) }
    }
    @Test fun providerRejectsExportedWrongAuthorityAndMissingGrantConfiguration() {
        for (which in 0..2) {
            val info = ProviderInfo().apply { authority = FactoryPhotoCapture.authority(context); exported = false; grantUriPermissions = true }
            when (which) { 0 -> info.authority = "wrong"; 1 -> info.exported = true; 2 -> info.grantUriPermissions = false }
            rejects { FactoryPhotoProvider().attachInfo(context, info) }
        }
    }
    @Test fun providerDeniesReadsQueriesMutationsCallsAndCancelledOpen() {
        val value = reserve(); val provider = FactoryPhotoProvider()
        assertNull(provider.getType(value.uri))
        rejects { provider.openFile(value.uri, "r") }
        rejects { provider.query(value.uri, null, null, null, null) }
        rejects { provider.insert(value.uri, null) }; rejects { provider.update(value.uri, null, null, null) }
        rejects { provider.delete(value.uri, null, null) }; rejects { provider.call("anything", null, null) }
        rejects { provider.openFile(value.uri, "w", CancellationSignal().apply { cancel() }) }
        assertFalse(value.opened)
    }
    @Test fun jpegAndPngAreDecodedWithoutReencodingOrStrippingMetadata() {
        val png = image(); val jpeg = image(format = Bitmap.CompressFormat.JPEG)
        // Insert an ordinary JPEG COM metadata segment immediately after SOI.
        val comment = "synthetic metadata remains byte exact".toByteArray()
        val metadata = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (comment.size + 2).toByte()) + comment
        val withMetadata = jpeg.copyOfRange(0, 2) + metadata + jpeg.copyOfRange(2, jpeg.size)
        for ((bytes, mime) in listOf(png to "image/png", withMetadata to "image/jpeg")) {
            val decoded = FactoryPhotoCapture.validate(bytes)
            assertSame(bytes, decoded.bytes); assertArrayEquals(bytes, decoded.bytes)
            assertEquals(mime, decoded.mimeType); assertEquals(2, decoded.width); assertEquals(3, decoded.height)
        }
    }
    @Test fun byteDimensionPixelAndCompleteFormatLimitsFailClosed() {
        for (bytes in listOf(ByteArray(0), ByteArray(FactoryPhotoCapture.MAX_BYTES + 1), byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), image().dropLast(1).toByteArray(), image() + byteArrayOf(0))) {
            rejects { FactoryPhotoCapture.validate(bytes) }
        }
        rejects { FactoryPhotoCapture.validate(image(4097, 1)) }
        rejects { FactoryPhotoCapture.validate(image(1, 4097)) }
        rejects { FactoryPhotoCapture.validate(image(4000, 3001)) }
        assertEquals(4096, FactoryPhotoCapture.validate(image(4096, 1)).width)
    }
    @Test fun streamReaderBoundsActualGrowthAndRechecksForegroundAfterRead() {
        var consumed = 0
        val oversized = object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { consumed += length; return length }
        }
        rejects { FactoryPhotoCapture.read(oversized) {} }
        assertEquals(FactoryPhotoCapture.MAX_BYTES + 1, consumed)
        var calls = 0
        rejects { FactoryPhotoCapture.read(image().inputStream()) { check(++calls < 2) } }
        assertEquals(2, calls)
        rejects { FactoryPhotoCapture.read(object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, o: Int, n: Int) = 0 }) {} }
    }
    @Test fun pendingCameraKeepsAdmissionUntilWorkerFinishesThenReleasesExactlyOnce() {
        val pending = com.jarvys.factory.runtime.FileShareTransfer.reserve()
        val value = reserve(); descriptors += FactoryPhotoCapture.open(value.uri, "w", value.uid)
        val finished = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        FactoryPhotoCapture.afterCompletion(value) { calls.incrementAndGet(); pending.close(); finished.countDown() }
        assertEquals(0, calls.get())
        rejects { com.jarvys.factory.runtime.FileShareTransfer.reserve() }
        FactoryPhotoCapture.cancel(value)
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(value.completed); assertEquals(1, calls.get())
        com.jarvys.factory.runtime.FileShareTransfer.reserve().close()
    }
    @Test fun reliablePipeFailureNeverPublishesEvenValidImageBytes() {
        val value = reserve(); val write = FactoryPhotoCapture.open(value.uri, "w", value.uid)
        val state = org.robolectric.shadow.api.Shadow.extract<PipeDescriptor>(write).state
        state.reliableError = true; PipeDescriptor.supply(write, image()); write.close()
        await(value); assertTrue(value.failure); assertNull(value.image)
        rejects { FactoryPhotoCapture.take(value) }
    }
    @Test fun idleDescriptorReadStopsOnForegroundRevocationWithoutWaitingForEof() {
        val pipe = ParcelFileDescriptor.createReliablePipe(); descriptors.addAll(pipe)
        val foreground = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<FactoryPhotoCapture.Image> {
                FactoryPhotoCapture.readDescriptor(pipe[0], SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME) {
                    entered.countDown(); check(foreground.get()) { "Foreground revoked" }
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); foreground.set(false)
            try { result.get(5, java.util.concurrent.TimeUnit.SECONDS); fail("Revoked read succeeded") }
            catch (failure: java.util.concurrent.ExecutionException) { assertEquals("Foreground revoked", failure.cause?.message) }
        } finally { executor.shutdownNow() }
    }
    @Test fun idleCameraExpiresAndNeverMakesBytesAvailable() {
        val value = reserve(); descriptors += FactoryPhotoCapture.open(value.uri, "w", value.uid)
        ShadowSystemClock.advanceBy(Duration.ofMillis(FactoryPhotoCapture.LIFETIME))
        await(value); assertTrue(value.failure); rejects { FactoryPhotoCapture.take(value) }
    }

    @Test fun pickerDescriptorReadValidatesExactBytesAndExpiredDeadlineBeforeRead() {
        val bytes = image(); val pipe = ParcelFileDescriptor.createReliablePipe(); descriptors.addAll(pipe)
        PipeDescriptor.supply(pipe[1], bytes); pipe[1].close()
        var checks = 0
        val selected = FactoryPhotoCapture.readDescriptor(pipe[0], SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME) { checks++ }
        assertArrayEquals(bytes, selected.bytes); assertTrue(checks >= 3)
        rejects { FactoryPhotoCapture.readDescriptor(pipe[0], SystemClock.elapsedRealtime()) {} }
    }

    @Test fun pickerDescriptorRejectsByteLimitPlusOneWithoutReadingBeyondSentinel() {
        val pipe = ParcelFileDescriptor.createReliablePipe(); descriptors.addAll(pipe)
        val state = org.robolectric.shadow.api.Shadow.extract<PipeDescriptor>(pipe[1]).state
        PipeDescriptor.supply(pipe[1], ByteArray(FactoryPhotoCapture.MAX_BYTES + 100)); pipe[1].close()
        rejects { FactoryPhotoCapture.readDescriptor(pipe[0], SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME) {} }
        assertEquals(FactoryPhotoCapture.MAX_BYTES + 1, state.offset)
    }

}
