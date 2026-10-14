package com.jarvys.factory.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static com.jarvys.factory.runtime.NativeOperationSessionsTest.idle;
import static org.junit.Assert.*;

/** Real policy leases and native Job wiring; synthetic vault/HTTP only, never live credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeSecretEffectsTest {
    private NativeOperationSessionsTest.Fixture fixture;
    private FactoryConfig config;
    private NativeEffects effects;
    private final SecretEffectsTest.Vault vault = new SecretEffectsTest.Vault();
    private final SecretEffectsTest.Queue queue = new SecretEffectsTest.Queue();
    private final NativeNetworkInjectionTest.Transport transport = new NativeNetworkInjectionTest.Transport();
    private int opened, sequence;

    @Before public void setup() throws Exception {
        fixture = new NativeOperationSessionsTest.Fixture();
        config = FactoryConfig.parse(new JSONObject(FactoryDispatcherTest.configuration("[\"secrets\",\"network\"]"))
                .put("appId", fixture.config.appId).put("documentBroker", new JSONObject()
                        .put("packageName", fixture.config.documentBroker.packageName)
                        .put("certificateSha256", fixture.config.documentBroker.certificateSha256)).toString(), fixture.config.appId);
        effects = new NativeEffects(fixture.screen, config, fixture.sessions, fixture.io, true);
    }
    @After public void finish() throws Exception {
        effects.close();
        while (!queue.values.isEmpty()) queue.next();
        idle(); fixture.close();
    }
    private void installSyntheticAdapter() {
        NativeSecrets storage = new NativeSecrets(vault, vault, config.appId, NativeSecrets.Limits.defaults(), new SecureRandom());
        SecretEffects adapter = new SecretEffects(fixture.screen, NativeSecrets.Limits.defaults(),
                () -> { opened++; return storage; }, NativeNetworkInjectionTest.network(transport), queue, new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(effects, "secrets", adapter);
    }
    private Result perform(String method, JSONObject args) throws Exception {
        Result result = new Result();
        effects.perform(request(method, args), result);
        return result;
    }
    private BridgeProtocol.Request request(String method, JSONObject args) throws Exception {
        return BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, new JSONObject().put("v", 1)
                .put("id", "secret_" + ++sequence).put("method", method).put("args", args).toString(), config);
    }
    private JSONObject put() throws Exception { return SecretEffectsPreparedTest.metadata().put("value", SecretEffectsTest.SYNTHETIC); }
    private Map<?, ?> jobs() { return ReflectionHelpers.getField(effects, "jobs"); }
    private Object job() { assertEquals(1, jobs().size()); return jobs().values().iterator().next(); }
    private NativeOperationSessions.Lease lease() { return ReflectionHelpers.getField(job(), "lease"); }
    /** Capture the immutable input owner while authorization is still queued, before Handle takeover. */
    private SecretEffects.Prepared prepared() throws Exception {
        Runnable cancellation = ReflectionHelpers.getField(job(), "cancellation");
        assertNotNull("Put cleanup must be installed before authorization", cancellation);
        for (Field field : cancellation.getClass().getDeclaredFields()) {
            if (field.getType() == SecretEffects.Prepared.class) { field.setAccessible(true); return (SecretEffects.Prepared) field.get(cancellation); }
        }
        throw new AssertionError("Initial cancellation must own the prepared secret input");
    }
    private byte[] nativeInput(SecretEffects.Prepared prepared) {
        SecretRequest.Put put = ReflectionHelpers.getField(prepared, "put");
        return ReflectionHelpers.getField(put, "value");
    }
    private void assertReleased(SecretEffects.Prepared prepared, byte[] previous) throws Exception {
        assertNull(nativeInput(prepared)); assertArrayEquals(new byte[previous.length], previous);
        SecretEffectsPreparedTest.error("CANCELLED", prepared::authorizationDetails);
    }
    private void work() throws Exception { fixture.pump(); queue.next(); idle(); }

    @Test public void unavailableInstalledSourceReleasesNativeInputWithoutConstructingVaultOrAdapter() throws Exception {
        fixture.provider.unavailable = true;
        Result result = perform("secrets.put", put());
        SecretEffects.Prepared prepared = prepared(); byte[] bytes = nativeInput(prepared);
        assertTrue(bytes.length > 0); assertNull(ReflectionHelpers.getField(effects, "secrets"));
        fixture.pump();
        assertEquals("UNAVAILABLE", result.error); assertReleased(prepared, bytes);
        assertTrue(jobs().isEmpty()); assertNull(ReflectionHelpers.getField(effects, "secrets"));
        assertEquals(0, result.claims); assertTrue(fixture.screen.launched.isEmpty());
    }

    @Test public void approvedCaptureLazilyCreatesAdapterWithDeclaredInputBudget() throws Exception {
        effects.close();
        config = FactoryConfig.parse(new JSONObject(FactoryDispatcherTest.configuration("[\"secrets\",\"network\"]"))
                .put("appId", fixture.config.appId).put("documentBroker", new JSONObject()
                        .put("packageName", fixture.config.documentBroker.packageName)
                        .put("certificateSha256", fixture.config.documentBroker.certificateSha256))
                .put("resourceBudgets", new JSONObject().put("version", 1).put("secrets", new JSONObject()
                        .put("entries", 2).put("valueBytes", 16).put("vaultBytes", 16384))).toString(), fixture.config.appId);
        effects = new NativeEffects(fixture.screen, config, fixture.sessions, fixture.io, true);
        perform("secrets.capture", SecretEffectsPreparedTest.metadata());
        assertNull(ReflectionHelpers.getField(effects, "secrets"));
        fixture.pump();
        SecretEffects adapter = ReflectionHelpers.getField(effects, "secrets"); assertNotNull(adapter);
        assertEquals(16, (int) ReflectionHelpers.getField(adapter, "maximumInputChars"));
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        // No Save or worker task: this verifies admission/UI only, never AndroidKeyStore.
    }

    @Test public void pauseBeforeSessionReplyReleasesPutAndPreventsLateAdmission() throws Exception {
        Result result = perform("secrets.put", put());
        SecretEffects.Prepared prepared = prepared(); byte[] bytes = nativeInput(prepared);
        effects.onPause(); fixture.sessions.onPause(); fixture.pump();
        assertReleased(prepared, bytes); assertEquals("CANCELLED", result.error);
        assertTrue(jobs().isEmpty()); assertNull(ReflectionHelpers.getField(effects, "secrets"));
        assertEquals(0, result.successes);
    }

    @Test public void putUsesImmutableSnapshotThenExplicitGetReceivesItsOwnLease() throws Exception {
        installSyntheticAdapter();
        BridgeProtocol.Request request = request("secrets.put", put()); Result saved = new Result(); effects.perform(request, saved);
        NativeOperationSessions.Lease putLease = lease();
        assertFalse(putLease.request.canonicalJson().contains(SecretEffectsTest.SYNTHETIC));
        request.args.put("value", "changed-after-policy-binding"); request.args.getJSONObject("scope").put("origin", "https://changed.example.test");
        work();
        JSONObject metadata = (JSONObject) saved.value;
        assertEquals("https://api.example.test", metadata.getJSONObject("scope").getString("origin"));
        assertEquals(0, saved.claims); assertEquals(1, saved.successes); assertTrue(jobs().isEmpty());
        Result read = perform("secrets.get", new JSONObject().put("id", metadata.getString("id"))); work();
        assertEquals(SecretEffectsTest.SYNTHETIC, read.value); assertEquals(2, fixture.provider.issued.size());
        assertEquals(0, read.claims); assertTrue(fixture.screen.launched.isEmpty());
    }

    @Test public void secretRequestUsesOneBoundGrantAndNativeConnectionInjection() throws Exception {
        installSyntheticAdapter(); Result saved = perform("secrets.put", put()); work();
        String id = ((JSONObject) saved.value).getString("id");
        NativeNetworkInjectionTest.Fake connection = new NativeNetworkInjectionTest.Fake(); transport.queued.add(connection);
        Result response = perform("secrets.request", new JSONObject().put("id", id).put("request", new JSONObject()
                .put("url", "https://api.example.test/path").put("method", "POST").put("body", "payload")
                .put("headers", new JSONObject().put("X-Ordinary", "yes"))));
        NativeOperationSessions.Lease requestLease = lease();
        assertEquals("secrets.request", requestLease.request.operation);
        JSONObject bound = requestLease.request.details();
        assertEquals(id, bound.getString("id")); assertEquals(7, bound.getJSONObject("request").getInt("bodyBytes"));
        assertFalse(bound.toString().contains(SecretEffectsTest.SYNTHETIC));
        work();
        assertEquals(2, fixture.provider.issued.size()); assertEquals(1, response.successes);
        assertEquals("Bearer " + SecretEffectsTest.SYNTHETIC, connection.headers.get("authorization"));
        assertEquals("yes", connection.headers.get("x-ordinary")); assertEquals(0, response.claims);
    }

    @Test public void pauseCancelsAdmittedWorkBeforeStorageAndClosesParentJob() throws Exception {
        installSyntheticAdapter(); Result result = perform("secrets.put", put()); fixture.pump();
        assertEquals(1, queue.values.size());
        effects.onPause(); fixture.sessions.onPause(); queue.next(); idle();
        assertEquals(0, opened); assertEquals(0, vault.writes); assertEquals(0, result.successes);
        assertEquals("CANCELLED", result.error); assertTrue(jobs().isEmpty());
    }

    @Test public void policyRevocationCancelsHandleAndRetiresJobWithoutStorage() throws Exception {
        installSyntheticAdapter(); Result result = perform("secrets.put", put()); fixture.pump();
        fixture.sessions.cancelAll(); idle(); queue.next(); idle();
        assertEquals("CANCELLED", result.error); assertEquals(0, opened); assertEquals(0, result.successes);
        assertTrue(jobs().isEmpty());
    }

    @Test public void pageCloseAfterCommitSuppressesReceiptWithoutPretendingRollback() throws Exception {
        installSyntheticAdapter(); Result result = perform("secrets.put", put()); fixture.pump(); queue.next();
        assertEquals(1, vault.writes);
        result.current = false; effects.close(); idle();
        assertEquals(0, result.successes); assertTrue(jobs().isEmpty()); assertNotNull(vault.saved);
    }

    @Test public void nativeCaptureClaimsExistingUiSlotAndPauseClearsInput() throws Exception {
        installSyntheticAdapter(); Result result = perform("secrets.capture", SecretEffectsPreparedTest.metadata()); fixture.pump();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
        android.widget.EditText input = SecretEffectsTest.field(dialog.getWindow().getDecorView());
        input.setText(SecretEffectsTest.SYNTHETIC); assertEquals(1, result.claims); assertEquals(0, opened);
        effects.onPause(); fixture.sessions.onPause(); idle();
        assertFalse(dialog.isShowing()); assertEquals(0, input.length()); assertEquals(1, result.releases);
        assertEquals(0, result.successes); assertEquals(0, vault.writes); assertTrue(jobs().isEmpty());
    }

    private static final class Result implements NativeEffects.Reply {
        Object value; String error; int claims, releases, successes; boolean current = true, held;
        public boolean current() { return current; }
        public boolean claimUi() { assertFalse(held); held = true; claims++; return true; }
        public void releaseUi() { if (held) { held = false; releases++; } }
        public void ok(Object result) { assertTrue(current); value = result; successes++; }
        public void fail(String code, String message) { error = code; }
    }
}
