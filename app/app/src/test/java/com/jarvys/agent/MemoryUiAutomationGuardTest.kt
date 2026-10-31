package com.jarvys.agent

import com.artemis.helper.GestureController
import com.artemis.helper.HierarchyDumper
import com.jarvys.agent.device.AccessibilityDriver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryUiAutomationGuardTest {
    @Before fun isolateFactoryStartup() { com.jarvys.agent.apkfactory.FactoryStartupTestIsolation.releaseCompletedSharingStartup() }
    private fun blocked(action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected protected-memory denial, got $error", error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("User-only memory"))
    }

    @Test fun ordinaryAutomationWorksBeforeAndAfterManualProtectedSurface() {
        val before = MemoryUiAutomationGuard.captureAutomationEpoch()
        var actions = 0
        assertTrue(MemoryUiAutomationGuard.runAutomated(before) { actions++ })
        val lease = MemoryUiAutomationGuard.enterProtectedSurface()
        try {
            assertTrue(MemoryUiAutomationGuard.isProtected())
            assertTrue(lease.isReadyForUser)
            lease.requireHumanUiInteraction()
            blocked { MemoryUiAutomationGuard.captureAutomationEpoch() }
            assertFalse(MemoryUiAutomationGuard.runAutomated(before) { actions++ })
        } finally { lease.close() }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(before))
        assertTrue(MemoryUiAutomationGuard.runAutomated(MemoryUiAutomationGuard.captureAutomationEpoch()) { actions++ })
        assertEquals(2, actions)
    }

    @Test fun captureSpanningOpenAndCloseIsRejectedEvenWhenCurrentScreenIsOrdinary() {
        val screenshotEpoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        MemoryUiAutomationGuard.enterProtectedSurface().use { assertTrue(it.isReadyForUser) }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        blocked { MemoryUiAutomationGuard.requireAutomationEpoch(screenshotEpoch) }
        assertFalse(MemoryUiAutomationGuard.runAutomated(screenshotEpoch) { error("Stale queued input executed") })
    }

    @Test fun nestedProtectionAndDuplicateCloseNeverOpenAnOuterSurface() {
        val outer = MemoryUiAutomationGuard.enterProtectedSurface()
        val inner = MemoryUiAutomationGuard.enterProtectedSurface()
        try {
            assertTrue(outer.isReadyForUser)
            assertTrue(inner.isReadyForUser)
            inner.close(); inner.close()
            assertTrue(MemoryUiAutomationGuard.isProtected())
            assertTrue(outer.isReadyForUser)
            blocked { inner.requireHumanUiInteraction() }
            blocked { MemoryUiAutomationGuard.captureAutomationEpoch() }
        } finally { inner.close(); outer.close(); outer.close() }
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun synchronousNodeOrImeActionEnteringSurfaceCannotBecomeNativeConsent() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var native: MemoryUiAutomationGuard.Lease
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) {
            native = MemoryUiAutomationGuard.enterProtectedSurface()
            assertFalse(native.isReadyForUser)
        })
        try {
            assertFalse(native.isReadyForUser)
            blocked { native.requireHumanUiInteraction() }
            MemoryUiAutomationGuard.enterProtectedSurface().use { nested ->
                assertFalse(nested.isReadyForUser)
                blocked { nested.requireHumanUiInteraction() }
            }
        } finally { native.close() }
        MemoryUiAutomationGuard.enterProtectedSurface().use { assertTrue(it.isReadyForUser) }
    }

    @Test fun dispatchedGestureRemainsInFlightAfterWaiterTimeoutAndSurfaceStaysTaintedAfterCallback() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var gesture: MemoryUiAutomationGuard.Action
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) {
            gesture = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        })
        // The tool's wait may time out, but it does not own the terminal callback lease.
        val native = MemoryUiAutomationGuard.enterProtectedSurface()
        try {
            assertFalse(native.isReadyForUser)
            blocked { native.requireHumanUiInteraction() }
            gesture.close(); gesture.close()
            assertFalse(native.isReadyForUser)
            blocked { native.requireHumanUiInteraction() }
        } finally { gesture.close(); native.close() }
        MemoryUiAutomationGuard.enterProtectedSurface().use { assertTrue(it.isReadyForUser) }
    }

    @Test fun delayedNavigationAfterTerminalCallbackStillRejectsEveryRemainingQueuedTapAndObservation() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        val dispatched = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        dispatched.close()
        val native = MemoryUiAutomationGuard.enterProtectedSurface()
        try {
            // Navigation was delivered after the prior action ended. New native review is still empty of consent.
            assertTrue(native.isReadyForUser)
            assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { error("Queued second tap executed") })
            blocked { MemoryUiAutomationGuard.captureAutomationEpoch() }
            blocked { MemoryUiAutomationGuard.requireAutomationEpoch(epoch) }
        } finally { native.close() }
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { error("Old repeated tap survived closing review") })
    }

    @Test fun nativeEntryDuringSynchronousBinderActionDoesNotDeadlockAndCannotBecomeConsent() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        val started = CountDownLatch(1)
        val nativeEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val dispatch = executor.submit<Boolean> {
                MemoryUiAutomationGuard.runAutomated(epoch) {
                    started.countDown()
                    assertTrue("Native entry must not deadlock on Binder action", nativeEntered.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val protection = executor.submit<MemoryUiAutomationGuard.Lease> {
                MemoryUiAutomationGuard.enterProtectedSurface().also { nativeEntered.countDown() }
            }
            protection.get(3, TimeUnit.SECONDS).use { lease ->
                assertTrue(dispatch.get(3, TimeUnit.SECONDS))
                assertFalse(lease.isReadyForUser)
                blocked { lease.requireHumanUiInteraction() }
                assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { error("Queued dispatch executed") })
            }
        } finally { nativeEntered.countDown(); executor.shutdownNow() }
    }

    @Test fun helperHttpCaptureAndActionChokePointsRejectBeforeAccessingService() {
        MemoryUiAutomationGuard.enterProtectedSurface().use {
            blocked { HierarchyDumper.dump(null) }
            blocked { HierarchyDumper.dumpXml(null) }
            blocked { HierarchyDumper.dumpAtomicSnapshot(null) }
            blocked { HierarchyDumper.captureRootSnapshots(null) }
            blocked { HierarchyDumper.findInputNode(null) }
            blocked { GestureController.tap(null, 1f, 1f, 1L) }
            blocked { GestureController.doubleTap(null, 1f, 1f, 1L) }
            blocked { GestureController.longPress(null, 1f, 1f, 1L, 1L) }
            blocked { GestureController.swipe(null, 1f, 1f, 2f, 2f, 1L, 1L) }
            blocked { GestureController.setText(null, "synthetic") }
            blocked { GestureController.clearText(null) }
            blocked { GestureController.setClipboard(null, "synthetic") }
            blocked { GestureController.performGlobalAction(null, "back") }
        }
    }

    @Test fun directDriverNodeImeDeleteGlobalAndLaunchRoutesRejectBeforeServiceAccess() {
        val driver = AccessibilityDriver()
        val token = CancellationToken.uncancellable()
        MemoryUiAutomationGuard.enterProtectedSurface().use {
            blocked { driver.inputText("synthetic", true, token) }
            blocked { driver.pressKey("enter", token) }
            blocked { driver.pressKey("delete", token) }
            blocked { driver.pressKey("back", token) }
            blocked { driver.launchApp("synthetic.package", token) }
            blocked { driver.openLink("https://example.invalid/synthetic", token) }
            blocked { driver.getHierarchyXml() }
            blocked { driver.tap(1, 1, 1, 2, 0, token) }
            blocked { driver.longPress(1, 1, 1, token) }
            blocked { driver.swipe(1, 1, 2, 2, 1, token) }
        }
    }
}
