package com.jarvys.factory.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import static org.junit.Assert.*;

/** Offline Robolectric UI/lifecycle tests, run separately with Android-compiled production sources. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SecretEffectsTest {
    static final NativeOperationAuthorization.Access ALLOWED = () -> { };
    static final String SYNTHETIC = "synthetic-ui-fixture";
    static class Vault implements NativeSecrets.Backend, NativeSecrets.Crypto {
        byte[] saved; SecretKey key; int writes; Runnable afterWrite = () -> { };
        public byte[] read() { return saved == null ? null : saved.clone(); }
        public void write(byte[] bytes) { saved = bytes.clone(); writes++; afterWrite.run(); }
        public void delete() { saved = null; }
        public byte[] encrypt(byte[] plain, byte[] aad, boolean create) throws FactoryException {
            try {
                if (key == null && create) { KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); key = generator.generateKey(); }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad);
                byte[] body = cipher.doFinal(plain), result = Arrays.copyOf(cipher.getIV(), 12 + body.length);
                System.arraycopy(body, 0, result, 12, body.length); return result;
            } catch (Exception failure) { throw new FactoryException("SECRET_KEY_UNAVAILABLE", "Synthetic crypto unavailable."); }
        }
        public byte[] decrypt(byte[] encrypted, byte[] aad) throws FactoryException {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted, 0, 12)); cipher.updateAAD(aad);
                return cipher.doFinal(encrypted, 12, encrypted.length - 12);
            } catch (Exception failure) { throw new FactoryException("SECRET_CORRUPT", "Synthetic crypto unavailable."); }
        }
        public void deleteKey() { key = null; }
    }
    static class Queue implements Executor {
        final ArrayDeque<Runnable> values = new ArrayDeque<>(); boolean reject;
        public void execute(Runnable runnable) { if (reject) throw new RejectedExecutionException(); values.add(runnable); }
        void next() throws Exception {
            Runnable runnable = values.remove();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(runnable, "synthetic-secret-test");
            worker.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
            worker.start(); worker.join(5000);
            assertFalse("Synthetic worker did not finish", worker.isAlive());
            assertNull("Synthetic worker raised an uncaught failure", failure.get());
        }
    }
    static class Reply implements SecretEffects.Callback {
        boolean current = true, allowUi = true; int claims, releases, completions; Object result; FactoryException failure;
        public boolean current() { return current; }
        public boolean claimUi() { assertEquals(Looper.getMainLooper(), Looper.myLooper()); claims++; return allowUi; }
        public void releaseUi() { assertEquals(Looper.getMainLooper(), Looper.myLooper()); releases++; }
        public void complete(Object result) { assertEquals(Looper.getMainLooper(), Looper.myLooper()); this.result = result; completions++; }
        public void error(FactoryException failure) { assertEquals(Looper.getMainLooper(), Looper.myLooper()); this.failure = failure; }
    }
    static class Rig implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final Vault vault = new Vault(); final Queue queue = new Queue(); final Reply reply = new Reply();
        final AtomicBoolean foreground = new AtomicBoolean(true), live = new AtomicBoolean(true);
        final NativeSecrets secrets = new NativeSecrets(vault, vault, "com.example.generated", NativeSecrets.Limits.defaults(), new SecureRandom());
        int opened;
        final SecretEffects effects = new SecretEffects(activity, NativeSecrets.Limits.defaults(),
                () -> { opened++; return secrets; }, new NativeNetwork(url -> { throw new AssertionError("No real network permitted"); }, host -> true, System::nanoTime),
                queue, new Handler(Looper.getMainLooper()));
        SecretEffects.Handle execute(String method, JSONObject args) throws Exception {
            return effects.execute(SecretEffects.prepare(method, args), () -> { if (!live.get()) throw new SecurityException(); }, foreground::get, reply);
        }
        void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
        public void close() { effects.close(); idle(); activity.finish(); }
    }
    static EditText field(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText found = field(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    static boolean secure(Activity activity) { return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0; }
    static JSONObject put() throws Exception { return SecretEffectsPreparedTest.metadata().put("value", SYNTHETIC); }

    @Test public void deniedAccessAcquiresNoStorageAndClosesPut() throws Exception {
        try (Rig rig = new Rig()) {
            rig.live.set(false); SecretEffects.Prepared prepared = SecretEffects.prepare("secrets.put", put());
            try { rig.effects.execute(prepared, () -> { throw new SecurityException(); }, () -> true, rig.reply); fail(); }
            catch (SecurityException expected) { }
            assertEquals(0, rig.opened); assertTrue(rig.queue.values.isEmpty());
            SecretEffectsPreparedTest.error("CANCELLED", prepared::authorizationDetails);
        }
    }
    @Test public void programmaticPutUsesNoUiAndGetExplicitlyReturnsPlaintext() throws Exception {
        try (Rig rig = new Rig()) {
            rig.execute("secrets.put", put()); assertEquals(0, rig.opened); assertEquals(0, rig.reply.claims);
            rig.queue.next(); rig.idle();
            assertEquals(1, rig.reply.completions); assertEquals(0, rig.reply.claims);
            JSONObject metadata = (JSONObject) rig.reply.result; assertFalse(metadata.toString().contains(SYNTHETIC));
            rig.execute("secrets.get", new JSONObject().put("id", metadata.getString("id")));
            rig.queue.next(); rig.idle(); assertEquals(SYNTHETIC, rig.reply.result);
        }
    }
    @Test public void captureHasNativePasswordSecureWindowsAndNoSavedStateOrAutofill() throws Exception {
        try (Rig rig = new Rig()) {
            rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
            EditText editor = field(dialog.getWindow().getDecorView()); assertNotNull(editor);
            assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, editor.getInputType() & InputType.TYPE_MASK_VARIATION);
            assertFalse(editor.isSaveEnabled()); assertFalse(editor.isSaveFromParentEnabled());
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, editor.getImportantForAutofill());
            assertTrue(secure(rig.activity)); assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertEquals(0, rig.opened); assertTrue(rig.queue.values.isEmpty());
            editor.setText(SYNTHETIC); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); rig.idle();
            assertEquals(0, editor.length()); assertFalse(dialog.isShowing()); assertFalse(secure(rig.activity));
            assertEquals(1, rig.reply.releases); assertEquals(0, rig.vault.writes);
        }
    }
    @Test public void captureRestoresAnAlreadySecureActivity() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            SecretEffects.Handle handle = rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            handle.cancel(); rig.idle(); assertTrue(secure(rig.activity));
        }
    }
    @Test public void captureSubmissionClearsFieldBeforeWorkerAndReturnsOnlyMetadata() throws Exception {
        try (Rig rig = new Rig()) {
            rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); EditText editor = field(dialog.getWindow().getDecorView());
            editor.setText(SYNTHETIC); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(0, editor.length()); assertFalse(dialog.isShowing()); assertEquals(0, rig.vault.writes);
            rig.queue.next(); rig.idle(); JSONObject result = (JSONObject) rig.reply.result;
            assertFalse(result.toString().contains(SYNTHETIC)); assertEquals(SYNTHETIC, rig.secrets.get(result.getString("id"), ALLOWED));
        }
    }
    @Test public void oversizedReplacementIsNotPartiallyStoredOrSilentlyTruncated() throws Exception {
        try (Rig rig = new Rig()) {
            rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); EditText editor = field(dialog.getWindow().getDecorView());
            editor.setText(SYNTHETIC); char[] tooLong = new char[8193]; Arrays.fill(tooLong, 'x');
            editor.getText().replace(0, editor.length(), new String(tooLong)); rig.idle();
            assertEquals(SYNTHETIC, editor.getText().toString()); assertNotNull(editor.getError());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); rig.idle();
            assertTrue(rig.queue.values.isEmpty()); assertEquals(0, rig.vault.writes); assertEquals(0, editor.length());
        }
    }
    @Test public void cancelAfterCaptureBeforeQueuedWriteDoesNotCommit() throws Exception {
        try (Rig rig = new Rig()) {
            SecretEffects.Handle handle = rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); field(dialog.getWindow().getDecorView()).setText(SYNTHETIC);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); handle.cancel(); rig.queue.next(); rig.idle();
            assertEquals(0, rig.opened); assertEquals(0, rig.vault.writes); assertEquals(0, rig.reply.completions);
        }
    }
    @Test public void pageLossCancelsCaptureAndNeverNotifiesReplacementPage() throws Exception {
        try (Rig rig = new Rig()) {
            rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); EditText editor = field(dialog.getWindow().getDecorView()); editor.setText(SYNTHETIC);
            rig.reply.current = false; rig.foreground.set(false); rig.effects.cancelAll(); rig.idle();
            assertFalse(dialog.isShowing()); assertEquals(0, editor.length()); assertFalse(secure(rig.activity));
            assertEquals(0, rig.reply.completions); assertNull(rig.reply.failure); assertEquals(0, rig.opened);
        }
    }
    @Test public void queueRejectionClearsPreparedWithoutStorageOrUi() throws Exception {
        try (Rig rig = new Rig()) {
            rig.queue.reject = true; SecretEffects.Prepared prepared = SecretEffects.prepare("secrets.put", put());
            rig.effects.execute(prepared, ALLOWED, () -> true, rig.reply); rig.idle();
            assertEquals("BUSY", rig.reply.failure.code); assertEquals(0, rig.opened); assertEquals(0, rig.reply.claims);
            SecretEffectsPreparedTest.error("CANCELLED", prepared::authorizationDetails);
        }
    }
    @Test public void revokedAfterCommittedWriteWithholdsReceiptAndDoesNotClaimRollback() throws Exception {
        try (Rig rig = new Rig()) {
            rig.vault.afterWrite = () -> rig.live.set(false);
            rig.execute("secrets.put", put()); rig.queue.next(); rig.idle();
            assertEquals(1, rig.vault.writes); assertEquals(0, rig.reply.completions);
            assertEquals("PERMISSION_DENIED", rig.reply.failure.code); assertTrue(rig.reply.failure.getMessage().contains("not undone"));
            assertEquals(1, rig.secrets.list(ALLOWED).length());
        }
    }
    @Test public void stalePageBeforeMainDeliveryDropsPlaintextResult() throws Exception {
        try (Rig rig = new Rig()) {
            String id = rig.secrets.put(SecretRequest.parsePut(put()), ALLOWED).getString("id");
            rig.execute("secrets.get", new JSONObject().put("id", id)); rig.queue.next();
            rig.reply.current = false; rig.idle(); assertEquals(0, rig.reply.completions); assertNull(rig.reply.result);
        }
    }
    @Test public void atMostEighteenOperationsCanWaitForWorkerOrDelivery() throws Exception {
        try (Rig rig = new Rig()) {
            for (int i = 0; i < 18; i++) rig.execute("secrets.list", new JSONObject());
            SecretEffectsPreparedTest.error("BUSY", () -> rig.execute("secrets.list", new JSONObject()));
            assertEquals(18, rig.queue.values.size()); assertEquals(0, rig.opened);
        }
    }
    @Test public void deniedUiClaimNeverCreatesStorageOrLeavesSecureFlag() throws Exception {
        try (Rig rig = new Rig()) {
            rig.reply.allowUi = false; rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            assertEquals("BUSY", rig.reply.failure.code); assertEquals(0, rig.opened); assertFalse(secure(rig.activity));
            assertEquals(0, rig.reply.releases);
        }
    }
    @Test @Config(sdk = 24) public void captureWorksAtMinimumSupportedApi() throws Exception {
        try (Rig rig = new Rig()) {
            SecretEffects.Handle handle = rig.execute("secrets.capture", SecretEffectsPreparedTest.metadata()); rig.idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
            handle.cancel(); rig.idle(); assertFalse(dialog.isShowing()); assertFalse(secure(rig.activity));
        }
    }
}
