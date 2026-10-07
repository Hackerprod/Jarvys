package com.jarvys.agent.flavor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.coding.ProjectScopeStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayLinuxBoundaryTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun playNeverAdvertisesLinuxOrProjectExecution() {
        assertTrue(FlavorLinuxTools.create(context, "chat", 0).isEmpty())
        assertTrue(FlavorLinuxTools.create(context, "chat", 0, CorePromptBudget.standard()).isEmpty())
        assertTrue(FlavorLinuxTools.profileCapabilityNames(context, "chat").isEmpty())
        assertEquals("", FlavorLinuxTools.profilePrompt(context, "chat"))
        assertNull(FlavorLinuxTools.systemPromptSection(context, "chat", 0))
    }

    @Test fun unavailableSelectedCapabilitiesRejectResume() {
        for (name in CodingExecutionTools.NAMES + listOf("linux_exec", "linux_setup", "linux_status", "linux_uninstall")) {
            val failure = runCatching { FlavorLinuxTools.validateProfileResume(context, listOf("read", name)) }.exceptionOrNull()
            assertTrue("$name must fail closed", failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("unavailable in this Play build"))
        }
        FlavorLinuxTools.validateProfileResume(context, listOf("ls", "read", "coding_grep"))
    }

    @Test fun savedJobsRemainUnverifiableAndBlockResume() {
        val scope = ProjectScopeStore(folder.newFolder()).open("chat")
        val failure = runCatching { FlavorLinuxTools.checkpointRecovery(context, "chat", scope, listOf("chat/bot/1")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("cannot be verified"))
        assertTrue(failure.message.orEmpty().contains("resume did not start"))
        assertTrue(failure.message.orEmpty().contains("no command was replayed or process signalled"))
    }

    @Test fun ownerlessFileCheckpointDoesNotClaimExecutionWasVerified() {
        val scope = ProjectScopeStore(folder.newFolder()).open("chat")
        val observation = FlavorLinuxTools.checkpointRecovery(context, "chat", scope, emptyList())
        assertTrue(observation.contains("unavailable in this Play build"))
        assertTrue(observation.contains("No execution outcome or process liveness was verified"))
        assertEquals(0L, scope.version())
        assertTrue(runCatching { FlavorLinuxTools.checkpointRecovery(context, "other", scope, emptyList()) }.isFailure)
    }

    @Test fun fullOnlyRuntimeClassesAreAbsent() {
        for (name in listOf("LinuxEnvironment", "LinuxExecAutonomy", "CodingJobManager", "ProotLauncher")) {
            assertTrue("Full implementation must not enter Play: $name", runCatching {
                Class.forName("com.jarvys.agent.linux.$name")
            }.exceptionOrNull() is ClassNotFoundException)
        }
    }
}
