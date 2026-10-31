package com.jarvys.agent.connectors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class AutonomyAuditMigrationTest {
    private fun row(id: String = "audit-one", connector: String = "calendar") = JSONObject()
        .put("id", id).put("timestampMillis", 7L).put("connectorId", connector)
        .put("connectorName", "Calendar").put("operationName", "create_event")
        .put("operationLabel", "Create event").put("summary", "Synthetic event")
    private fun source(vararg rows: Any) = JSONArray(rows.toList()).toString()
    private class Source(var bytes: String?) : LegacyAutonomyAuditSource {
        var done = false
        var cleanupSucceeds = true
        var memoryChangesOnFailure = false
        override fun migrated() = done
        override fun original() = bytes
        override fun complete(expectedOriginal: String?): Boolean {
            check(bytes == expectedOriginal)
            if (cleanupSucceeds || memoryChangesOnFailure) { done = true; bytes = null }
            return cleanupSucceeds
        }
    }
    private class Target : AutonomyAuditMigrationTarget {
        val records = linkedMapOf<String, AutonomyAuditRecord>()
        var fail = false
        var imports = 0
        override fun importRecords(records: List<AutonomyAuditRecord>) {
            imports++
            val next = LinkedHashMap(this.records)
            records.forEach {
                check(next[it.id] == null || next[it.id] == it)
                next[it.id] = it
            }
            check(!fail)
            this.records.clear(); this.records.putAll(next)
        }
    }
    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { }
    }

    @Test fun malformedOrPartialSourceIsPreservedWhole() {
        val invalid = listOf("[", "[] trailing lost content", "[] []", "[] {}", source(row(), "not an object"), source(row().apply { remove("id") }),
            source(row().put("id", " ")), source(row().put("timestampMillis", "7")),
            source(row().put("timestampMillis", 1.5)), source(row().put("summary", JSONObject.NULL)),
            source(row().put("futureField", "unmapped")))
        for (text in invalid) {
            val src = Source(text); val target = Target()
            fails { AutonomyAuditMigration(src, target, Any()).migrate() }
            assertEquals(text, src.bytes); assertFalse(src.done); assertEquals(0, target.imports)
        }
    }

    @Test fun strictLegacyGrammarRejectsDuplicateFieldsAndNonJsonNumbers() {
        val valid = source(row())
        val duplicate = valid.replace("\"id\":\"audit-one\"", "\"id\":\"audit-one\",\"id\":\"replacement\"")
        val invalid = listOf(duplicate, "/*comment*/" + valid, valid + "\u0000hidden",
            valid.replace("Synthetic event", "Synthetic\nevent"),
            valid.replace("Synthetic event", "Invalid\\qescape"),
            valid.replace("Synthetic event", "Invalid\\u+123escape"),
            valid.replace("Synthetic event", "Invalid\\u-123escape"), valid.replace("\"id\":", "id:"),
            valid.replace("\"timestampMillis\":7", "\"timestampMillis\":07"),
            valid.replace("\"timestampMillis\":7", "\"timestampMillis\":0x7"),
            valid.replace("\"timestampMillis\":7", "\"timestampMillis\":9223372036854775808"),
            valid.dropLast(1) + ",]", valid.replace("}]", ",}]"))
        for (text in invalid) {
            assertNotEquals(valid, text)
            val src = Source(text); val target = Target()
            fails { AutonomyAuditMigration(src, target, Any()).migrate() }
            assertEquals(text, src.bytes); assertFalse(src.done); assertEquals(0, target.imports)
        }
        val negative = LegacyAutonomyAuditParser.parse(valid.replace("\"timestampMillis\":7", "\"timestampMillis\":-7"))
        assertEquals(-7L, negative.single().timestampMillis)
    }

    @Test fun validWhitespaceEscapesUnicodeAndLongBoundsArePreserved() {
        val text = "Unicode á 文; quote \"; slash \\; newline\nretained"
        val first = row("minimum").put("timestampMillis", Long.MIN_VALUE).put("summary", text)
        val second = row("maximum").put("timestampMillis", Long.MAX_VALUE)
        val records = LegacyAutonomyAuditParser.parse(" \n\t[\r " + first.toString() + " ,\n" + second.toString() + " ]\n")
        assertEquals(text, records[0].summary)
        assertEquals(Long.MIN_VALUE, records[0].timestampMillis)
        assertEquals(Long.MAX_VALUE, records[1].timestampMillis)
    }

    @Test fun validBatchAndIdenticalDuplicatesImportOnceAndRetiredConnectorsStayExcluded() {
        val src = Source(source(row(), row(), row("retired", "email"), row("retired-native", "native")))
        val target = Target(); val migration = AutonomyAuditMigration(src, target, Any())
        migration.migrate(); migration.migrate()
        assertTrue(src.done); assertNull(src.bytes)
        assertEquals(setOf("audit-one"), target.records.keys); assertEquals(1, target.imports)
    }

    @Test fun conflictingDuplicateRejectsBeforeAnyImport() {
        val bytes = source(row(), row().put("summary", "Other synthetic event"))
        val src = Source(bytes); val target = Target()
        fails { AutonomyAuditMigration(src, target, Any()).migrate() }
        assertEquals(bytes, src.bytes); assertEquals(0, target.imports)
    }

    @Test fun targetFailureLeavesSourceAndCanRetry() {
        val bytes = source(row()); val src = Source(bytes); val target = Target().apply { fail = true }
        val migration = AutonomyAuditMigration(src, target, Any())
        fails { migration.migrate() }; assertEquals(bytes, src.bytes); assertFalse(src.done)
        target.fail = false; migration.migrate()
        assertEquals(1, target.records.size); assertTrue(src.done)
    }

    @Test fun cleanupFailureAfterDurableImportReplaysIdempotently() {
        val src = Source(source(row())).apply { cleanupSucceeds = false }
        val target = Target(); val migration = AutonomyAuditMigration(src, target, Any())
        fails { migration.migrate() }; assertEquals(1, target.records.size); assertFalse(src.done)
        src.cleanupSucceeds = true; migration.migrate()
        assertEquals(1, target.records.size); assertTrue(src.done); assertEquals(2, target.imports)
    }

    @Test fun failedPreferenceCommitMayChangeMemoryButDurableTargetRetainsEveryRow() {
        val bytes = source(row()); val src = Source(bytes).apply {
            cleanupSucceeds = false; memoryChangesOnFailure = true
        }
        val target = Target()
        fails { AutonomyAuditMigration(src, target, Any()).migrate() }
        assertEquals(1, target.records.size)
        // A process restart may still see the old disk source; replay must not duplicate it.
        val restarted = Source(bytes)
        AutonomyAuditMigration(restarted, target, Any()).migrate()
        assertEquals(1, target.records.size); assertTrue(restarted.done)
    }

    @Test fun changedOriginalIsNotDeletedAfterImport() {
        val src = Source(source(row()))
        val replacement = source(row("later"))
        val target = AutonomyAuditMigrationTarget { src.bytes = replacement }
        fails { AutonomyAuditMigration(src, target, Any()).migrate() }
        assertEquals(replacement, src.bytes); assertFalse(src.done)
    }

    @Test fun twoInstancesShareMigrationLockAndImportOnlyOnce() {
        val src = Source(source(row())); val target = Target(); val lock = Any()
        val start = CountDownLatch(1); val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = List(2) { Thread {
            try { start.await(); AutonomyAuditMigration(src, target, lock).migrate() }
            catch (failure: Throwable) { failures.add(failure) }
        }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty()); assertEquals(1, target.imports)
    }
}
