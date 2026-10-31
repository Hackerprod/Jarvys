package com.jarvys.agent.proactive

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ToolSpec
import org.junit.Assert.*
import org.junit.Test

/** Exercises the private wrapper without opening connectors, models, or changing production visibility. */
class ProactiveCompletionMetadataTest {
    @Test fun redactingWrapperPreservesArgumentSensitiveAsyncMetadata() {
        val delegate = object : CoreTool {
            override fun declaration() = ToolSpec("synthetic_metadata", "test", "Synthetic", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun completionMode(arguments: Map<String, Any>) = if (arguments["async"] == true)
                CoreTool.CompletionMode.ASYNC_JOB else CoreTool.CompletionMode.SYNCHRONOUS
            override fun hasCompleteAsyncSubmissionCoverage() = true
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult = error("Metadata only")
        }
        val constructor = Class.forName("com.jarvys.agent.proactive.ProactiveRedactingConnectorTool")
            .getDeclaredConstructor(CoreTool::class.java).apply { isAccessible = true }
        val wrapped = constructor.newInstance(delegate) as CoreTool
        assertEquals(CoreTool.CompletionMode.SYNCHRONOUS, wrapped.completionMode(emptyMap()))
        assertEquals(CoreTool.CompletionMode.ASYNC_JOB, wrapped.completionMode(mapOf("async" to true)))
        assertTrue(wrapped.hasCompleteAsyncSubmissionCoverage())
    }
}
