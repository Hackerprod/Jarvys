package com.jarvys.agent.chatdb

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preregistered prewrite OFF cohort only. Keep this assertion unconditional and unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[com.jarvys.agent.ArtifactOsShadow::class])
class ProactiveReplyPrewriteGateTest {
    @Test fun gateRemainsOffUntilIndependentD3Approval() {assertFalse(com.jarvys.agent.ProactiveReplyHandoffPolicy.admissionEnabled())}
 }
