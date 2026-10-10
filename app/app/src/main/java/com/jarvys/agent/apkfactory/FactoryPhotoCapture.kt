package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** No filesystem output: one exact, single-open, write-only, bounded streaming capability. */
internal object FactoryPhotoCapture {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_PIXELS = 12_000_000L
    const val LIFETIME = 300_000L
    data class Image(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int)
    class Capture internal constructor(val uri: Uri, val uid: Int, val packageName: String, val deadline: Long,
                                       internal val verify: () -> Boolean) {
        internal var opened = false
        @Volatile internal var cancelled = false
        @Volatile internal var completed = false
        internal var image: Image? = null
        internal var failure = false
        internal var finished: (() -> Unit)? = null
        internal var expiry: java.util.concurrent.ScheduledFuture<*>? = null
    }
    private var active: Capture? = null
    private val expiry = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task -> Thread(task, "factory-camera-expiry").apply { isDaemon = true } }.apply { setRemoveOnCancelPolicy(true) }
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }
    fun authority(context: Context) = context.packageName + ".factory.camera"
    @Synchronized fun reserve(context: Context, packageName: String, uid: Int, verify: () -> Boolean): Capture {
        check(active == null) { "A camera stream is still active" }
        check(verify()) { "Camera identity changed" }
        val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath("capture")
            .appendPath(UUID.randomUUID().toString()).build()
        return Capture(uri, uid, packageName, SystemClock.elapsedRealtime() + LIFETIME, verify).also { capture ->
            active = capture
            try { capture.expiry = expiry.schedule({ cancel(capture) }, LIFETIME, TimeUnit.MILLISECONDS) }
            catch (error: RuntimeException) { active = null; throw error }
        }
    }
    @Synchronized fun open(uri: Uri, mode: String, caller: Int = Binder.getCallingUid()): ParcelFileDescriptor {
        val capture = active ?: error("No camera output")
        check(mode == "w" && uri == capture.uri && caller == capture.uid && !capture.opened && !capture.cancelled &&
            SystemClock.elapsedRealtime() < capture.deadline && capture.verify()) { "Camera output denied" }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        capture.opened = true
        try { worker.execute { receive(capture, pipe[0]) } }
        catch (error: Exception) { pipe.forEach { runCatching { it.close() } }; capture.failure = true; capture.completed = true; throw error }
        return pipe[1]
    }
    private fun receive(capture: Capture, read: ParcelFileDescriptor) {
        var bytes: ByteArray? = null
        try {
            val fd = read.fileDescriptor
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                check(!capture.cancelled && SystemClock.elapsedRealtime() < capture.deadline) { "Camera output expired" }
                val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), 100) == 0) continue
                val count = try { Os.read(fd, buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1)) }
                    catch (error: android.system.ErrnoException) { if (error.errno == OsConstants.EAGAIN) continue else throw error }
                if (count == 0) break
                check(count <= MAX_BYTES - output.size()) { "Camera output exceeds 8 MiB" }
                output.write(buffer, 0, count)
            }
            read.checkError()
            bytes = output.toByteArray()
            val image = validate(bytes)
            synchronized(this) {
                check(active === capture && !capture.cancelled && SystemClock.elapsedRealtime() < capture.deadline)
                capture.image = image; bytes = null
            }
        } catch (_: Exception) { synchronized(this) { capture.failure = true } }
        finally {
            bytes?.fill(0)
            runCatching { read.close() }
            val done = synchronized(this) {
                capture.completed = true; if (capture.cancelled && active === capture) active = null
                capture.finished.also { capture.finished = null }
            }
            done?.invoke()
        }
    }
    /** RESULT_OK is separately required by the Activity; this never equates clean EOF with consent. */
    @Synchronized fun take(capture: Capture): Image {
        check(active === capture && capture.completed && !capture.failure && !capture.cancelled && SystemClock.elapsedRealtime() < capture.deadline)
        val image = capture.image ?: error("Camera supplied no valid image")
        capture.image = null; capture.cancelled = true; capture.expiry?.cancel(false); active = null
        return image
    }
    @Synchronized fun cancel(capture: Capture?) {
        if (capture == null) return
        capture.cancelled = true; capture.expiry?.cancel(false); capture.image?.bytes?.fill(0); capture.image = null
        if ((!capture.opened || capture.completed) && active === capture) active = null
    }
    fun afterCompletion(capture: Capture?, action: () -> Unit) {
        val now = synchronized(this) {
            if (capture == null || !capture.opened || capture.completed) true
            else { check(capture.finished == null); capture.finished = action; false }
        }
        if (now) action()
    }
    fun readDescriptor(descriptor: ParcelFileDescriptor, deadline: Long, allowed: () -> Unit): Image {
        val fd = descriptor.fileDescriptor
        Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        val output = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024)
        while (true) {
            allowed(); check(SystemClock.elapsedRealtime() < deadline) { "Photo selection expired" }
            val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            if (Os.poll(arrayOf(poll), 100) == 0) continue
            val count = try { Os.read(fd, buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1)) }
                catch (error: android.system.ErrnoException) { if (error.errno == OsConstants.EAGAIN) continue else throw error }
            allowed()
            if (count == 0) break
            check(count <= MAX_BYTES - output.size()) { "Photo exceeds 8 MiB" }
            output.write(buffer, 0, count)
        }
        descriptor.checkError(); allowed()
        return validate(output.toByteArray())
    }
    fun read(input: InputStream, allowed: () -> Unit): Image {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024)
        while (true) {
            allowed()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
            allowed()
            if (count < 0) break
            check(count > 0 && count <= MAX_BYTES - output.size()) { "Photo exceeds 8 MiB" }
            output.write(buffer, 0, count)
        }
        return validate(output.toByteArray())
    }
    fun validate(bytes: ByteArray): Image {
        require(bytes.size in 1..MAX_BYTES)
        val jpeg = bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
        val png = bytes.size >= 20 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) &&
            bytes.copyOfRange(bytes.size - 12, bytes.size).contentEquals(byteArrayOf(0,0,0,0,73,69,78,68,174.toByte(),66,96,130.toByte()))
        require(jpeg || png) { "Only complete JPEG and PNG photos are supported" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS)
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Corrupt photo")
        try {
            require(decoded.width == bounds.outWidth && decoded.height == bounds.outHeight && decoded.allocationByteCount.toLong() <= MAX_PIXELS * 4)
        } finally { decoded.recycle() }
        return Image(bytes, if (jpeg) "image/jpeg" else "image/png", bounds.outWidth, bounds.outHeight)
    }
}
