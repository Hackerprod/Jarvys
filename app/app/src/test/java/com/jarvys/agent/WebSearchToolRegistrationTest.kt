package com.jarvys.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolRegistrationTest {
    @Test fun webToolsAreAlwaysRegisteredByRuntimeAssembly() {
        val runtime = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        val names = runtime.createTools().names()
        assertTrue(names.contains(WebSearchTools.SEARCH))
        assertTrue(names.contains(WebSearchTools.FETCH))
    }
}
