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

/** Native bitmap decoding and synthetic one-use pipes only; never opens real media or a camera. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactoryPhotoCaptureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var capture: FactoryPhotoCapture.Capture? = null
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private fun rejects(action: () -> Unit) { assertTrue("Expected fail-closed rejection", runCatching(action).isFailure) }
    @Before fun setup() { FactoryStartupTestIsolation.releaseCompletedSharingStartup() }
    @After fun cleanup() {
        descriptors.forEach { runCatching { it.close() } }
        capture?.let { FactoryPhotoCapture.cancel(it); if (it.opened) await(it) }
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
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(byteArrayOf(1, 2, 3)) }
        await(value); assertTrue(value.failure); rejects { FactoryPhotoCapture.take(value) }
    }
    @Test fun cameraPipeProducesExactValidatedBytesOnlyAfterEofAndConsumesOnce() {
        val bytes = image(); val value = reserve()
        val write = FactoryPhotoCapture.open(value.uri, "w", value.uid)
        rejects { FactoryPhotoCapture.take(value) }
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
        await(value)
        val result = FactoryPhotoCapture.take(value)
        assertArrayEquals(bytes, result.bytes); assertEquals("image/png", result.mimeType)
        rejects { FactoryPhotoCapture.take(value) }
        rejects { FactoryPhotoCapture.open(value.uri, "w", value.uid) }
    }
    @Test fun oversizedCameraPipeTerminatesWithoutPublishingPartialBytes() {
        val value = reserve(); val write = FactoryPhotoCapture.open(value.uri, "w", value.uid)
        descriptors += write
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task = writer.submit { runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    val chunk = ByteArray(32 * 1024)
                    repeat(FactoryPhotoCapture.MAX_BYTES / chunk.size + 1) { output.write(chunk) }
                }
            }; Unit }
            task.get(10, java.util.concurrent.TimeUnit.SECONDS)
            await(value); assertTrue(value.failure); assertNull(value.image)
            rejects { FactoryPhotoCapture.take(value) }
        } finally { runCatching { write.close() }; writer.shutdownNow() }
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
}
