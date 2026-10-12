package com.jarvys.agent

import com.jarvys.agent.connectors.AutonomyPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeMemoryViewGuardTest {
    @Test fun selectedViewRevocationInvalidatesCurrentAndQueuedAutomationWithoutGlobalGrant() {
        val policy = NativeActionPolicy.inMemory()
        val request = policy.prepare(NativeActionPolicy.Operation.MEMORY_VIEW, NativeActionPolicy.binding("selected scope", "exact content"))
        MemoryUiAutomationGuard.registerViewAuthority { runCatching { request.admit(); true }.getOrDefault(false) }.use {
            val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
            assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { })
            policy.set(NativeActionPolicy.Operation.MEMORY_VIEW, AutonomyPolicy.DENY)
            assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
            assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { fail("Revoked view") })
            assertThrows(IllegalStateException::class.java) { MemoryUiAutomationGuard.captureAutomationEpoch() }
            assertEquals(1L, MemoryUiAutomationGuard.localSnapshot()[1])
        }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        request.close()
    }
    @Test fun authorityIsEvaluatedOutsideMonitorAndConcurrentTransitionFailsClosed() {
        val executor = Executors.newSingleThreadExecutor()
        val mutateOnce = AtomicBoolean(true)
        try {
            MemoryUiAutomationGuard.registerViewAuthority {
                if (mutateOnce.compareAndSet(true, false)) {
                    // This would time out if validation held the guard MONITOR.
                    executor.submit { MemoryUiAutomationGuard.invalidateQueuedAutomation() }.get(5, TimeUnit.SECONDS)
                }
                true
            }.use {
                assertThrows(IllegalStateException::class.java) { MemoryUiAutomationGuard.captureAutomationEpoch() }
                val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
                assertTrue(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
                MemoryUiAutomationGuard.enterViewTransition().use { transition ->
                    assertThrows(IllegalStateException::class.java) { transition.requireHumanUiInteraction() }
                    assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
                    assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { fail("Transition pixels") })
                }
                assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun sourceSelectionInvalidatedDuringSnapshotValidationCannotProduceUnprotectedProof() {
        MemoryUiAutomationGuard.registerViewAuthority { MemoryUiAutomationGuard.invalidateQueuedAutomation(); true }.use {
            assertEquals(1L, MemoryUiAutomationGuard.localSnapshot()[1])
        }
    }
}
