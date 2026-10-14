package com.jarvys.agent.tasks

import java.io.FileOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStoreTest {
    @Test fun snapshotsLastWinsTombstoneHidesAndRevisionPreventsStaleWrite() {
        val file = File(Files.createTempDirectory("st0-snapshot").toFile(), "tasks.jsonl")
        val store = TaskStore(file)
        val created = store.create(task("snapshot"))
        val update = store.update(created.copy(name = "New name", updatedAt = 2L), created.revision)
        assertEquals(2L, update.revision)
        assertEquals("New name", store.list().single().name)
        assertTrue(runCatching { store.update(created.copy(name = "Lost write", updatedAt = 3L), created.revision) }
            .exceptionOrNull() is StaleTaskRevisionException)

        val tombstone = store.delete(update.id, update.revision, 3L)
        assertEquals(3L, tombstone.revision)
        assertTrue(store.list().isEmpty())
        assertTrue(runCatching { store.create(created.copy(updatedAt = 4L)) }.exceptionOrNull() is StaleTaskRevisionException)
        assertEquals(3, file.readLines().size)
    }

    @Test fun futurePolicyAndCreatorFieldsRoundTripWithoutBeingInterpreted() {
        val file = File(Files.createTempDirectory("st0-reserved-fields").toFile(), "tasks.jsonl")
        val store = TaskStore(file)
        val input = task("reserved").copy(
            timePrecision = TaskTimePrecision.EXACT,
            catchUp = TaskCatchUp.SKIP_MISSED,
            validUntil = 9_000_000L,
            deleteAfterRun = true,
            lastRun = TaskLastRun("run", 100L, 101L, 102L, "OK", "CHAT_ONLY"),
            toolScope = TaskToolScope("LISTED", listOf("read_only_tool"), true, "NEVER",
                memory = true, skillIds = listOf("skill-a", "skill-b")),
            delivery = TaskDelivery.ONLY_IF_NOTABLE,
            createdBy = "AGENT_CHAT:session:message",
            creatorToolNames = listOf("read_only_tool"),
            openIncidentCause = TaskRunCause.NETWORK,
            lastIncidentRecoveredAt = 10_000L,
        )
        val created = store.create(input)
        val roundTrip = requireNotNull(TaskStore(file).get(created.id))
        assertEquals(input.timePrecision, roundTrip.timePrecision)
        assertEquals(input.catchUp, roundTrip.catchUp)
        assertEquals(input.lastRun, roundTrip.lastRun)
        assertEquals(input.toolScope, roundTrip.toolScope)
        assertEquals(input.delivery, roundTrip.delivery)
        assertEquals(input.createdBy, roundTrip.createdBy)
        assertEquals(input.creatorToolNames, roundTrip.creatorToolNames)
        assertEquals(TaskRunCause.NETWORK, roundTrip.openIncidentCause)
        assertEquals(10_000L, roundTrip.lastIncidentRecoveredAt)
        assertTrue(roundTrip.deleteAfterRun)
    }

    @Test fun legacyTaskSnapshotsLoadWithoutIncidentFieldsAndIncidentMetadataDoesNotChangeRevision() {
        val file = File(Files.createTempDirectory("task-incident-store").toFile(), "tasks.jsonl")
        val store = TaskStore(file)
        val task = store.create(task("incident"))
        assertTrue(store.openIncident(task.id, task.revision, TaskRunCause.PROVIDER_UNAVAILABLE))
        val open = requireNotNull(store.get(task.id))
        assertEquals(task.revision, open.revision)
        assertFalse(store.openIncident(task.id, task.revision, TaskRunCause.PROVIDER_UNAVAILABLE))
        assertTrue(store.closeIncident(task.id, task.revision, 99L))
        val recovered = requireNotNull(store.get(task.id))
        assertNull(recovered.openIncidentCause)
        assertEquals(99L, recovered.lastIncidentRecoveredAt)
        assertEquals(task.revision, recovered.revision)

        val oldFile = File(file.parentFile, "old-tasks.jsonl")
        val legacySnapshot = JSONObject(file.readLines().first()).apply {
            remove("openIncidentCause")
            remove("lastIncidentRecoveredAt")
        }
        oldFile.writeText(legacySnapshot.toString() + "\n", StandardCharsets.UTF_8)
        assertNull(TaskStore(oldFile).get(task.id)?.openIncidentCause)
        assertNull(TaskStore(oldFile).get(task.id)?.lastIncidentRecoveredAt)
    }

    @Test fun malformedAndTruncatedRowsAreIgnoredAndCountedWithoutLosingPriorSnapshots() {
        val file = File(Files.createTempDirectory("st0-corrupt").toFile(), "tasks.jsonl")
        val store = TaskStore(file)
        val saved = store.create(task("survives"))
        FileOutputStream(file, true).use { output ->
            output.write("{corrupt json}\n".toByteArray(StandardCharsets.UTF_8))
            output.write("{\"id\":\"truncated\"".toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        val recovered = TaskStore(file).read()
        assertEquals(listOf(saved.id), recovered.tasks.map { it.id })
        assertEquals(2, recovered.ignoredCorruptRows)
        store.read()
        assertEquals(2, store.lastReadDiagnostics)
    }

    @Test fun twoStoreInstancesSerializeConcurrentRevisionWritersAndOneBecomesStale() {
        val file = File(Files.createTempDirectory("st0-concurrent").toFile(), "tasks.jsonl")
        val firstStore = TaskStore(file)
        val secondStore = TaskStore(file)
        val original = firstStore.create(task("race"))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val one = executor.submit<Boolean> {
                ready.countDown(); start.await()
                runCatching { firstStore.update(original.copy(name = "Writer A", updatedAt = 2L), original.revision) }.isSuccess
            }
            val two = executor.submit<Boolean> {
                ready.countDown(); start.await()
                runCatching { secondStore.update(original.copy(name = "Writer B", updatedAt = 2L), original.revision) }.isSuccess
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, listOf(one.get(2, TimeUnit.SECONDS), two.get(2, TimeUnit.SECONDS)).count { it })
            assertEquals(2L, secondStore.list().single().revision)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun runLedgerClaimIsIdempotentAndFinalSnapshotWins() {
        val file = File(Files.createTempDirectory("st0-ledger").toFile(), "runs.jsonl")
        val ledger = TaskRunLedger(file)
        val started = TaskRunRecord("task", "run-1", 100L, 101L)
        assertTrue(ledger.claim(started))
        assertFalse(TaskRunLedger(file).claim(started.copy(runId = "retry")))
        ledger.saveSnapshot(started.copy(finishedAt = 102L, status = "OK", deliveryStatus = "NONE"))
        assertFalse(ledger.appendIfAbsent(started.copy(runId = "another")))
        val stored = ledger.find("task", 100L)
        assertNotNull(stored)
        assertEquals("OK", stored?.status)
        assertEquals(102L, stored?.finishedAt)
        assertNull(TaskRunLedger(file).read().runs.single().reason)
        assertEquals(2, file.readLines().size)
        assertEquals(1, ledger.read().runs.size)
    }

    @Test fun legacyLedgerCausesMapToStableCodesAndUnknownDetailsSurvive() {
        val file = File(Files.createTempDirectory("task-legacy-causes").toFile(), "runs.jsonl")
        file.writeText(listOf(
            """{"taskId":"provider","runId":"provider-run","scheduledFor":1,"startedAt":2,"status":"ERROR","deliveryStatus":"CHAT_ONLY","errorCause":"provider_http_503"}""",
            """{"taskId":"unknown","runId":"unknown-run","scheduledFor":3,"startedAt":4,"status":"ERROR","deliveryStatus":"CHAT_ONLY","reason":"Vendor failure detail"}""",
            """{"taskId":"interrupted","runId":"interrupted-run","scheduledFor":5,"startedAt":6,"status":"INTERRUPTED","deliveryStatus":"UNKNOWN","reason":"Previous occurrence was interrupted"}""",
        ).joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)

        val result = TaskRunLedger(file).read()
        assertEquals(0, result.ignoredCorruptRows)
        val provider = result.runs.single { it.taskId == "provider" }
        assertEquals(TaskRunCause.PROVIDER_UNAVAILABLE, provider.causeCode)
        assertEquals("provider_http_503", provider.causeDetail)
        val unknown = result.runs.single { it.taskId == "unknown" }
        assertEquals(TaskRunCause.UNKNOWN, unknown.causeCode)
        assertEquals("Vendor failure detail", unknown.causeDetail)
        assertEquals(TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED,
            result.runs.single { it.taskId == "interrupted" }.causeCode)
    }

    @Test fun runLedgerReadsDeliveredResultWhenPresentAndOldRowsWithoutIt() {
        val file = File(Files.createTempDirectory("task-run-delivery").toFile(), "runs.jsonl")
        val ledger = TaskRunLedger(file)
        assertTrue(ledger.appendIfAbsent(TaskRunRecord("with-result", "run-1", 1L, 2L, 3L,
            "OK", "DELIVERED", deliveredResult = "The model's delivered summary.",
            conversationMessageId = "manual:one")))
        val roundTrip = TaskRunLedger(file).read().runs.single()
        assertEquals("The model's delivered summary.", roundTrip.deliveredResult)
        assertEquals("manual:one", roundTrip.conversationMessageId)

        val oldFile = File(file.parentFile, "legacy-runs.jsonl")
        oldFile.writeText(
            """{"taskId":"legacy","runId":"run-2","scheduledFor":4,"startedAt":5,"status":"OK","deliveryStatus":"CHAT_ONLY"}""" + "\n",
            StandardCharsets.UTF_8)
        val legacy = TaskRunLedger(oldFile).read().runs.single()
        assertNull(legacy.deliveredResult)
        assertNull(legacy.conversationMessageId)
    }

    @Test fun concurrentLedgerClaimsForOneOccurrenceGrantExactlyOneExecutor() {
        val file = File(Files.createTempDirectory("st0-ledger-race").toFile(), "runs.jsonl")
        val first = TaskRunLedger(file)
        val second = TaskRunLedger(file)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<Boolean> {
                ready.countDown(); start.await()
                first.claim(TaskRunRecord("task", "run-a", 5_000L, 5_001L))
            }
            val b = executor.submit<Boolean> {
                ready.countDown(); start.await()
                second.claim(TaskRunRecord("task", "run-b", 5_000L, 5_001L))
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, listOf(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS)).count { it })
            assertEquals(1, first.read().runs.size)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun equivalentConfirmedCreationsRaceToOneTaskAndManualOccurrenceDoesNotCollide() {
        val file = File(Files.createTempDirectory("st2-create-deduplicate").toFile(), "tasks.jsonl")
        val one = TaskStore(file)
        val two = TaskStore(file)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<TaskCreateResult> {
                ready.countDown(); start.await()
                one.createIfEquivalent(task("same").copy(schedule = TaskSchedule.Every(900_000L, 1L)))
            }
            val second = executor.submit<TaskCreateResult> {
                ready.countDown(); start.await()
                two.createIfEquivalent(task("same").copy(schedule = TaskSchedule.Every(900_000L, 2L)))
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            val results = listOf(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.created })
            assertEquals(results[0].task.id, results[1].task.id)
            assertEquals(1, one.list().size)
        } finally {
            start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }

        val ledgerFile = File(file.parentFile, "runs.jsonl")
        val ledger = TaskRunLedger(ledgerFile)
        val scheduled = TaskRunRecord("task", "scheduled", 500L, 501L, status = "OK")
        val manual = scheduled.copy(runId = "manual", manualOccurrenceId = "manual:task:unique-id")
        assertTrue(ledger.appendIfAbsent(scheduled))
        assertTrue(ledger.appendIfAbsent(manual))
        assertFalse(ledger.appendIfAbsent(manual.copy(runId = "manual-retry")))
        assertEquals(2, ledger.forTask("task").size)
        assertEquals(setOf("task:500", "manual:task:unique-id"), ledger.forTask("task").map { it.idempotencyKey }.toSet())
    }

    @Test fun confirmedHistoryClearPhysicallyRemovesOnlyThatTasksRows() {
        val file = File(Files.createTempDirectory("st2-ledger-clear").toFile(), "runs.jsonl")
        val ledger = TaskRunLedger(file)
        ledger.appendIfAbsent(TaskRunRecord("remove-me", "one", 1L, 2L, status = "OK"))
        ledger.appendIfAbsent(TaskRunRecord("keep-me", "two", 3L, 4L, status = "OK"))
        ledger.clearTask("remove-me")
        assertEquals(listOf("keep-me"), ledger.read().runs.map { it.taskId })
        assertFalse(file.readText().contains("remove-me"))
        assertTrue(file.readText().contains("keep-me"))
    }

    private fun task(name: String) = ScheduledTask(
        name = name, instruction = "Record an occurrence",
        schedule = TaskSchedule.Every(15L * 60L * 1000L, 60_000L),
        createdAt = 1L,
    )
}
