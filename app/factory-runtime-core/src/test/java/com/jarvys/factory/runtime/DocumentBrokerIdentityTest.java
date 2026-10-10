package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSigningInfo;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Synthetic package-manager evidence only; this does not attest any installed APK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 32}, manifest = Config.NONE)
public class DocumentBrokerIdentityTest {
    static final byte[] CERTIFICATE = new byte[] {1, 7, 9, 21};
    static void install(Context context, String name, Signature... signatures) {
        PackageInfo info = new PackageInfo(); info.packageName = name; info.signatures = signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            ((ShadowSigningInfo) Shadow.extract(info.signingInfo)).setSignatures(signatures);
        }
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
    static String digest() throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(CERTIFICATE))
            out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    static FactoryConfig configuration(String name, String digest) throws Exception {
        return FactoryConfig.parsePreview(new JSONObject(FactoryDispatcherTest.configuration("[\"documents\"]"))
                .put("documentBroker", new JSONObject().put("packageName", name).put("certificateSha256", digest)).toString());
    }
    private void unavailable(FactoryConfig.DocumentBroker broker) throws Exception {
        try { DocumentBrokerIdentity.verify(RuntimeEnvironment.getApplication(), broker); fail("Untrusted broker accepted"); }
        catch (FactoryException expected) { assertEquals("UNAVAILABLE", expected.code); }
    }
    @Test public void onlyBuildOwnedHostNamesAreSupported() throws Exception {
        assertTrue(DocumentBrokerIdentity.supported(DocumentBrokerIdentity.PRIMARY));
        assertTrue(DocumentBrokerIdentity.supported(DocumentBrokerIdentity.RECOVERY));
        assertFalse(DocumentBrokerIdentity.supported("com.jarvys.agent.evil"));
        try { DocumentBrokerIdentity.certificate(RuntimeEnvironment.getApplication(), "com.other.broker"); fail(); }
        catch (FactoryException expected) { assertEquals("UNAVAILABLE", expected.code); }
    }
    @Test public void missingBrokerAndMissingBindingFailClosed() throws Exception {
        unavailable(null);
        unavailable(configuration(DocumentBrokerIdentity.PRIMARY, digest()).documentBroker);
    }
    @Test public void matchingSingleSignerAuthenticatesPrimary() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        install(context, DocumentBrokerIdentity.PRIMARY, new Signature(CERTIFICATE));
        assertEquals(digest(), DocumentBrokerIdentity.certificate(context, DocumentBrokerIdentity.PRIMARY));
        DocumentBrokerIdentity.verify(context, configuration(DocumentBrokerIdentity.PRIMARY, digest()).documentBroker);
    }
    @Test public void matchingSingleSignerAuthenticatesRecovery() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        install(context, DocumentBrokerIdentity.RECOVERY, new Signature(CERTIFICATE));
        DocumentBrokerIdentity.verify(context, configuration(DocumentBrokerIdentity.RECOVERY, digest()).documentBroker);
    }
    @Test public void wrongCertificateFailsClosed() throws Exception {
        install(RuntimeEnvironment.getApplication(), DocumentBrokerIdentity.PRIMARY, new Signature(new byte[] {99}));
        unavailable(configuration(DocumentBrokerIdentity.PRIMARY, digest()).documentBroker);
    }
    @Test public void absentAndMultipleSignersFailClosed() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        install(context, DocumentBrokerIdentity.PRIMARY, new Signature[0]);
        unavailable(configuration(DocumentBrokerIdentity.PRIMARY, digest()).documentBroker);
        install(context, DocumentBrokerIdentity.PRIMARY, new Signature(CERTIFICATE), new Signature(new byte[] {3}));
        unavailable(configuration(DocumentBrokerIdentity.PRIMARY, digest()).documentBroker);
    }
    @Test public void twoInstalledHostsFailEvenWhenBothCertificatesMatch() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        install(context, DocumentBrokerIdentity.PRIMARY, new Signature(CERTIFICATE));
        install(context, DocumentBrokerIdentity.RECOVERY, new Signature(CERTIFICATE));
        unavailable(configuration(DocumentBrokerIdentity.PRIMARY, digest()).documentBroker);
        unavailable(configuration(DocumentBrokerIdentity.RECOVERY, digest()).documentBroker);
    }
}
