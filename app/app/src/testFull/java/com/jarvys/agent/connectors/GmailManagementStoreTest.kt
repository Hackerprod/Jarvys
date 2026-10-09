package com.jarvys.agent.connectors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Checkpoint contracts use only in-memory persistence, with independent durable and visible values. */
class GmailManagementStoreTest {
    private class Backing(initial: String? = null) {
        var durable: String? = initial
        var visible: String? = initial
        var writes = 0
        var failure: IOException? = null
        var changeMemoryBeforeFailure = false

        fun wrapper(): GmailManagementPersistence = object : GmailManagementPersistence {
            override val identity: Any = this@Backing
            override fun read(): String? = visible
            override fun write(value: String) {
                writes++
                failure?.let {
                    if (changeMemoryBeforeFailure) visible = value
                    throw it
                }
                visible = value
                durable = value
            }
        }

        fun store() = JsonGmailManagementStore(wrapper())
    }

    private fun id(index: Int, kind: String = "receipt") = "$kind:${UUID(0L, index.toLong())}"

    private fun receipt(index: Int, createdAt: Long = index.toLong(), states: List<String> = listOf("pending")): JSONObject =
        JSONObject().put("record_id", id(index)).put("kind", "receipt").put("created_at", createdAt)
            .put("account", "owner@example.test").put("operation", "archive_messages")
            .put("targets", JSONArray().apply {
                states.forEachIndexed { target, state ->
                    put(JSONObject().put("id", "message-$index-$target").put("state", state)
                        .put("labels", JSONArray().put("INBOX")))
                }
            })

    private fun selection(index: Int, createdAt: Long = index.toLong()) = JSONObject()
        .put("record_id", id(index, "selection")).put("kind", "selection").put("created_at", createdAt)
        .put("ids", JSONArray().put("message-$index")).put("complete", true)

    private fun encoded(records: List<JSONObject>): String = JSONObject().apply {
        records.forEach { put(it.getString("record_id"), it) }
    }.toString()

    private fun ids(store: GmailManagementStore) = store.records().map { it.getString("record_id") }.toSet()
    private fun target(record: JSONObject) = record.getJSONArray("targets").getJSONObject(0)

    @Test fun inputGetAndRecordsAreIndependentDeepCopies() {
        val backing = Backing()
        val store = backing.store()
        val original = receipt(1).put("metadata", JSONObject().put("revision", 1))
        store.put(original)
        val durable = backing.durable

        target(original).put("state", "unknown").getJSONArray("labels").put("MUTATED_INPUT")
        original.getJSONObject("metadata").put("revision", 2)
        val fromGet = store.get(id(1))!!
        assertEquals("pending", target(fromGet).getString("state"))
        assertEquals(1, target(fromGet).getJSONArray("labels").length())
        assertEquals(1, fromGet.getJSONObject("metadata").getInt("revision"))

        target(fromGet).put("state", "dispatched").getJSONArray("labels").put("MUTATED_GET")
        fromGet.getJSONObject("metadata").put("revision", 3)
        val fromRecords = store.records().single()
        assertEquals("pending", target(fromRecords).getString("state"))
        target(fromRecords).put("state", "accepted").getJSONArray("labels").put("MUTATED_LIST")
        fromRecords.getJSONObject("metadata").put("revision", 4)

        assertEquals(durable, backing.durable)
        assertEquals("pending", target(store.get(id(1))!!).getString("state"))
        assertEquals(1, target(store.records().single()).getJSONArray("labels").length())
        assertEquals(1, store.get(id(1))!!.getJSONObject("metadata").getInt("revision"))
        store.put(receipt(1, states = listOf("verified")))
        assertEquals("dispatched", target(fromGet).getString("state"))
        assertEquals("accepted", target(fromRecords).getString("state"))
    }

