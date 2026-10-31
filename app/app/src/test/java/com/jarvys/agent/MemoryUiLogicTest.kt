package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryUiLogicTest {
    @Test
    fun parsesAndSerializesEditableNotesWithoutChangingTheirBody() {
        val raw = "---\nname: Food\ndescription: Dietary preference\n---\nAvoid peanuts.\n"
        val parsed = MemoryUiLogic.parseDocument("food.md", raw)
        assertEquals("Food", parsed.name)
        assertEquals("Dietary preference", parsed.description)
        assertEquals("Avoid peanuts.\n", parsed.body)
        assertEquals(raw, MemoryUiLogic.serializeDocument("food.md", parsed.name, parsed.description, parsed.body))
        assertEquals("# Index\n", MemoryUiLogic.serializeDocument("MEMORY.md", "", "", "# Index\n"))
    }

    @Test
    fun diffReportsChangedLinesAndSessionPreferenceKeysAreIsolated() {
        assertEquals(
            listOf(
                MemoryUiLogic.DiffLine(MemoryUiLogic.DiffKind.UNCHANGED, "first"),
                MemoryUiLogic.DiffLine(MemoryUiLogic.DiffKind.REMOVED, "old"),
                MemoryUiLogic.DiffLine(MemoryUiLogic.DiffKind.ADDED, "new"),
                MemoryUiLogic.DiffLine(MemoryUiLogic.DiffKind.UNCHANGED, "last"),
            ),
            MemoryUiLogic.diffLines("first\nold\nlast", "first\nnew\nlast"),
        )
        assertTrue(MemoryUiLogic.sessionMemoryDisabledKey("one") != MemoryUiLogic.sessionMemoryDisabledKey("two"))
        assertTrue(MemoryUiLogic.hasRevisionConflict(2, 3))
        assertFalse(MemoryUiLogic.hasRevisionConflict(2, 2))
    }
}
