package com.jarvys.agent.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotVisualStateTest {
    @Test fun everyCrewStatusHasItsOwnVisualModeAndWaitingReasonDistinguishesWaitTypes() {
        assertEquals(BotVisualState.Mode.IDLE, BotVisualState.from("IDLE", "").mode)
        assertEquals(BotVisualState.Mode.QUEUED, BotVisualState.from("QUEUED", "").mode)
        assertEquals(BotVisualState.Mode.RUNNING, BotVisualState.from("RUNNING", "").mode)
        assertEquals(BotVisualState.Mode.WAITING_PROVIDER, BotVisualState.from("WAITING", "límite del proveedor").mode)
        assertEquals(BotVisualState.Mode.WAITING_USER, BotVisualState.from("WAITING", "respuesta del capitán").mode)
        assertEquals(BotVisualState.Mode.DONE, BotVisualState.from("DONE", "").mode)
        assertEquals(BotVisualState.Mode.ERROR, BotVisualState.from("FAILED", "").mode)
        assertEquals(BotVisualState.Mode.INTERRUPTED, BotVisualState.from("INTERRUPTED", "").mode)
        assertEquals(BotVisualState.Mode.INTERRUPTED, BotVisualState.from("STOPPED", "").mode)
        assertTrue(BotVisualState.from("RUNNING", "").active)
        assertTrue(BotVisualState.from("WAITING", "response").active)
        assertTrue(BotVisualState.from("FAILED", "").terminal)
    }

    @Test fun eachBuiltInRoleHasDeterministicDistinctHeadGeometry() {
        val roles = listOf(CrewRoleTemplates.EXPLORER, CrewRoleTemplates.ANALYST,
            CrewRoleTemplates.CRITIC, CrewRoleTemplates.WRITER, CrewRoleTemplates.OPERATOR)
        val firstPass = roles.map(BotAvatarDesign::forRole)
        val secondPass = roles.map(BotAvatarDesign::forRole)
        assertEquals(firstPass, secondPass)
        assertEquals(roles.size, firstPass.toSet().size)
        assertNotEquals(BotAvatarDesign.forRole("custom scout"), BotAvatarDesign.forRole("custom archivist"))
    }
}
