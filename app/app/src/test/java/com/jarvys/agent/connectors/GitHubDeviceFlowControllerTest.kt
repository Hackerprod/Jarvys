package com.jarvys.agent.connectors

import org.junit.Assert.*
import org.junit.Test

class GitHubDeviceFlowControllerTest {
    private class Attempt(val code: (GitHubDeviceCode) -> Unit, val complete: (Result<GitHubOAuthTokens>) -> Unit) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }
    private class Starter : GitHubDeviceFlowStarter {
        val attempts = mutableListOf<Attempt>()
        override fun start(request: GitHubAuthorizationRequest, onCode: (GitHubDeviceCode) -> Unit,
                           onComplete: (Result<GitHubOAuthTokens>) -> Unit) = Attempt(onCode, onComplete).also(attempts::add)
    }
    private val code = GitHubDeviceCode("private", "ABCD-1234", GitHubDeviceFlowProtocol.VERIFICATION_URI, 900, 5)
    private val tokens = GitHubOAuthTokens("access", "refresh", 8000, 10000, setOf("read:user"))

    @Test fun beginOwnsExactlyOneAttemptAndRejectsDuplicateClicks() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter)
        assertTrue(owner.begin(false) {}); assertFalse(owner.begin(true) {})
        assertEquals(1, starter.attempts.size); assertEquals(0, starter.attempts.single().closes)
        starter.attempts.single().code(code)
        assertEquals(code, owner.state.value.code); assertTrue(owner.state.value.inProgress)
    }
    @Test fun cancelImmediatelyResetsProgressAndClosesOnlyItsAttempt() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter)
        owner.begin(false) {}; starter.attempts.single().code(code); owner.cancel()
        assertFalse(owner.state.value.inProgress); assertNull(owner.state.value.code)
        assertEquals(GitHubDeviceFlowError.CANCELLED, owner.state.value.error)
        assertEquals(1, starter.attempts.single().closes)
    }
    @Test fun staleSuccessAndCodeCannotReplaceTheNewAttemptOrSaveCredentials() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter); val saved = mutableListOf<GitHubOAuthTokens>()
        owner.begin(false, saved::add); owner.cancel(); owner.begin(false, saved::add)
        starter.attempts[0].code(code); starter.attempts[0].complete(Result.success(tokens))
        assertTrue(saved.isEmpty()); assertTrue(owner.state.value.inProgress); assertNull(owner.state.value.code)
        assertEquals(0, starter.attempts[1].closes)
        starter.attempts[1].code(code); starter.attempts[1].complete(Result.success(tokens))
        assertEquals(listOf(tokens), saved); assertFalse(owner.state.value.inProgress)
        assertEquals(1, starter.attempts[1].closes)
    }
    @Test fun staleCancellationCannotDismissANewerCode() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter)
        owner.begin(false) {}; owner.cancel(); owner.begin(false) {}
        starter.attempts[1].code(code)
        starter.attempts[0].complete(Result.failure(GitHubDeviceFlowException("cancelled")))
        assertTrue(owner.state.value.inProgress); assertEquals(code, owner.state.value.code)
    }
    @Test fun disposeRejectsQueuedSuccessAndCannotStartAgain() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter); var saves = 0
        owner.begin(false) { saves++ }; owner.close()
        starter.attempts.single().complete(Result.success(tokens)); starter.attempts.single().code(code)
        assertEquals(0, saves); assertFalse(owner.begin(false) {}); assertNull(owner.state.value.code)
    }
    @Test fun synchronousCompletionClosesReturnedAttemptExactlyOnce() {
        var saves = 0; var closes = 0
        val owner = GitHubDeviceFlowController { _, _, complete -> complete(Result.success(tokens)); AutoCloseable { closes++ } }
        owner.begin(false) { saves++ }; owner.close()
        assertEquals(1, saves); assertEquals(1, closes)
    }
    @Test fun failureThenRetryDoesNotRetainPreviousCodeOrError() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter)
        owner.begin(false) {}; starter.attempts.single().code(code)
        starter.attempts.single().complete(Result.failure(GitHubDeviceFlowException("device_flow_disabled")))
        assertEquals(GitHubDeviceFlowError.DISABLED, owner.state.value.error); assertNull(owner.state.value.code)
        owner.begin(false) {}; assertNull(owner.state.value.error); assertTrue(owner.state.value.inProgress)
    }
    @Test fun persistenceFailureIsReportedAndNeverLeavesAConnectingSpinner() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter)
        owner.begin(false) { error("Vault unavailable") }; starter.attempts.single().complete(Result.success(tokens))
        assertFalse(owner.state.value.inProgress); assertEquals(GitHubDeviceFlowError.NETWORK, owner.state.value.error)
    }
    @Test fun starterExceptionIsRecoverableWithoutACredentialCallback() {
        val owner = GitHubDeviceFlowController { _, _, _ -> error("offline") }
        assertTrue(owner.begin(false) {}); assertFalse(owner.state.value.inProgress)
        assertEquals(GitHubDeviceFlowError.NETWORK, owner.state.value.error)
    }
    @Test fun completionTwiceSavesAtMostOnce() {
        val starter = Starter(); val owner = GitHubDeviceFlowController(starter); var saves = 0
        owner.begin(false) { saves++ }; repeat(2) { starter.attempts.single().complete(Result.success(tokens)) }
        assertEquals(1, saves)
    }
}
