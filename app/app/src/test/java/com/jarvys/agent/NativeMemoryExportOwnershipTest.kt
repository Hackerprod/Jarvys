package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Test

class NativeMemoryExportOwnershipTest {
    @Test fun rapidSecondTapCannotOverwriteTheFirstJournalChoice() {
        val requests = MemoryExportRequests()
        val first = requireNotNull(requests.reserve(false))
        assertNull(requests.reserve(true))
        requests.launched(first)
        val result = requireNotNull(requests.claim())
        assertSame(first, result)
        assertFalse(result.includeJournal)
        assertNull(requests.claim())
        assertNull(requests.reserve(true))
        requests.complete(result)
        assertTrue(requireNotNull(requests.reserve(true)).includeJournal)
    }
    @Test fun cancelledLaunchedResultMustDrainBeforeFreshRequestCanOwnAPickerResult() {
        val requests = MemoryExportRequests()
        val old = requireNotNull(requests.reserve(true))
        requests.launched(old)
        requests.cancel(old)
        assertNull(requests.reserve(false))
        assertSame(old, requests.claim())
        assertTrue(old.cancelled)
        requests.complete(old)
        val next = requireNotNull(requests.reserve(false))
        assertNotSame(old, next)
        // A stale completion cannot release the newer reservation.
        requests.complete(old)
        assertNull(requests.reserve(true))
    }
    @Test fun cancelledPreparationReleasesReservationWithoutInventingPickerResult() {
        val requests = MemoryExportRequests()
        val old = requireNotNull(requests.reserve(true))
        requests.cancel(old)
        assertNull(requests.claim())
        assertNotNull(requests.reserve(false))
    }
}
