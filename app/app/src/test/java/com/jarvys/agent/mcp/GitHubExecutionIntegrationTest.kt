package com.jarvys.agent.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalPresenter
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyActionNotifier
import com.jarvys.agent.connectors.AutonomyAuditRecord
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.GitHubDeviceFlowProtocol
import com.jarvys.agent.connectors.GitHubNativeBridge
import com.jarvys.agent.connectors.GitHubNativeRequest
import com.jarvys.agent.connectors.GitHubNativeResponse
import com.jarvys.agent.connectors.GitHubNativeTransport
import com.jarvys.agent.connectors.GitHubOAuthTokens
import com.jarvys.agent.connectors.GitHubOperationPolicy
import com.jarvys.agent.connectors.GitHubWriteJournals
import com.jarvys.agent.connectors.InMemoryConnectorAutonomyStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real connection, discovery, preparation, approval, lease, and journal boundaries.
 * Every HTTP/protocol/OAuth boundary is injected; no account, browser, or real network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubExecutionIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixtures = mutableListOf<Harness>()
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)
    private val now = 1_700_000_000_000L

    @Before fun setUp() { clearPreferences() }
    @After fun tearDown() {
        fixtures.forEach { it.close() }
        clearPreferences()
    }
    private fun clearPreferences() {
        context.getSharedPreferences("jarvys_mcp_servers", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun discoveryReplacesUnguardedAliasesAndMergeDispatchUsesAdvertisedWireKey() {
        val h = harness(listOf(remote("push_files"), remote("delete_file"), remote("merge_pull_request")))
        val names = h.repository.get(h.config.id)!!.tools.map { it.wireName }
        assertFalse(names.contains("push_files")); assertFalse(names.contains("delete_file"))
        assertTrue(names.contains(GitHubNativeBridge.COMMIT_FILES))
        h.enable("merge_pull_request")
        assertThrows(IllegalArgumentException::class.java) { h.call("merge_pull_request", target().put("pullNumber", 7).put("sha", head)) }
        assertTrue(h.calls.isEmpty()); assertTrue(h.presenter.shown.isEmpty())
        h.approved("merge_pull_request", target().put("pullNumber", 7).put("expectedHeadSha", head))
        assertEquals(head, h.calls.single().second.getString("expectedHeadSha"))
        assertFalse(h.calls.single().second.has("sha"))
    }

    @Test fun removingMergeGuardFromLiveSchemaDuringApprovalBlocksDispatchWithoutPoisoningIntent() {
        val h = harness(listOf(remote("merge_pull_request")))
        h.enable("merge_pull_request")
        val args = target().put("pullNumber", 7).put("expectedHeadSha", head)
        val pending = h.start("merge_pull_request", args)
        val (id, _) = h.awaitApproval()
        val current = h.repository.get(h.config.id)!!
        h.repository.updateTools(h.config.id, current.tools.map { if(it.wireName == "merge_pull_request") it.copy(inputSchemaJson = "{\"type\":\"object\",\"properties\":{}}") else it })
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        assertTrue(h.failure(pending) is IllegalArgumentException)
        assertTrue(h.calls.isEmpty())
        assertFalse(h.journal.isUncertain(h.intent("merge_pull_request", args)))
    }

    @Test fun deepArgumentSnapshotMatchesApprovalEvenWhenCallerMutatesOriginalWhileWaiting() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        val labels = JSONArray().put("reviewed")
        val metadata = JSONObject().put("value", "reviewed nested value")
        val supplied = issueArgs().put("labels", labels).put("metadata", metadata)
        val future = h.start("issue_write", supplied)
        val (id, summary) = h.awaitApproval()
        assertTrue(summary.lines.any { it.contains("octo/repo") })
        assertTrue(summary.lines.any { it.contains("issue_write / create") })
        supplied.put("owner", "attacker").put("repo", "different").put("method", "update").put("title", "changed")
        labels.put("unreviewed")
        metadata.put("value", "unreviewed nested value")
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        future.get(5, TimeUnit.SECONDS)
        val sent = h.calls.single().second
        assertEquals("octo", sent.getString("owner"))
        assertEquals("repo", sent.getString("repo"))
        assertEquals("create", sent.getString("method"))
        assertEquals("Reviewed title", sent.getString("title"))
        assertEquals(1, sent.getJSONArray("labels").length())
        assertEquals("reviewed nested value", sent.getJSONObject("metadata").getString("value"))
        assertFalse(h.journal.isUncertain(h.intent("issue_write", sent)))
    }

    @Test fun ownerRepositoryAndMethodAreRequiredBeforeApprovalOrDispatch() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        for (missing in listOf("owner", "repo", "method")) {
            val args = issueArgs().apply { remove(missing) }
            assertThrows(IllegalArgumentException::class.java) { h.call("issue_write", args) }
        }
        for (method in listOf("", "delete", "get", "CREATE")) {
            assertThrows(IllegalArgumentException::class.java) { h.call("issue_write", issueArgs().put("method", method)) }
        }
        assertTrue(h.presenter.shown.isEmpty())
        assertTrue(h.calls.isEmpty())
        assertTrue(h.nativeRequests.isEmpty())
    }

    @Test fun everyReviewedGitHubWriteStillAsksWithLegacyAllowAndReadOnlyAnnotations() {
        val writes = linkedMapOf(
            "create_branch" to target().put("branch", "feature/review").put("from_branch", "main"),
            "issue_write" to issueArgs(),
            "add_issue_comment" to target().put("issue_number", 1).put("body", "Reviewed comment"),
            "create_pull_request" to prArgs(),
            "update_pull_request" to target().put("pullNumber", 1).put("title", "Reviewed title"),
            "pull_request_review_write" to target().put("method", "create").put("pullNumber", 1).put("commitID", head),
            "add_comment_to_pending_review" to target().put("pullNumber", 1).put("body", "Reviewed comment"),
            "update_pull_request_branch" to target().put("pullNumber", 1).put("expectedHeadSha", head),
            "merge_pull_request" to target().put("pullNumber", 1).put("expectedHeadSha", head),
            "sub_issue_write" to target().put("method", "add").put("issue_number", 1),
            "discussion_comment_write" to target().put("method", "add").put("discussionNumber", 1).put("body", "Reviewed"),
            "actions_run_trigger" to target().put("method", "run_workflow").put("workflow_id", "ci.yml"),
            "create_or_update_file" to target().put("branch", "feature/review").put("path", "file.txt").put("content", "Reviewed text").put("message", "Update file"),
            GitHubNativeBridge.CREATE_DISCUSSION to target().put("categoryId", "DIC_1").put("title", "Reviewed").put("body", "Reviewed"),
            GitHubNativeBridge.UPDATE_DISCUSSION to target().put("discussionNumber", 1).put("expectedUpdatedAt", "2026-10-08T10:00:00Z").put("title", "Reviewed").put("body", "Reviewed"),
            GitHubNativeBridge.COMMIT_FILES to commitArgs(),
        )
        val h = harness(writes.keys.map { remote(it, readOnly = true) }, groups = GitHubOperationPolicy.supportedToolsets)
        for ((name, args) in writes) {
            h.enable(name)
            val definition = h.repository.exposedTools().single { it.wireName == name }
            assertEquals("Server annotations must not authorize $name", McpToolAccess.WRITE, definition.access)
            h.store.setPolicy("mcp:${h.config.id}", name, AutonomyPolicy.ALLOW)
            assertEquals(AutonomyPolicy.ASK, h.approval.policy(definition))
            assertThrows(IllegalArgumentException::class.java) { h.approval.setPolicy(definition, AutonomyPolicy.ALLOW) }
            val future = h.start(name, args)
            val (id, summary) = h.awaitApproval()
            assertFalse(summary.allowAlwaysAvailable)
            assertTrue(summary.lines.any { it.contains("octo/repo") })
            assertTrue(h.calls.isEmpty())
            h.gate.resolve(id, ApprovalDecision.DENIED)
            assertTrue(h.failure(future) is McpWriteDeniedException)
        }
        assertTrue(h.calls.isEmpty())
        assertTrue(h.nativeRequests.isEmpty())
    }

    @Test fun changingBearerAccountDuringApprovalPreventsDispatchAndDoesNotReserveIntent() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        val args = issueArgs()
        val future = h.start("issue_write", args)
        val (id, _) = h.awaitApproval()
        h.repository.saveBearerToken(h.config.id, h.config.endpoint, "replacement-test-account")
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        assertTrue(h.failure(future) is IllegalArgumentException)
        assertTrue(h.calls.isEmpty())
        assertFalse(h.journal.isUncertain(h.intent("issue_write", args)))
    }

    @Test fun previouslyChangedAccountIsRejectedBeforeAnotherApproval() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        h.repository.saveBearerToken(h.config.id, h.config.endpoint, "replacement-test-account")
        assertThrows(McpReauthRequiredException::class.java) { h.call("issue_write", issueArgs()) }
        assertTrue(h.presenter.shown.isEmpty())
        assertTrue(h.calls.isEmpty())
    }

    @Test fun disablingToolDuringApprovalBlocksDispatchButDoesNotPoisonUnsentIntent() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        val args = issueArgs()
        val future = h.start("issue_write", args)
        val (id, _) = h.awaitApproval()
        h.repository.updateToolEnabled(h.config.id, "issue_write", false)
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        assertTrue(h.failure(future) is IllegalArgumentException)
        assertTrue(h.calls.isEmpty())
        assertFalse(h.journal.isUncertain(h.intent("issue_write", args)))
        h.enable("issue_write")
        h.approved("issue_write", args)
        assertEquals(1, h.calls.size)
    }

    @Test fun changingSavedWritePolicyToDenyDuringApprovalPreventsDispatch() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        val future = h.start("issue_write", issueArgs())
        val (id, _) = h.awaitApproval()
        h.store.setPolicy("mcp:${h.config.id}", "issue_write", AutonomyPolicy.DENY)
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        assertTrue(h.failure(future) is IllegalArgumentException)
        assertTrue(h.calls.isEmpty())
        assertFalse(h.journal.isUncertain(h.intent("issue_write", issueArgs())))
    }

    @Test fun disconnectDuringApprovalInvalidatesTheCapturedConnectionGeneration() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        val future = h.start("issue_write", issueArgs())
        val (id, _) = h.awaitApproval()
        h.manager.disconnect(h.config.id)
        h.gate.resolve(id, ApprovalDecision.APPROVED)
        assertTrue(h.failure(future) is IllegalArgumentException)
        assertTrue(h.calls.isEmpty())
        assertFalse(h.journal.isUncertain(h.intent("issue_write", issueArgs())))
    }

    @Test fun oauth401RefreshCannotReplayAfterToolIsDisabledDuringRefresh() {
        val h = harness(listOf(remote("issue_write")), oauth = true)
        h.enable("issue_write")
        h.remoteResponse = { _, _ -> throw McpTransportClient.McpAuthorizationException("expired test grant", null) }
        h.refreshResponse = {
            h.repository.updateToolEnabled(h.config.id, "issue_write", false)
            JSONObject().put("access_token", "rotated-test-access").put("expires_in", 3600)
        }
        val future = h.start("issue_write", issueArgs())
        h.approvePending()
        h.failure(future)
        assertEquals(1, h.refreshes.get())
        assertEquals("No post-refresh replay may use the revoked tool permission", 1, h.calls.size)
        assertEquals(1, h.sessions.size)
        assertFalse("Definitive 401 plus an unsent replay must not poison the intent",
            h.journal.isUncertain(h.intent("issue_write", issueArgs())))
    }

    @Test fun oauth401RefreshCannotReplayOrOverwriteReplacementAccount() {
        val h = harness(listOf(remote("issue_write")), oauth = true)
        h.enable("issue_write")
        h.remoteResponse = { _, _ -> throw McpTransportClient.McpAuthorizationException("expired test grant", null) }
        h.refreshResponse = {
            h.oauth.saveGitHubDeviceGrant(h.config, "new-account-test-access", null, 0L)
            JSONObject().put("access_token", "stale-test-access").put("expires_in", 3600)
        }
        val future = h.start("issue_write", issueArgs())
        h.approvePending()
        h.failure(future)
        assertEquals(1, h.refreshes.get())
        assertEquals(1, h.calls.size)
        assertEquals(1, h.sessions.size)
        assertEquals("Bearer new-account-test-access", h.oauth.authorizationHeader(h.config))
        assertFalse("Definitive 401 plus an unsent replay must not poison the intent",
            h.journal.isUncertain(h.intent("issue_write", issueArgs())))
    }

    @Test fun oauth401WithUnchangedLeaseRefreshesOnceAndReplaysOnlyDefinitivelyRejectedCall() {
        val h = harness(listOf(remote("issue_write")), oauth = true)
        h.enable("issue_write")
        h.remoteResponse = { _, _ ->
            if (h.calls.size == 1) throw McpTransportClient.McpAuthorizationException("expired test grant", null)
            success("created once")
        }
        h.refreshResponse = { JSONObject().put("access_token", "rotated-test-access").put("expires_in", 3600) }
        h.approved("issue_write", issueArgs())
        assertEquals(1, h.refreshes.get())
        assertEquals(2, h.calls.size)
        assertEquals(2, h.sessions.size)
        assertEquals(listOf("Bearer first-test-access", "Bearer rotated-test-access"), h.sessionHeaders.toList())
        assertFalse(h.journal.isUncertain(h.intent("issue_write", issueArgs())))
    }

    @Test fun refreshedSessionOwnsDynamicDiscoveryAfterOriginalInvocationEndsOrIsCancelled() {
        val h = harness(listOf(remote("issue_write")), oauth = true)
        h.enable("issue_write")
        h.remoteResponse = { _, _ ->
            if (h.calls.size == 1) throw McpTransportClient.McpAuthorizationException("expired test grant", null)
            success("created once")
        }
        h.refreshResponse = { JSONObject().put("access_token", "rotated-test-access").put("expires_in", 3600) }
        val invocation = CancellationToken.cancellable()
        val completed = h.start("issue_write", issueArgs(), invocation)
        h.approvePending()
        completed.get(5, TimeUnit.SECONDS)
        val original = h.sessions.single { it.number == 1 }
        val replacement = h.sessions.single { it.number == 2 }
        assertTrue(original.closed.get())
        assertFalse(replacement.closed.get())

        // A successful invocation has finished, but its replacement session is still active.
        replacement.notifyTools(listOf(remote("issue_write"), remote("get_file_contents", true)))
        var discovered = h.repository.get(h.config.id)!!.tools.associateBy { it.wireName }
        assertTrue("The live refreshed session must keep publishing dynamic discovery", discovered.containsKey("get_file_contents"))
        assertTrue(discovered.getValue("issue_write").enabled)
        assertFalse("New dynamic capabilities remain opt-in", discovered.getValue("get_file_contents").enabled)

        assertTrue(invocation.cancel())
        assertTrue(invocation.isCancelled)
        replacement.notifyTools(listOf(remote("issue_write"), remote("get_file_contents", true), remote("get_commit", true)))
        discovered = h.repository.get(h.config.id)!!.tools.associateBy { it.wireName }
        assertTrue("Long-lived notifications must not inherit a completed invocation's cancellation", discovered.containsKey("get_commit"))
        assertFalse(discovered.getValue("get_commit").enabled)
        val liveCatalog = h.repository.get(h.config.id)!!.tools

        // Closing a session cannot retract an already queued notification. Ownership must reject it.
        original.notifyTools(listOf(remote("list_branches", true)))
        assertEquals("An obsolete original session must not overwrite the replacement's catalog", liveCatalog,
            h.repository.get(h.config.id)!!.tools)
        assertEquals(McpConnectionStatus.READY, h.manager.state(h.config.id).status)
        assertEquals("2", h.manager.state(h.config.id).serverInfo?.version)
        assertFalse(replacement.closed.get())
        h.approved("issue_write", issueArgs().put("title", "Fresh invocation"))
        assertEquals(listOf(1, 2, 2), h.sessionCalls.toList())
    }

    @Test fun pausedRefreshReplacementCannotOverwriteOrCloseANewerReconnectSession() {
        for (disconnectFirst in listOf(false, true)) {
            val h = harness(listOf(remote("issue_write")), oauth = true)
            h.enable("issue_write")
            val replacementReached = CountDownLatch(1)
            val releaseReplacement = CountDownLatch(1)
            h.discoveredForSession = { number ->
                if (number == 3) listOf(remote("issue_write"), remote("get_file_contents", true))
                else listOf(remote("issue_write"))
            }
            h.beforeSessionCreated = { number ->
                if (number == 2) {
                    replacementReached.countDown()
                    check(releaseReplacement.await(5, TimeUnit.SECONDS)) { "Refresh barrier was not released" }
                }
            }
            h.remoteResponse = { _, _ ->
                if (h.calls.size == 1) throw McpTransportClient.McpAuthorizationException("expired test grant", null)
                success("new connection owns this call")
            }
            h.refreshResponse = { JSONObject().put("access_token", "rotated-test-access").put("expires_in", 3600) }
            val args = issueArgs()
            val pending = h.start("issue_write", args)
            h.approvePending()
            try {
                assertTrue("Refresh never reached replacement construction", replacementReached.await(5, TimeUnit.SECONDS))
                if (disconnectFirst) h.manager.disconnect(h.config.id)
                h.connect()
                assertEquals("3", h.manager.state(h.config.id).serverInfo?.version)
                val newest = h.sessions.single { it.number == 3 }
                assertFalse(newest.closed.get())
                releaseReplacement.countDown()
                assertTrue(h.failure(pending) is IllegalArgumentException)
                assertTrue("Superseded replacement must be closed", h.sessions.single { it.number == 2 }.closed.get())
                assertFalse("Old refresh must not close the newer connection", newest.closed.get())
                assertEquals(McpConnectionStatus.READY, h.manager.state(h.config.id).status)
                assertEquals("3", h.manager.state(h.config.id).serverInfo?.version)
                assertTrue("Late stale discovery must not overwrite the newer connection's toolset",
                    h.repository.get(h.config.id)!!.tools.any { it.wireName == "get_file_contents" })
                assertEquals("The rejected original call must not be replayed", listOf(1), h.sessionCalls.toList())
                assertFalse(h.journal.isUncertain(h.intent("issue_write", args)))
                h.approved("issue_write", args)
                assertEquals(listOf(1, 3), h.sessionCalls.toList())
                assertFalse(newest.closed.get())
            } finally { releaseReplacement.countDown() }
        }
    }

    @Test fun ambiguousIoAndMalformedResultsBlockRepeatBeforeAnotherApproval() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        val outcomes: List<() -> JSONObject> = listOf(
            { throw IOException("connection lost after send") },
            { throw McpTransportClient.McpHttpStatusException(408) },
            { throw McpTransportClient.McpHttpStatusException(500) },
            { throw McpTransportClient.McpHttpStatusException(503) },
            { JSONObject() },
            { JSONObject().put("result", JSONObject().put("isError", false)) },
            { success("invalid boolean").apply { getJSONObject("result").put("isError", "false") } },
        )
        outcomes.forEachIndexed { index, outcome ->
            val args = issueArgs().put("title", "Uncertain operation $index")
            h.remoteResponse = { _, _ -> outcome() }
            val future = h.start("issue_write", args)
            h.approvePending()
            val failure = h.failure(future)
            assertTrue(failure.message.orEmpty().contains("unknown"))
            assertTrue(h.journal.isUncertain(h.intent("issue_write", args)))
            val before = h.calls.size
            assertThrows(IllegalStateException::class.java) { h.call("issue_write", args) }
            assertEquals(before, h.calls.size)
            assertTrue(h.presenter.shown.isEmpty())
        }
        assertEquals(outcomes.size, h.calls.size)
    }

    @Test fun mcpIsErrorRetainsUncertaintyAndAddsAnExplicitDoNotRepeatNotice() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        h.remoteResponse = { _, _ -> success("provider reported an error").apply { getJSONObject("result").put("isError", true) } }
        val args = issueArgs()
        val response = h.approved("issue_write", args)
        assertTrue(response.getJSONObject("result").getBoolean("isError"))
        assertTrue(response.toString().contains("Write outcome unconfirmed"))
        assertTrue(h.journal.isUncertain(h.intent("issue_write", args)))
        assertThrows(IllegalStateException::class.java) { h.call("issue_write", args) }
        assertEquals(1, h.calls.size)
        assertTrue(h.presenter.shown.isEmpty())
    }

    @Test fun definitiveHttpRejectionClearsMarkerAndPermitsSeparatelyApprovedRetry() {
        val h = harness(listOf(remote("issue_write")))
        h.enable("issue_write")
        for (status in listOf(400, 403, 404, 409, 422, 429)) {
            val args = issueArgs().put("title", "Rejected $status")
            h.remoteResponse = { _, _ -> throw McpTransportClient.McpHttpStatusException(status) }
            val future = h.start("issue_write", args)
            h.approvePending()
            assertTrue(h.failure(future) is McpTransportClient.McpHttpStatusException)
            assertFalse("HTTP $status is a definitive rejection", h.journal.isUncertain(h.intent("issue_write", args)))
            h.remoteResponse = { _, _ -> success("accepted after explicit retry") }
            h.approved("issue_write", args)
            assertFalse(h.journal.isUncertain(h.intent("issue_write", args)))
        }
        assertEquals(12, h.calls.size)
    }

    @Test fun missingScopeRejectionDoesNotRefreshOrPoisonIntent() {
        val h = harness(listOf(remote("issue_write")), oauth = true)
        h.enable("issue_write")
        h.remoteResponse = { _, _ -> throw McpPermissionRequiredException("Missing repository permission", setOf("repo")) }
        val future = h.start("issue_write", issueArgs())
        h.approvePending()
        assertTrue(h.failure(future) is McpPermissionRequiredException)
        assertEquals(McpConnectionStatus.PERMISSION_REQUIRED, h.manager.state(h.config.id).status)
        assertEquals(0, h.refreshes.get())
        assertFalse(h.journal.isUncertain(h.intent("issue_write", issueArgs())))
        h.remoteResponse = { _, _ -> success("retry after permission restored") }
        h.approved("issue_write", issueArgs())
        assertEquals(2, h.calls.size)
    }

    @Test fun nativePreflightPermissionFailureNeverReservesAnUnsentMutation() {
        val h = harness(emptyList())
        h.enable(GitHubNativeBridge.COMMIT_FILES)
        h.nativeResponse = { GitHubNativeResponse(403, "") }
        val args = commitArgs()
        val future = h.start(GitHubNativeBridge.COMMIT_FILES, args)
        h.approvePending()
        val failure = h.failure(future)
        assertTrue(failure is com.jarvys.agent.connectors.GitHubNativeException)
        assertEquals(com.jarvys.agent.connectors.GitHubNativeFailure.FORBIDDEN,
            (failure as com.jarvys.agent.connectors.GitHubNativeException).reason)
        assertFalse(h.journal.isUncertain(h.intent(GitHubNativeBridge.COMMIT_FILES, args)))
        assertEquals(1, h.nativeRequests.size)
        assertFalse(h.nativeRequests.single().mutation)
        assertTrue(h.calls.isEmpty())
        // A new explicit approval is allowed; the prior unsent mutation did not poison its intent.
        val retry = h.start(GitHubNativeBridge.COMMIT_FILES, args)
        val (id, _) = h.awaitApproval()
        h.gate.resolve(id, ApprovalDecision.DENIED)
        assertTrue(h.failure(retry) is McpWriteDeniedException)
        assertEquals(1, h.nativeRequests.size)
    }

    @Test fun discoveryKeepsNewToolsDisabledAndPreservesChoicesAcrossDisappearanceAndReload() {
        val chosen = remote("issue_read", true)
        val denied = remote("issue_write")
        val h = harness(listOf(chosen, denied))
        assertTrue(h.repository.get(h.config.id)!!.tools.all { !it.enabled })
        h.enable("issue_read")
        h.enable("issue_write")
        h.repository.updateToolEnabled(h.config.id, "issue_write", false)
        h.discover(emptyList())
        assertFalse(h.repository.get(h.config.id)!!.tools.any { it.wireName == "issue_read" })
        h.discover(listOf(chosen, denied, remote("get_file_contents", true)))
        val saved = h.repository.get(h.config.id)!!.tools.associateBy { it.wireName }
        assertTrue(saved.getValue("issue_read").enabled)
        assertFalse(saved.getValue("issue_write").enabled)
        assertFalse(saved.getValue("get_file_contents").enabled)
        assertTrue(saved.values.filter { it.wireName.startsWith("jarvys_") }.all { !it.enabled })
        val reloaded = McpServerRepository(context, h.vault).get(h.config.id)!!
        assertEquals("READ:true", reloaded.githubToolPreferences["issue_read"])
        assertEquals("WRITE:false", reloaded.githubToolPreferences["issue_write"])
        assertTrue(reloaded.tools.single { it.wireName == "issue_read" }.enabled)
    }

    @Test fun capabilityFilteringAndUntrustedAnnotationsCannotAuthorizeHiddenOrUnreviewedTools() {
        val h = harness(listOf(remote("actions_run_trigger", true), remote("new_unreviewed_action", true), remote("issue_read", true)))
        h.enable("actions_run_trigger", "new_unreviewed_action", "issue_read")
        assertThrows(IllegalArgumentException::class.java) {
            h.call("actions_run_trigger", target().put("method", "run_workflow"))
        }
        assertThrows(IllegalArgumentException::class.java) { h.call("new_unreviewed_action", target()) }
        assertThrows(IllegalArgumentException::class.java) { h.call("issue_read", target().put("method", "update")) }
        assertTrue(h.presenter.shown.isEmpty())
        assertTrue(h.calls.isEmpty())
    }

    @Test fun scriptedIssueFileBranchAtomicCommitDraftPrAndExactShaChecksUseRealManagerBoundaries() {
        val h = harness(listOf(remote("issue_read", true), remote("get_file_contents", true),
            remote("create_branch"), remote("create_pull_request")))
        h.enable("issue_read", "get_file_contents", "create_branch", "create_pull_request",
            GitHubNativeBridge.COMMIT_FILES, GitHubNativeBridge.COMMIT_CHECKS)
        val steps = CopyOnWriteArrayList<String>()
        h.remoteResponse = { name, args ->
            steps += name
            when (name) {
                "issue_read" -> { assertEquals("get", args.getString("method")); success("Issue 7: update greeting") }
                "get_file_contents" -> { assertEquals("src/greeting.txt", args.getString("path")); success("old greeting") }
                "create_branch" -> { assertEquals("main", args.getString("from_branch")); success(base) }
                "create_pull_request" -> { assertTrue(args.getBoolean("draft")); success("https://github.com/octo/repo/pull/8") }
                else -> error("Unexpected MCP operation $name")
            }
        }
        h.nativeResponse = { request ->
            steps += request.operation
            val variables = JSONObject(request.bodyJson).getJSONObject("variables")
            when (request.operation) {
                "JarvysBranchIdentity" -> {
                    assertFalse(request.mutation)
                    assertEquals("refs/heads/feature/review", variables.getString("refName"))
                    graph(repoJson().put("ref", refJson().put("target", JSONObject().put("__typename", "Commit").put("oid", base))))
                }
                "createCommitOnBranch" -> {
                    assertTrue(request.mutation)
                    assertTrue(h.journal.isUncertain(h.intent(GitHubNativeBridge.COMMIT_FILES, commitArgs())))
                    val input = variables.getJSONObject("input")
                    assertEquals(base, input.getString("expectedHeadOid"))
                    assertFalse(input.has("force"))
                    val changes = input.getJSONObject("fileChanges")
                    assertEquals(2, changes.getJSONArray("additions").length())
                    assertEquals(0, changes.getJSONArray("deletions").length())
                    graphData(JSONObject().put("createCommitOnBranch", JSONObject().put("ref", refJson())
                        .put("commit", JSONObject().put("id", "C_1").put("oid", head)
                            .put("url", "https://github.com/octo/repo/commit/$head").put("repository", repoJson())
                            .put("parents", JSONObject().put("nodes", JSONArray().put(JSONObject().put("oid", base)))))))
                }
                "JarvysCommitChecks" -> {
                    assertFalse(request.mutation)
                    assertEquals(head, variables.getString("sha"))
                    assertFalse(variables.has("branch"))
                    graph(repoJson().put("object", JSONObject().put("id", "C_1").put("__typename", "Commit").put("oid", head)
                        .put("url", "https://github.com/octo/repo/commit/$head").put("repository", repoJson())
                        .put("statusCheckRollup", JSONObject.NULL)))
                }
                else -> error("Unexpected native operation ${request.operation}")
            }
        }
        h.call("issue_read", target().put("method", "get").put("issue_number", 7))
        h.call("get_file_contents", target().put("path", "src/greeting.txt").put("ref", "main"))
        assertTrue(h.presenter.shown.isEmpty())
        h.approved("create_branch", target().put("branch", "feature/review").put("from_branch", "main"))
        val commit = h.approved(GitHubNativeBridge.COMMIT_FILES, commitArgs())
            .getJSONObject("result").getJSONObject("structuredContent")
        assertEquals(head, commit.getString("sha"))
        assertTrue(commit.getBoolean("atomicPrecondition"))
        h.approved("create_pull_request", prArgs())
        val checks = h.call(GitHubNativeBridge.COMMIT_CHECKS, target().put("sha", commit.getString("sha")))
            .getJSONObject("result").getJSONObject("structuredContent")
        assertEquals(head, checks.getString("sha"))
        assertTrue(checks.isNull("rollupState"))
        assertTrue(checks.getString("notice").contains("not evidence of success"))
        assertTrue(h.presenter.shown.isEmpty())
        assertEquals(listOf("issue_read", "get_file_contents", "create_branch", "JarvysBranchIdentity",
            "createCommitOnBranch", "create_pull_request", "JarvysCommitChecks"), steps.toList())
        assertEquals(1, h.nativeRequests.count { it.mutation })
        assertFalse(h.journal.isUncertain(h.intent(GitHubNativeBridge.COMMIT_FILES, commitArgs())))
    }

    private fun remote(name: String, readOnly: Boolean = false) = McpToolConfig(name, name,
        "Untrusted test metadata", JSONObject().put("type", "object").put("properties", JSONObject().apply {
            if (name == "merge_pull_request") put("expectedHeadSha", JSONObject().put("type", "string"))
        }).toString(), annotations = McpToolAnnotations(readOnlyHint = readOnly))
    private fun target() = JSONObject().put("owner", "octo").put("repo", "repo")
    private fun issueArgs() = target().put("method", "create").put("title", "Reviewed title").put("body", "Reviewed body")
    private fun prArgs() = target().put("title", "Reviewed PR").put("head", "feature/review").put("base", "main").put("body", "Fixes #7")
    private fun commitArgs() = target().put("branch", "feature/review").put("expectedHeadOid", base).put("message", "Update greeting")
        .put("additions", JSONArray().put(JSONObject().put("path", "src/greeting.txt").put("content", "hello\n"))
            .put(JSONObject().put("path", "Pending.md").put("content", "Greeting reviewed\n")))
        .put("deletions", JSONArray())
    private fun success(text: String) = JSONObject().put("result", JSONObject().put("isError", false)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
    private fun repoJson() = JSONObject().put("id", "R_1").put("nameWithOwner", "octo/repo").put("url", "https://github.com/octo/repo")
    private fun refJson() = JSONObject().put("id", "REF_1").put("name", "feature/review").put("prefix", "refs/heads/")
    private fun graph(repository: JSONObject) = graphData(JSONObject().put("repository", repository))
    private fun graphData(data: JSONObject) = GitHubNativeResponse(200, JSONObject().put("data", data).toString())

    private fun harness(tools: List<McpToolConfig>, oauth: Boolean = false,
                        groups: Set<String> = GitHubOperationPolicy.defaultToolsets): Harness =
        Harness(tools, oauth, groups).also { fixtures += it; it.connect() }

    private inner class Harness(discovered: List<McpToolConfig>, useOAuth: Boolean, groups: Set<String>) : AutoCloseable {
        val vault = TestVault()
        val repository = McpServerRepository(context, vault)
        val config = McpServerConfig(id = "github-integration-${fixtures.size}", alias = "GitHub",
            endpoint = GitHubOperationPolicy.ENDPOINT, catalogServiceId = "github",
            authMode = if (useOAuth) McpAuthMode.OAUTH else McpAuthMode.BEARER,
            oauthClientId = GitHubDeviceFlowProtocol.CLIENT_ID, githubToolsets = groups)
        val presenter = Presenter()
        val gate = ApprovalGate(10_000, presenter)
        val store = InMemoryConnectorAutonomyStore()
        val approval = McpWriteApprovalCoordinator(store, gate, object : AutonomyActionNotifier {
            override fun canPost() = true
            override fun missingRuntimePermission(): String? = null
            override fun post(record: AutonomyAuditRecord) = Unit
        })
        val journal = GitHubWriteJournals.inMemory()
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val sessions = CopyOnWriteArrayList<FakeSession>()
        val sessionHeaders = CopyOnWriteArrayList<String>()
        val sessionCalls = CopyOnWriteArrayList<Int>()
        private val connections = AtomicInteger()
        var beforeSessionCreated: (Int) -> Unit = {}
        var discoveredForSession: (Int) -> List<McpToolConfig> = { discovered }
        val nativeRequests = CopyOnWriteArrayList<GitHubNativeRequest>()
        val refreshes = AtomicInteger()
        var remoteResponse: (String, JSONObject) -> JSONObject = { _, _ -> success("done") }
        var nativeResponse: (GitHubNativeRequest) -> GitHubNativeResponse = { error("Unexpected native request ${it.operation}") }
        var refreshResponse: () -> JSONObject = { error("Unexpected OAuth refresh") }
        private lateinit var discovery: (List<McpToolConfig>) -> Unit
        private val worker = Executors.newSingleThreadExecutor()
        val oauth = McpOAuthManager(repository, { now }) { endpoint, _ ->
            assertEquals(GitHubDeviceFlowProtocol.TOKEN_ENDPOINT, endpoint)
            refreshes.incrementAndGet()
            refreshResponse()
        }
        val manager: McpConnectionManager
        init {
            repository.upsert(config)
            if (useOAuth) oauth.saveGitHubDeviceGrant(config, GitHubOAuthTokens("first-test-access", "test-refresh",
                now + 600_000, now + 3_600_000, setOf("repo", "read:user")))
            else repository.saveBearerToken(config.id, config.endpoint, "test-bearer")
            val bridge = GitHubNativeBridge(GitHubNativeTransport { request, header, token ->
                token.throwIfCancelled()
                assertTrue(header.startsWith("Bearer "))
                nativeRequests += request
                nativeResponse(request)
            }, { now }, { error("Unexpected native retry wait") })
            manager = McpConnectionManager(repository, oauth, approval, bridge, journal) { current, header, callback ->
                assertEquals(config.endpoint, current.endpoint)
                sessionHeaders += requireNotNull(header)
                val number = connections.incrementAndGet()
                beforeSessionCreated(number)
                discovery = callback
                val sessionTools = discoveredForSession(number)
                callback(sessionTools)
                FakeSession(sessionTools, number, callback).also { sessions += it }
            }
        }
        fun connect() {
            manager.connect(config.id)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (manager.state(config.id).status == McpConnectionStatus.CONNECTING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(manager.state(config.id).message, McpConnectionStatus.READY, manager.state(config.id).status)
        }
        fun enable(vararg names: String) = names.forEach { name ->
            check(repository.get(config.id)!!.tools.any { it.wireName == name }) { "Undiscovered fixture tool $name" }
            repository.updateToolEnabled(config.id, name, true)
        }
        fun discover(tools: List<McpToolConfig>) = discovery(tools)
        fun call(name: String, args: JSONObject): JSONObject = manager.callTool(config.id, name, args, CancellationToken.uncancellable())
        fun start(name: String, args: JSONObject, token: CancellationToken = CancellationToken.uncancellable()): Future<JSONObject> =
            worker.submit<JSONObject> { manager.callTool(config.id, name, args, token) }
        fun awaitApproval(): Pair<String, ApprovalSummary> = presenter.shown.poll(5, TimeUnit.SECONDS)
            ?: error("Expected explicit GitHub approval, but none was presented")
        fun approvePending() { val (id, _) = awaitApproval(); gate.resolve(id, ApprovalDecision.APPROVED) }
        fun approved(name: String, args: JSONObject): JSONObject {
            val future = start(name, args)
            approvePending()
            return future.get(5, TimeUnit.SECONDS)
        }
        fun failure(future: Future<JSONObject>): Throwable =
            assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }.cause!!
        fun intent(name: String, args: JSONObject) = GitHubOperationPolicy.intentHash(config, name, args)
        override fun close() {
            worker.shutdownNow()
            assertTrue("Test operation did not stop", worker.awaitTermination(5, TimeUnit.SECONDS))
            manager.close()
        }
        inner class FakeSession(override val tools: List<McpToolConfig>, val number: Int,
                                private val onToolsChanged: (List<McpToolConfig>) -> Unit) : McpProtocolSession {
            val closed = AtomicBoolean()
            override val serverInfo = McpServerInfo("simulated-github", number.toString(), null)
            override fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject {
                token.throwIfCancelled()
                check(!closed.get()) { "A closed fixture session was used" }
                sessionCalls += number
                calls += name to JSONObject(arguments.toString())
                return remoteResponse(name, arguments)
            }
            fun notifyTools(tools: List<McpToolConfig>) = onToolsChanged(tools)
            override fun close() { closed.set(true) }
        }
    }

    private class Presenter : ApprovalPresenter {
        val shown = LinkedBlockingQueue<Pair<String, ApprovalSummary>>()
        override fun show(id: String, summary: ApprovalSummary) { shown.put(id to summary) }
        override fun update(id: String, decision: ApprovalDecision) = Unit
    }
    private class TestVault : McpCredentialVault {
        private val values = ConcurrentHashMap<Pair<String, String>, String>()
        override fun get(serverId: String, key: String) = values[serverId to key]
        override fun save(serverId: String, key: String, value: String) { values[serverId to key] = value }
        override fun clear(serverId: String) { values.keys.removeAll { it.first == serverId } }
    }
}
