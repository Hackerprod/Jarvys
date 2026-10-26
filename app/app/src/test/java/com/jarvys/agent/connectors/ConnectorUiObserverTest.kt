package com.jarvys.agent.connectors

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Scheduling/observer ownership only; UI/Registry integration is tested separately. */
class ConnectorUiObserverTest {
    @Test fun completionIsPostedToTheProvidedMainExecutor() {
        val posted = mutableListOf<Runnable>()
        val observer = ConnectorUiObserver(Executor { posted += it })
        val operation = CompletableFuture<String>()
        var observed: String? = null
        observer.observe(operation) { value, failed -> assertFalse(failed); observed = value }
        operation.complete("confirmed")
        assertNull(observed); assertEquals(1, posted.size)
        posted.single().run()
        assertEquals("confirmed", observed)
    }

    @Test fun disposingTheObserverDoesNotCancelAcceptedWorkOrInvokeItsOldScreen() {
        val posted = mutableListOf<Runnable>()
        val observer = ConnectorUiObserver(Executor { posted += it })
        val operation = CompletableFuture<String>()
        var callbacks = 0
        observer.observe(operation) { _, _ -> callbacks++ }
        observer.close()
        assertFalse(operation.isCancelled); assertFalse(operation.isDone)
        assertTrue(operation.complete("confirmed"))
        posted.forEach(Runnable::run)
        assertEquals(0, callbacks); assertEquals("confirmed", operation.get())
    }

    @Test fun recreatedScreenCanObserveTheSameWorkWithoutStartingItAgain() {
        val posted = mutableListOf<Runnable>()
        val main = Executor { posted += it }
        val old = ConnectorUiObserver(main)
        val fresh = ConnectorUiObserver(main)
        val operation = CompletableFuture<String>()
        var oldCalls = 0; var freshCalls = 0
        old.observe(operation) { _, _ -> oldCalls++ }; old.close()
        fresh.observe(operation) { _, failed -> assertFalse(failed); freshCalls++ }
        operation.complete("confirmed"); posted.forEach(Runnable::run)
        assertEquals(0, oldCalls); assertEquals(1, freshCalls)
    }

    @Test fun failuresExposeOnlyClassificationToTheUiCallback() {
        val observer = ConnectorUiObserver(Executor(Runnable::run))
        val operation = CompletableFuture<String>()
        var called = false
        observer.observe(operation) { value, failed -> assertNull(value); assertTrue(failed); called = true }
        operation.completeExceptionally(IllegalStateException("synthetic backend failure"))
        assertTrue(called)
    }

    @Test fun localSettingsWorkRunsOffTheCallerThread() {
        val caller = Thread.currentThread()
        assertTrue(ConnectorSettingsWork.submit { Thread.currentThread() !== caller }.get(10, TimeUnit.SECONDS))
    }
}
