package com.jarvys.agent.linux

import com.jarvys.agent.CancellationToken
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProotProcessOutputTest {
    @Test fun realProcessDrainsBothStreamsDecodesSplitUtf8AndKeepsFinalUnterminatedLine() {
        val events = Collections.synchronizedList(mutableListOf<Pair<LinuxOutputStream, String>>())
        val killer = ProcessTreeKiller(object : ProcessTree {
            override fun processIds() = emptyList<Int>()
            override fun parentPid(pid: Int): Int? = null
            override fun kill(pid: Int) = Unit
        })
        val command = "printf 'head'; printf 'err\\n' >&2; sleep 0.03; printf '\\342'; sleep 0.03; printf '\\202\\254tailfinal-no-newline'"

        val result = ProotProcessExecutor(killer).execute(
            ProotCommandPlan(listOf("/bin/sh", "-c", command), emptyMap()),
            timeoutMillis = 5_000L,
            token = CancellationToken.cancellable(),
            callback = LinuxOutputCallback { stream, text -> events += stream to text },
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("head€tailfinal-no-newline", events.filter { it.first == LinuxOutputStream.STDOUT }.joinToString("") { it.second })
        assertEquals("err\n", events.filter { it.first == LinuxOutputStream.STDERR }.joinToString("") { it.second })
    }
}
