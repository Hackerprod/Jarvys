package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.CentralInvocationTestSource
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.ChatTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [com.jarvys.agent.ArtifactOsShadow::class, com.jarvys.agent.ArtifactOsShadow.Descriptor::class])
class WorkspaceMutationSourceTest {
    @Rule @JvmField val chatTestStore = ChatTestStore(registerApplicationFilesDirectory = true)

    @Test fun exactLiveProviderWitnessCannotBeReboundToAnotherInvocationOrOmitted() {
        CentralInvocationTestSource.runWithWitness(RuntimeEnvironment.getApplication()) { owner, witness ->
            val actual = WorkspaceMutationContext("delegate", "workspace-1", witness, owner, Runnable {})
            assertSame(witness.source(), actual.rootSource)
            val foreign = EffectInvocation { throw AssertionError("Foreign operation must not be admitted") }
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceMutationContext("delegate", "workspace-1", witness, foreign, Runnable {})
            }
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceMutationContext("delegate", "workspace-1", null, owner, Runnable {})
            }
            assertFalse(owner.admitted())
            assertFalse(foreign.admitted())
        }
    }
}
