package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real bounded provider readers, synthetic streams only; no requests leave this process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProviderTimeoutTransportTest {
    @Test public void genericSocketTimeoutIsTransportFailureWithoutStopOrReplay() throws Exception {
        exercise(false, false, false);
    }
    @Test public void codexSocketTimeoutIsTransportFailureWithoutStopOrReplay() throws Exception {
        exercise(true, false, false);
    }
    @Test public void genericInterruptedIoWithoutStopIsNotUserCancellation() throws Exception {
        exercise(false, false, true);
    }
    @Test public void codexInterruptedIoWithoutStopIsNotUserCancellation() throws Exception {
        exercise(true, false, true);
    }
    @Test public void genericGenuineStopKeepsCancellationIdentity() throws Exception {
        exercise(false, true, false);
    }
    @Test public void codexGenuineStopKeepsCancellationIdentity() throws Exception {
        exercise(true, true, false);
    }

    private void exercise(boolean codex, boolean stopped, boolean interruptedIo) throws Exception {
        assertFalse(Thread.currentThread().isInterrupted());
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger calls = new AtomicInteger();
        IOException timeout = interruptedIo ? new InterruptedIOException("synthetic read deadline")
                : new SocketTimeoutException("synthetic read deadline");
        FakeConnection connection = new FakeConnection(timeout, stopped ? token::cancel : () -> { });
        ProviderHttp.ConnectionFactory factory = endpoint -> { calls.incrementAndGet(); return connection; };
        try {
            if (codex) {
                Context context = ApplicationProvider.getApplicationContext();
                ProviderSettings settings = new ProviderSettings(context);
                settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
                SecretStore secrets = new SecretStore(context.getSharedPreferences("ux38-synthetic-credentials", Context.MODE_PRIVATE));
                secrets.saveCodexTokens("SYNTHETIC_CODEX_TOKEN", "SYNTHETIC_REFRESH_TOKEN", Long.MAX_VALUE, "SYNTHETIC_ACCOUNT");
                OpenAICodexResponsesClient client = new OpenAICodexResponsesClient(settings,
                        (refresh, ignored) -> secrets.getCodexCredentials(), factory);
                client.completeConversation("synthetic system", Collections.emptyList(), "synthetic user",
                        Collections.emptyList(), "ux38-timeout", token);
            } else {
                ProviderHttp.post("https://fixture.invalid/never-opened", Collections.emptyMap(),
                        new byte[] { 1 }, token, false, factory);
            }
            fail("Expected a bounded request failure");
        } catch (ProviderTransportException failure) {
            assertFalse("A true STOP must not become transport failure", stopped);
            assertSame(timeout, failure.getCause());
        } catch (CancellationException failure) {
            assertTrue("A socket timeout must not manufacture user STOP", stopped);
        } finally {
            assertEquals("No automatic request replay", 1, calls.get());
            assertTrue(connection.disconnected);
            assertEquals(stopped, token.isCancellationRequested());
            assertFalse("Reader must not manufacture interrupt", Thread.currentThread().isInterrupted());
            assertTrue(connection.getConnectTimeout() > 0);
            assertTrue(connection.getReadTimeout() > 0);
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final IOException failure;
        final Runnable beforeFailure;
        boolean disconnected;
        FakeConnection(IOException failure, Runnable beforeFailure) throws Exception {
            super(new URL("https://fixture.invalid/never-opened"));
            this.failure = failure; this.beforeFailure = beforeFailure;
        }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() { return 200; }
        @Override public InputStream getInputStream() {
            return new InputStream() { @Override public int read() throws IOException {
                beforeFailure.run(); throw failure;
            } };
        }
    }
}
