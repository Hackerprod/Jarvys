package com.jarvys.agent

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Pure fake connections: constructing the URL never opens a socket. */
class CodexAuthConnectionRetryTest {
    private val stage = CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE
    private class FakeTime : CodexAuthConnectionRetry.Clock {
        var now = 0L
        val waits = mutableListOf<Long>()
        override fun nowMillis() = now
        val waiter = CodexAuthConnectionRetry.Waiter { millis, token ->
            token.throwIfCancelled()
            waits += millis
            now += millis
        }
    }
    private fun connection(failure: Exception? = null) = object : HttpURLConnection(URL("https://auth.openai.com/oauth/token")) {
        override fun connect() { failure?.let { throw it } }
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
    private fun dns(token: CancellationToken) = CodexAuthConnectionRetry.connectBeforeBody(
        connection(UnknownHostException("secret-body-and-host")), stage, token)

    @Test fun retriesOnlyPreBodyDnsAtTwoFourEightSeconds() {
        val time = FakeTime()
        val token = CancellationToken.cancellable()
        var attempts = 0
        val result = CodexAuthConnectionRetry.execute(token, {
            attempts++
            if (attempts < 4) dns(token)
            "connected"
        }, time, time.waiter)
        assertEquals("connected", result)
        assertEquals(4, attempts)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), time.waits)
    }

    @Test fun dnsRetryStopsAfterThreeWaitsAndContainsNoRawFailure() {
        val time = FakeTime()
        val token = CancellationToken.cancellable()
        var attempts = 0
        val error = runCatching {
            CodexAuthConnectionRetry.execute(token, { attempts++; dns(token) }, time, time.waiter)
        }.exceptionOrNull()
        assertTrue(error is CodexAuthConnectionRetry.BeforeBodyDnsFailure)
        assertEquals(4, attempts)
        assertEquals(14_000L, time.now)
        assertFalse(error.toString().contains("secret-body-and-host"))
        assertNull(error?.cause)
        assertEquals("before_body_dns", (error as CodexAuthDiagnostic.Failure).diagnostic.reason)
    }

    @Test fun timeWindowIncludesAttemptsAndDoesNotStartAnotherAtTwentySeconds() {
        val time = FakeTime()
        val token = CancellationToken.cancellable()
        var attempts = 0
        val error = runCatching {
            CodexAuthConnectionRetry.execute(token, {
                attempts++
                time.now += 19_000L
                dns(token)
            }, time, time.waiter)
        }.exceptionOrNull()
        assertTrue(error is CodexAuthConnectionRetry.BeforeBodyDnsFailure)
        assertEquals(1, attempts)
        assertEquals(listOf(1_000L), time.waits)
        assertEquals(20_000L, time.now)
    }

    @Test fun rawDnsAfterBodyTimeoutHttpAndTlsAreNeverReplayed() {
        val failures = listOf(UnknownHostException("body already sent"), SocketTimeoutException("secret"),
            javax.net.ssl.SSLException("secret"), CodexAuthDiagnostic.Failure(CodexAuthDiagnostic.http(stage, 503, null)))
        failures.forEach { expected ->
            val time = FakeTime()
            var attempts = 0
            val error = runCatching {
                CodexAuthConnectionRetry.execute(CancellationToken.cancellable(), { attempts++; throw expected }, time, time.waiter)
            }.exceptionOrNull()
            assertSame(expected, error)
            assertEquals(1, attempts)
            assertTrue(time.waits.isEmpty())
        }
    }

    @Test fun cancellationBeforeAttemptDoesNotConnect() {
        val token = CancellationToken.cancellable().also { it.cancel() }
        var attempts = 0
        val time = FakeTime()
        assertTrue(runCatching { CodexAuthConnectionRetry.execute(token, { attempts++ }, time, time.waiter) }
            .exceptionOrNull() is CancellationException)
        assertEquals(0, attempts)
    }

    @Test fun cancellationDuringBackoffDoesNotStartAnotherAttempt() {
        val token = CancellationToken.cancellable()
        var attempts = 0
        val time = FakeTime()
        val error = runCatching {
            CodexAuthConnectionRetry.execute(token, { attempts++; dns(token) }, time,
                CodexAuthConnectionRetry.Waiter { _, cancellation -> cancellation.cancel() })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun cancellationDuringConnectTakesPriorityOverDnsDiagnostic() {
        val token = CancellationToken.cancellable()
        val fake = object : HttpURLConnection(URL("https://auth.openai.com/oauth/token")) {
            override fun connect() { token.cancel(); throw UnknownHostException("secret") }
            override fun disconnect() = Unit
            override fun usingProxy() = false
        }
        assertTrue(runCatching { CodexAuthConnectionRetry.connectBeforeBody(fake, stage, token) }
            .exceptionOrNull() is CancellationException)
    }
}