    @Test fun freshWrappersAndSimulatedProcessRestartReadDurableRecordsWithoutStaleCaching() {
        val backing = Backing()
        val first = backing.store()
        val second = backing.store()
        assertNull(first.get(id(1)))
        assertTrue(second.records().isEmpty())
        first.put(receipt(1, states = listOf("dispatched")))
        assertEquals("dispatched", target(second.get(id(1))!!).getString("state"))
        second.put(receipt(1, states = listOf("accepted")))
        first.put(selection(2))
        assertEquals("accepted", target(first.get(id(1))!!).getString("state"))

        // A new backing identity models process-local guards being gone; only durable bytes survive.
        val restarted = Backing(backing.durable).store()
        assertEquals(setOf(id(1), id(2, "selection")), ids(restarted))
        assertEquals("accepted", target(restarted.get(id(1))!!).getString("state"))
        assertTrue(restarted.get(id(2, "selection"))!!.getBoolean("complete"))
    }

    @Test fun malformedOrOverCapacityStoredJsonFailsClosedOnEveryAccessWithoutRewritingIt() {
        val malformed = listOf(
            "", "not-json", "[]", "{broken", "null",
            JSONObject().put("bad-key", receipt(1)).toString(),
            JSONObject().put(id(1), receipt(2)).toString(),
            JSONObject().put(id(1), "not-an-object").toString(),
            JSONObject().put(id(1), JSONObject.NULL).toString(),
            JSONObject().put(id(1), JSONObject().put("kind", "receipt")).toString(),
            encoded((1..65).map { receipt(it) }),
        )
        malformed.forEach { raw ->
            val backing = Backing(raw)
            val store = backing.store()
            assertThrows(IllegalStateException::class.java) { store.get(id(1)) }
            assertThrows(IllegalStateException::class.java) { store.records() }
            assertThrows(IllegalStateException::class.java) { store.put(receipt(100)) }
            assertEquals(0, backing.writes)
            assertEquals(raw, backing.durable)
        }
    }

    @Test fun invalidRecordIdentifiersCannotChangeExistingStorage() {
        val backing = Backing(encoded(listOf(receipt(1))))
        val store = backing.store()
        val before = backing.durable
        val invalid = listOf(
            "", "receipt:short", "other:${UUID(0L, 2L)}", id(2).uppercase(),
            id(2) + "/extra", " " + id(2),
        )
        invalid.forEach { badId ->
            assertThrows(IllegalArgumentException::class.java) { store.put(receipt(2).put("record_id", badId)) }
        }
        assertThrows(org.json.JSONException::class.java) { store.put(JSONObject().put("kind", "receipt")) }
        assertThrows(org.json.JSONException::class.java) { store.put(receipt(2).put("record_id", 2)) }
        assertEquals(0, backing.writes)
        assertEquals(before, backing.durable)
        assertEquals(setOf(id(1)), ids(store))
    }

    @Test fun failedWritePoisonsExistingAndNewWrappersEvenWhenProcessMemoryAlreadyChanged() {
        for (mutatesMemory in listOf(false, true)) {
            val backing = Backing(encoded(listOf(receipt(1))))
            val first = backing.store()
            val sibling = backing.store()
            val before = backing.durable
            val failure = IOException("Durability acknowledgement failed")
            backing.failure = failure
            backing.changeMemoryBeforeFailure = mutatesMemory
            assertSame(failure, assertThrows(IOException::class.java) { first.put(receipt(2)) })
            assertEquals(before, backing.durable)
            assertEquals(mutatesMemory, JSONObject(backing.visible!!).has(id(2)))

            backing.failure = null
            listOf(first, sibling, backing.store()).forEach { wrapper ->
                val blocked = assertThrows(IllegalStateException::class.java) { wrapper.put(receipt(3)) }
                assertTrue(blocked.message.orEmpty().contains("durability is uncertain"))
            }
            assertEquals(1, backing.writes)
            assertEquals(before, backing.durable)

            val independent = Backing(before)
            independent.store().put(receipt(3))
            assertEquals(setOf(id(1), id(3)), ids(independent.store()))
            assertEquals(1, independent.writes)
        }
    }

