package com.jarvys.agent.connectors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class GitHubAuthorizationRequest(val privateRepositories: Boolean, val additionalScopes: Set<String> = emptySet())

internal fun interface GitHubDeviceFlowStarter {
    fun start(request: GitHubAuthorizationRequest, onCode: (GitHubDeviceCode) -> Unit,
              onComplete: (Result<GitHubOAuthTokens>) -> Unit): AutoCloseable
}

internal data class GitHubDeviceFlowState(
    val inProgress: Boolean = false,
    val code: GitHubDeviceCode? = null,
    val error: GitHubDeviceFlowError? = null,
)

/** One UI owner, one attempt. Late callbacks can never adopt a replacement owner's credentials. */
internal class GitHubDeviceFlowController(
    private val starter: GitHubDeviceFlowStarter = GitHubDeviceFlowStarter { request, code, complete ->
        GitHubDeviceFlowAttempt.start(request.privateRepositories, code, complete, request.additionalScopes)
    },
) : AutoCloseable {
    private val mutableState = MutableStateFlow(GitHubDeviceFlowState())
    val state: StateFlow<GitHubDeviceFlowState> = mutableState.asStateFlow()
    private var generation = 0L
    private var attempt: AutoCloseable? = null
    private var closed = false

    fun begin(privateRepositories: Boolean, saveGrant: (GitHubOAuthTokens) -> Unit): Boolean =
        begin(GitHubAuthorizationRequest(privateRepositories), saveGrant)

    @Synchronized fun begin(request: GitHubAuthorizationRequest, saveGrant: (GitHubOAuthTokens) -> Unit): Boolean {
        if (closed || mutableState.value.inProgress) return false
        val owner = ++generation
        mutableState.value = GitHubDeviceFlowState(inProgress = true)
        try {
            val started = starter.start(request.copy(additionalScopes = request.additionalScopes.toSet()), { code -> synchronized(this) {
                if (owns(owner)) mutableState.value = GitHubDeviceFlowState(true, code)
            } }, { result -> synchronized(this) {
                if (owns(owner)) {
                    // Invalidate before closing: even a synchronous cancellation callback is obsolete.
                    generation++
                    val completed = attempt
                    attempt = null
                    val failure = result.fold(onSuccess = { tokens -> runCatching { saveGrant(tokens) }.exceptionOrNull() },
                        onFailure = { it })
                    mutableState.value = GitHubDeviceFlowState(error = failure?.let(GitHubDeviceFlowProtocol::mapError))
                    runCatching { completed?.close() }
                }
            } })
            if (owns(owner)) attempt = started else runCatching { started.close() }
        } catch (failure: Exception) {
            if (owns(owner)) {
                generation++
                mutableState.value = GitHubDeviceFlowState(error = GitHubDeviceFlowProtocol.mapError(failure))
            }
        }
        return true
    }

    @Synchronized fun cancel() {
        generation++
        val cancelled = attempt
        attempt = null
        mutableState.value = GitHubDeviceFlowState(error = GitHubDeviceFlowError.CANCELLED)
        runCatching { cancelled?.close() }
    }

    @Synchronized override fun close() { closed = true; cancel() }
    private fun owns(owner: Long) = !closed && generation == owner && mutableState.value.inProgress
}
