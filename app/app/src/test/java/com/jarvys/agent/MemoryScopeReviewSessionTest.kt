package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryScopeReviewSessionTest {
    @Test fun exactContentMustBeExplicitlyAcknowledgedAndApprovalCanBeConsumedOnlyOnce() {
        val store = localStore()
        val review = store.reviewForSharing("note.md")
        val ui = MemoryScopeReviewSession()
        assertTrue(ui.present(ui.beginReview(), store, review))
        assertNull(ui.takeApproved())
        assertSame(review, ui.pending?.review)
        ui.acknowledge(true)
        val approved = ui.takeApproved()!!
        assertSame(review, approved.review)
        assertSame(store, approved.source)
        assertNull(ui.takeApproved())
        assertFalse(ui.acknowledged)
        assertNull(ui.pending)
        assertTrue(store.listSharedPersonalForUser().isEmpty())
    }

    @Test fun cancelClearsConsentAndRejectsLateRead() {
        val store = localStore()
        val ui = MemoryScopeReviewSession()
        val ticket = ui.beginReview()
        ui.cancel()
        assertFalse(ui.present(ticket, store, store.reviewForSharing("note.md")))
        ui.acknowledge(true)
        assertFalse(ui.acknowledged)
        assertNull(ui.takeApproved())
        assertTrue(store.listSharedPersonalForUser().isEmpty())
    }

    @Test fun sourceNavigationInvalidatesPriorReviewAndAcknowledgment() {
        val store = localStore()
        val ui = MemoryScopeReviewSession()
        val oldTicket = ui.beginReview()
        val review = store.reviewForSharing("note.md")
        ui.present(oldTicket, store, review)
        ui.acknowledge(true)
        val currentTicket = ui.beginReview()
        assertFalse(ui.present(oldTicket, store, review))
        assertTrue(ui.present(currentTicket, store, review))
        assertFalse(ui.acknowledged)
        assertNull(ui.takeApproved())
    }

    @Test fun closeAndLifecycleInterruptionDiscardConsentAndLateResults() {
        val store = localStore()
        val ui = MemoryScopeReviewSession()
        val ticket = ui.beginReview()
        val review = store.reviewForSharing("note.md")
        ui.present(ticket, store, review)
        ui.acknowledge(true)
        ui.close()
        assertNull(ui.takeApproved())
        assertFalse(ui.present(ticket, store, review))
        ui.acknowledge(true)
        assertFalse(ui.acknowledged)
        assertTrue(store.listSharedPersonalForUser().isEmpty())
    }

    @Test fun freshSessionNeverRestoresApprovalAfterProcessRecreation() {
        val store = localStore()
        val oldUi = MemoryScopeReviewSession()
        oldUi.present(oldUi.beginReview(), store, store.reviewForSharing("note.md"))
        oldUi.acknowledge(true)
        oldUi.close()
        val newUi = MemoryScopeReviewSession()
        assertNull(newUi.takeApproved())
        assertNull(newUi.pending)
        assertFalse(newUi.acknowledged)
    }

    @Test fun failedLoadInvalidatesTicketWithoutCreatingApproval() {
        val store = localStore()
        val ui = MemoryScopeReviewSession()
        val ticket = ui.beginReview()
        assertTrue(ui.failed(ticket))
        assertFalse(ui.failed(ticket))
        assertFalse(ui.present(ticket, store, store.reviewForSharing("note.md")))
        assertNull(ui.takeApproved())
    }

    @Test fun reviewedVersionChangedBeforeNativeApprovalIsRefused() {
        val store = localStore()
        val ui = MemoryScopeReviewSession()
        ui.present(ui.beginReview(), store, store.reviewForSharing("note.md"))
        ui.acknowledge(true)
        store.writeUserFile("note.md", document("Changed after review"), store.latestRevisionId("note.md"), false, SESSION)
        val pending = ui.takeApproved()!!
        assertTrue(runCatching { pending.source.approveSharedPersonal(pending.review) }.isFailure)
        assertNull(ui.takeApproved())
        assertTrue(store.listSharedPersonalForUser().none { it.active })
    }

    private fun localStore(): MemoryStore = MemoryStore(
        Files.createTempDirectory("memory-scope-review-state").toFile(), true,
        testMemorySeedProvider(AppLanguageChoice.ENGLISH),
    ).forConversation(SESSION).also {
        it.ensureInitialized()
        it.writeUserFile("note.md", document("An exact local fact"), 0L, false, SESSION)
    }

    private fun document(body: String) = "---\nname: Note\ndescription: A test note\n---\n$body\n"
    companion object { private const val SESSION = "scope-review-state" }
}