    @Test fun capacity64EvictsOldestSafeSelectionPlanOrTerminalReceiptAndPreservesUncertainWrites() {
        assertEquals(64, JsonGmailManagementStore.MAX_RECORDS)
        val unsafeStates = listOf("unknown", "dispatched", "accepted")
        val protectedRecords = (1..57).map { receipt(it, createdAt = -it.toLong(), states = listOf(unsafeStates[it % 3])) }
        val safe = listOf(
            selection(101, 1),
            receipt(102, 2, listOf("pending")),
            receipt(103, 3, listOf("verified")),
            receipt(104, 4, listOf("rejected")),
            receipt(105, 5, listOf("skipped_changed")),
            receipt(106, 6, listOf("resumed")),
            receipt(107, 7, listOf("pending", "verified", "rejected")),
        )
        val store = Backing(encoded(protectedRecords + safe)).store()
        val remaining = (protectedRecords + safe).map { it.getString("record_id") }.toMutableSet()
        safe.forEachIndexed { index, oldestSafe ->
            val replacement = receipt(200 + index, createdAt = 100L + index, states = listOf("unknown"))
            store.put(replacement)
            remaining.remove(oldestSafe.getString("record_id"))
            remaining.add(replacement.getString("record_id"))
            assertEquals(64, store.records().size)
            assertEquals(remaining, ids(store))
            protectedRecords.forEach { assertNotNull(store.get(it.getString("record_id"))) }
        }
    }

    @Test fun mixedReceiptIsProtectedIfAnyTargetRemainsUnknownDispatchedOrAccepted() {
        for (uncertain in listOf("unknown", "dispatched", "accepted")) {
            for (states in listOf(listOf(uncertain, "verified"), listOf("pending", uncertain))) {
                val protectedRecords = (1..63).map { receipt(it, states = listOf("unknown")) }
                val mixed = receipt(64, createdAt = -1, states = states)
                val backing = Backing(encoded(protectedRecords + mixed))
                val before = backing.durable
                assertThrows(IllegalStateException::class.java) { backing.store().put(selection(100)) }
                assertEquals(0, backing.writes)
                assertEquals(before, backing.durable)
                assertEquals((1..64).map { id(it) }.toSet(), ids(backing.store()))
            }
        }
    }

    @Test fun fullUncertainStoreBlocksInsertionButAllowsReconciliationToFreeOneSlot() {
        val states = listOf("unknown", "dispatched", "accepted")
        val backing = Backing(encoded((1..64).map { receipt(it, states = listOf(states[it % 3])) }))
        val store = backing.store()
        val before = backing.durable
        val full = assertThrows(IllegalStateException::class.java) { store.put(receipt(100)) }
        assertTrue(full.message.orEmpty().contains("full"))
        assertEquals(0, backing.writes)
        assertEquals(before, backing.durable)

        val reconciled = store.get(id(32))!!
        target(reconciled).put("state", "verified")
        store.put(reconciled)
        assertEquals(64, store.records().size)
        assertEquals("verified", target(store.get(id(32))!!).getString("state"))
        store.put(receipt(100))
        assertEquals(64, store.records().size)
        assertNull(store.get(id(32)))
        assertNotNull(store.get(id(100)))
        (1..64).filter { it != 32 }.forEach { assertNotNull(store.get(id(it))) }
        assertEquals(2, backing.writes)
    }

    @Test fun exactSixMiBUtf8BoundaryIsAcceptedAndOneExtraByteCannotOverwriteIt() {
        assertEquals(6 * 1024 * 1024, JsonGmailManagementStore.MAX_BYTES)
        val backing = Backing()
        val store = backing.store()
        val record = selection(1).put("padding", "")
        val bytesAvailable = JsonGmailManagementStore.MAX_BYTES - encoded(listOf(record)).toByteArray(Charsets.UTF_8).size
        val padding = "界".repeat(bytesAvailable / 3) + "x".repeat(bytesAvailable % 3)
        record.put("padding", padding)
        assertEquals(JsonGmailManagementStore.MAX_BYTES, encoded(listOf(record)).toByteArray(Charsets.UTF_8).size)
        store.put(record)
        val before = backing.durable
        assertEquals(padding, store.get(id(1, "selection"))!!.getString("padding"))
        assertEquals(setOf(id(1, "selection")), ids(store))

        record.put("padding", padding + "x")
        val rejected = assertThrows(IllegalStateException::class.java) { store.put(record) }
        assertTrue(rejected.message.orEmpty().contains("byte budget"))
        assertEquals(before, backing.durable)
        assertEquals(1, backing.writes)

        // A pre-persistence size rejection must not poison subsequent valid writes.
        store.put(selection(1))
        assertEquals(2, backing.writes)
        assertFalse(store.get(id(1, "selection"))!!.has("padding"))
    }

