package com.jarvys.agent;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Fake sockets only; no account access, live provider or rollout activation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SealedInferenceTransportTest {
    private static ProviderAttemptLease lease(CancellationToken token) {
        return new ProviderAttemptLease("central", "run", "attempt",
                new CoreAgentLoop.Checkpoint(Collections.emptyList(), Collections.emptySet(), Collections.emptyMap(), -1), token);
    }
    private static final class Connection extends HttpURLConnection {
        final byte[] response;
        final int status;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final AtomicReference<Thread> networkThread = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        final boolean blocked;
        Connection(String response, int status, boolean blocked) throws Exception {
            super(new URL("https://example.invalid/inference"));
            this.response = response.getBytes(StandardCharsets.UTF_8); this.status = status; this.blocked = blocked;
        }
        @Override public OutputStream getOutputStream() { networkThread.set(Thread.currentThread()); return written; }
        @Override public int getResponseCode() {
            entered.countDown();
            if (blocked) {
                boolean interrupted = false;
                while (true) try { release.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
            returned.countDown(); return status;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public void disconnect() { /* Deliberately cannot unblock the synthetic remote operation. */ }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
    }
    private static SealedInferenceTransport.Request request(Connection connection, byte[] body,
            SealedInferenceTransport.Framing framing) {
        return new SealedInferenceTransport.Request("https://example.invalid/inference",
                Collections.singletonMap("Authorization", "synthetic"), body, false, framing,
                8 * 1024 * 1024, true, endpoint -> connection);
    }

    @Test public void immutableBytesAndNativeAdmissionRemainOnOwnerWhileOnlySocketMoves() throws Exception {
        Thread owner = Thread.currentThread();
        Connection connection = new Connection("{\"ok\":true}", 200, false);
        byte[] body = "original".getBytes(StandardCharsets.UTF_8);
        SealedInferenceTransport.Request request = request(connection, body, SealedInferenceTransport.Framing.JSON);
        Arrays.fill(body, (byte) 'x');
        AtomicInteger admitted = new AtomicInteger();
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease lease = lease(token);
             NativeEffectDispatchScope scope = NativeEffectDispatchScope.enter(() -> {
                 assertSame(owner, Thread.currentThread()); admitted.incrementAndGet();
             })) {
            assertEquals(200, SealedInferenceTransport.exchange(lease, request, token, null).status);
            assertEquals(ProviderAttemptLease.Stage.PREPARING, lease.stage());
            assertTrue(lease.tryCommitReply());
        }
        assertEquals("original", connection.written.toString("UTF-8"));
        assertEquals(1, admitted.get());
        assertNotSame(owner, connection.networkThread.get());
        assertFalse(token.isCancellationRequested());
    }

    @Test public void sseDeltasAreDeliveredOnlyOnOwnerAndNotAsToolArguments() throws Exception {
        Thread owner = Thread.currentThread();
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"private args\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        Connection connection = new Connection(sse, 200, false);
        List<String> deltas = new ArrayList<>();
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease lease = lease(token)) {
            SealedInferenceTransport.exchange(lease, request(connection, new byte[0], SealedInferenceTransport.Framing.CODEX_SSE), token,
                    delta -> { assertSame(owner, Thread.currentThread()); deltas.add(delta); });
        }
        assertEquals(Collections.singletonList("hello"), deltas);
    }

    @Test public void supersededBlockedSocketCannotRetainOwnerOrCancelItsChildren() throws Exception {
        Connection connection = new Connection("late", 200, true);
        CancellationToken ownerToken = CancellationToken.cancellable();
        AtomicInteger childStops = new AtomicInteger();
        ownerToken.registerCancelAction(childStops::incrementAndGet);
        AtomicReference<ProviderAttemptLease> active = new AtomicReference<>();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> outcome = owner.submit(() -> {
                try (ProviderAttemptLease lease = lease(ownerToken)) {
                    active.set(lease);
                    try {
                        SealedInferenceTransport.exchange(lease, request(connection, new byte[0], SealedInferenceTransport.Framing.JSON), ownerToken, null);
                        return false;
                    } catch (ProviderAttemptLease.YieldRequested expected) { return true; }
                }
            });
            assertTrue(connection.entered.await(10, TimeUnit.SECONDS));
            active.get().requestYield();
            assertTrue(outcome.get(10, TimeUnit.SECONDS));
            assertEquals(1L, connection.returned.getCount());
            assertFalse(ownerToken.isCancellationRequested());
            assertEquals(0, childStops.get());
        } finally {
            connection.release.countDown();
            assertTrue(connection.returned.await(10, TimeUnit.SECONDS));
            owner.shutdownNow();
        }
    }

    @Test public void ordinaryEffectHttpNeverUsesTheInferenceExecutorEvenInsideLease() throws Exception {
        Thread owner = Thread.currentThread();
        Connection connection = new Connection("ok", 200, false);
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease lease = lease(token)) {
            ProviderHttp.post("https://example.invalid/effect", Collections.emptyMap(), new byte[0], token, false, endpoint -> connection);
            assertEquals(ProviderAttemptLease.Stage.PREPARING, lease.stage());
        }
        assertSame(owner, connection.networkThread.get());
    }

    @Test public void codexCredentialsAndOne401RefreshStayOnOwnerBetweenSealedAttempts() throws Exception {
        Thread owner = Thread.currentThread();
        Context context = ApplicationProvider.getApplicationContext();
        ProviderSettings settings = new ProviderSettings(context);
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        SecretStore secrets = new SecretStore(context.getSharedPreferences("sealed-inference-test", Context.MODE_PRIVATE));
        secrets.saveCodexTokens("synthetic", "synthetic-refresh", Long.MAX_VALUE, "synthetic-account");
        Connection denied = new Connection("{}", 401, false);
        Connection success = new Connection("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}", 200, false);
        List<Boolean> refreshes = new ArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        OpenAICodexResponsesClient client = new OpenAICodexResponsesClient(settings, (refresh, token) -> {
            assertSame(owner, Thread.currentThread());
            assertEquals(ProviderAttemptLease.Stage.PREPARING, ProviderAttemptLease.current().stage());
            refreshes.add(refresh); return secrets.getCodexCredentials();
        }, endpoint -> requests.getAndIncrement() == 0 ? denied : success);
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease lease = lease(token)) {
            ModelReply reply = client.completeConversation("instructions", Collections.emptyList(), "request",
                    Collections.emptyList(), Collections.emptyList(), "central", token);
            assertEquals("done", reply.text);
            assertEquals(ProviderAttemptLease.Stage.PREPARING, lease.stage());
        }
        assertEquals(Arrays.asList(false, true), refreshes);
        assertEquals(2, requests.get());
        assertNotSame(owner, denied.networkThread.get());
        assertNotSame(owner, success.networkThread.get());
    }
    @Test public void late401FromSupersededTransportCannotRefreshCredentials() throws Exception {
        Connection connection = new Connection("{}", 401, true);
        AtomicInteger credentialCalls = new AtomicInteger(), sends = new AtomicInteger();
        AtomicReference<ProviderAttemptLease> active = new AtomicReference<>();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CancellationToken token = CancellationToken.cancellable();
        try {
            Future<Boolean> outcome = owner.submit(() -> {
                try (ProviderAttemptLease lease = lease(token)) {
                    active.set(lease);
                    try {
                        CodexAuthenticatedRequestExecutor.execute(new org.json.JSONObject(), "central", token, "synthetic",
                            (refresh, current) -> { assertFalse(refresh); credentialCalls.incrementAndGet(); return new Object(); },
                            (payload, credentials, session, current) -> {
                                sends.incrementAndGet();
                                return SealedInferenceTransport.exchange(lease,
                                    request(connection, new byte[0], SealedInferenceTransport.Framing.JSON), current, null);
                            });
                        return false;
                    } catch (ProviderAttemptLease.YieldRequested expected) { return true; }
                }
            });
            assertTrue(connection.entered.await(10, TimeUnit.SECONDS));
            active.get().requestYield();
            assertTrue(outcome.get(10, TimeUnit.SECONDS));
            connection.release.countDown();
            assertTrue(connection.returned.await(10, TimeUnit.SECONDS));
            assertEquals(1, credentialCalls.get()); assertEquals(1, sends.get());
            assertFalse(token.isCancellationRequested());
        } finally { connection.release.countDown(); owner.shutdownNow(); }
    }

    @Test public void openRouterAdapterPreservesTypedYieldInsteadOfWrappingAsProviderFailure() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ProviderSettings settings = new ProviderSettings(context);
        settings.setProvider(ProviderSettings.Provider.OPENROUTER);
        SecretStore secrets = new SecretStore(context.getSharedPreferences("sealed-openrouter-test", Context.MODE_PRIVATE));
        secrets.saveOpenRouterKey("synthetic");
        Connection connection = new Connection("late", 200, true);
        OpenRouterClient client = new OpenRouterClient(secrets, settings,
                com.jarvys.agent.providers.ChatCompletionsConfig.openRouterDefault(), endpoint -> connection);
        AtomicReference<ProviderAttemptLease> active = new AtomicReference<>();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CancellationToken token = CancellationToken.cancellable();
        try {
            Future<Boolean> outcome = owner.submit(() -> {
                try (ProviderAttemptLease lease = lease(token)) {
                    active.set(lease);
                    try {
                        client.completeConversation("instructions", Collections.emptyList(), "request",
                                Collections.emptyList(), Collections.emptyList(), "central", token);
                        return false;
                    } catch (ProviderAttemptLease.YieldRequested expected) { return true; }
                }
            });
            assertTrue(connection.entered.await(10, TimeUnit.SECONDS));
            active.get().requestYield();
            assertTrue(outcome.get(10, TimeUnit.SECONDS));
            assertFalse(token.isCancellationRequested());
        } finally { connection.release.countDown(); owner.shutdownNow(); }
    }

}
