package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SafPickerAdmissionTest {
    private fun coordinator(): SafDocumentPickerCoordinator = SafDocumentPickerCoordinator::class.java
        .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    private fun pending(value: SafDocumentPickerCoordinator): SafPickerRequest {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < end) { value.pending.value?.let { return it }; Thread.sleep(2) }
        error("Picker request was not published")
    }
    @Test fun recreatedHostCannotLaunchTheSamePickerTwice() {
        val c = coordinator(); val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<android.net.Uri?> { c.pick(arrayOf("text/plain"), CancellationToken.uncancellable()) }
            val request = pending(c)
            assertTrue(c.claimLaunch(request.id)); assertFalse(c.claimLaunch(request.id))
            c.complete(request.id, null); assertNull(future.get(3, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
    @Test fun cancelledPickerDrainsOldResultBeforeNewRequest() {
        val c = coordinator(); val worker = Executors.newSingleThreadExecutor(); val token = CancellationToken.cancellable()
        try {
            val future = worker.submit<android.net.Uri?> { c.pick(arrayOf("text/plain"), token) }
            val old = pending(c); assertTrue(c.claimLaunch(old.id)); token.cancel()
            assertTrue(runCatching { future.get(3, TimeUnit.SECONDS) }.isFailure)
            assertTrue(runCatching { c.pick(arrayOf("text/plain"), CancellationToken.uncancellable()) }.isFailure)
            c.complete(old.id, null)
            val next = worker.submit<android.net.Uri?> { c.pick(arrayOf("text/plain"), CancellationToken.uncancellable()) }
            val current = pending(c); assertNotEquals(old.id, current.id); assertTrue(c.claimLaunch(current.id))
            c.complete(old.id, null); assertFalse(next.isDone)
            c.complete(current.id, null); assertNull(next.get(3, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
    @Test fun launchFailureTerminatesAndReleasesAdmission() {
        val c = coordinator(); val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<android.net.Uri?> { c.pick(arrayOf("text/plain"), CancellationToken.uncancellable()) }
            val request = pending(c); assertTrue(c.claimLaunch(request.id)); c.fail(request.id, IllegalStateException("Denied before launch"))
            assertTrue(runCatching { future.get(3, TimeUnit.SECONDS) }.isFailure)
            val next = worker.submit<android.net.Uri?> { c.pick(arrayOf("text/plain"), CancellationToken.uncancellable()) }
            val fresh = pending(c); assertTrue(c.claimLaunch(fresh.id)); c.complete(fresh.id, null)
            assertNull(next.get(3, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
}