    @Test fun oversizedInsertionAtCapacityDoesNotLoseTheTentativelyEvictedSafeRecord() {
        val protectedRecords = (1..63).map { receipt(it, states = listOf("unknown")) }
        val safe = selection(64)
        val backing = Backing(encoded(protectedRecords + safe))
        val store = backing.store()
        val before = backing.durable
        val oversized = receipt(100).put("padding", "x".repeat(JsonGmailManagementStore.MAX_BYTES))
        assertThrows(IllegalStateException::class.java) { store.put(oversized) }
        assertEquals(0, backing.writes)
        assertEquals(before, backing.durable)
        assertEquals(64, store.records().size)
        assertNotNull(store.get(id(64, "selection")))
        assertNull(store.get(id(100)))

        store.put(receipt(100))
        assertEquals(1, backing.writes)
        assertNull(store.get(id(64, "selection")))
        assertEquals((1..63).map { id(it) }.toSet() + id(100), ids(store))
    }

    @Test fun oversizedPersistedUtf8DataFailsClosedEvenWhenItsCharacterCountFits() {
        val record = selection(1).put("padding", "界".repeat(JsonGmailManagementStore.MAX_BYTES / 3))
        val raw = encoded(listOf(record))
        assertTrue(raw.length < JsonGmailManagementStore.MAX_BYTES)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size > JsonGmailManagementStore.MAX_BYTES)
        val backing = Backing(raw)
        val store = backing.store()
        assertThrows(IllegalStateException::class.java) { store.get(id(1, "selection")) }
        assertThrows(IllegalStateException::class.java) { store.records() }
        assertThrows(IllegalStateException::class.java) { store.put(receipt(2)) }
        assertEquals(0, backing.writes)
        assertEquals(raw, backing.durable)
    }

    @Test fun missingMalformedOrUnrecognizedTargetStatesCannotBeEvictedAsSafeReceipts() {
        val invalidStates = listOf<Any?>("future_unrecognized_state", null, JSONObject.NULL, 7, JSONObject(), JSONArray())
        for (invalidState in invalidStates) {
            val malformed = receipt(64, createdAt = -1)
            if (invalidState == null) target(malformed).remove("state")
            else target(malformed).put("state", invalidState)
            val protectedRecords = (1..63).map { receipt(it, states = listOf("unknown")) }
            val backing = Backing(encoded(protectedRecords + malformed))
            val before = backing.durable

            // Only positively known safe states may be retired; unknown schema is not terminal proof.
            assertThrows(IllegalStateException::class.java) { backing.store().put(receipt(100)) }
            assertEquals(0, backing.writes)
            assertEquals(before, backing.durable)
        }
    }

    @Test fun terminalReceiptsRemainProtectedUntilEveryUnresolvedIntentMarkerIsCleared() {
        for (terminalState in listOf("verified", "rejected")) {
            val firstHash = "1".repeat(64)
            val secondHash = "2".repeat(64)
            val terminal = receipt(64, createdAt = -1, states = listOf(terminalState))
                .put("unresolved_intents", JSONArray().put(firstHash).put(secondHash))
            val protectedRecords = (1..63).map { receipt(it, states = listOf("unknown")) }
            val backing = Backing(encoded(protectedRecords + terminal))
            val store = backing.store()
            val before = backing.durable

            assertThrows(IllegalStateException::class.java) { store.put(receipt(100)) }
            assertEquals(0, backing.writes)
            assertEquals(before, backing.durable)
            assertEquals(terminalState, target(store.get(id(64))!!).getString("state"))
            assertEquals(2, store.get(id(64))!!.getJSONArray("unresolved_intents").length())

            val partlyReconciled = store.get(id(64))!!
            partlyReconciled.put("unresolved_intents", JSONArray().put(secondHash))
            store.put(partlyReconciled)
            val afterPartialReconciliation = backing.durable
            assertThrows(IllegalStateException::class.java) { backing.store().put(receipt(100)) }
            assertEquals(1, backing.writes)
            assertEquals(afterPartialReconciliation, backing.durable)
            assertEquals(secondHash, store.get(id(64))!!.getJSONArray("unresolved_intents").getString(0))

            val reconciled = store.get(id(64))!!
            reconciled.put("unresolved_intents", JSONArray())
            store.put(reconciled)
            store.put(receipt(100))
            assertEquals(3, backing.writes)
            assertEquals(64, store.records().size)
            assertNull(store.get(id(64)))
            assertEquals((1..63).map { id(it) }.toSet() + id(100), ids(store))
        }
    }

    @Test fun resumeChildCannotEvictItsPendingParentWhenEveryOtherReceiptIsUncertain() {
        val parent = receipt(1, createdAt = -100, states = listOf("pending"))
        val uncertainStates = listOf("unknown", "dispatched", "accepted")
        val protectedRecords = (2..64).map { receipt(it, states = listOf(uncertainStates[it % 3])) }
        val backing = Backing(encoded(listOf(parent) + protectedRecords))
        val store = backing.store()
        val before = backing.durable
        val child = receipt(100).put("resumes_receipt", id(1))

        val blocked = assertThrows(IllegalStateException::class.java) { store.put(child) }
        assertTrue(blocked.message.orEmpty().contains("full"))
        assertEquals(0, backing.writes)
        assertEquals(before, backing.durable)
        assertEquals((1..64).map { id(it) }.toSet(), ids(store))
        assertEquals("pending", target(store.get(id(1))!!).getString("state"))
        assertNull(store.get(id(100)))
    }

    @Test fun resumeChildEvictsAnotherSafeRecordInsteadOfItsOlderPendingParent() {
        val parent = receipt(1, createdAt = -100, states = listOf("pending"))
        val protectedRecords = (2..63).map { receipt(it, states = listOf("unknown")) }
        val otherSafe = receipt(64, createdAt = 100, states = listOf("verified"))
        val backing = Backing(encoded(listOf(parent, otherSafe) + protectedRecords))
        val store = backing.store()
        val child = receipt(100, createdAt = 200).put("resumes_receipt", id(1))

        store.put(child)

        assertEquals(1, backing.writes)
        assertEquals(64, store.records().size)
        assertEquals((1..63).map { id(it) }.toSet() + id(100), ids(store))
        assertNull(store.get(id(64)))
        assertEquals("pending", target(store.get(id(1))!!).getString("state"))
        assertEquals(-100L, store.get(id(1))!!.getLong("created_at"))
        assertEquals(id(1), store.get(id(100))!!.getString("resumes_receipt"))
        assertEquals("pending", target(store.get(id(100))!!).getString("state"))
        assertEquals(ids(store), ids(Backing(backing.durable).store()))
    }

    @Test fun verifiedReceiptsWithRetainedJournalOrLegacyUntrackedIntentCannotBeEvicted() {
        val hash = "a".repeat(64)
        for (hasLedger in listOf(true, false)) {
            val verified = receipt(64, createdAt = -100, states = listOf("verified"))
            target(verified).put("intent_hash", hash)
            if (hasLedger) verified.put("journal_intents", JSONArray().put(hash))
            // No failed-cleanup flag exists: a crash can occur before resolve returns at all.
            assertFalse(verified.has("unresolved_intents"))
            val protectedRecords = (1..63).map { receipt(it, states = listOf("unknown")) }
            val backing = Backing(encoded(protectedRecords + verified))
            val store = backing.store(); val durableBefore = backing.durable

            assertThrows(IllegalStateException::class.java) { store.put(receipt(100)) }
            assertEquals(durableBefore, backing.durable); assertEquals(0, backing.writes)
            assertEquals("verified", target(store.get(id(64))!!).getString("state"))
            assertEquals(hash, target(Backing(backing.durable).store().get(id(64))!!).getString("intent_hash"))

            // Explicit empty ledger is proof cleanup was durably acknowledged, unlike an absent ledger.
            val reconciled = store.get(id(64))!!.put("journal_intents", JSONArray())
            store.put(reconciled)
            store.put(receipt(100))
            assertEquals(2, backing.writes); assertNull(store.get(id(64)))
            assertEquals((1..63).map { id(it) }.toSet() + id(100), ids(store))
        }
    }
}
