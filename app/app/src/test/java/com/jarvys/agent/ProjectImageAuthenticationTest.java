package com.jarvys.agent;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class ProjectImageAuthenticationTest {
    private SecretStore secrets(){Context context=ApplicationProvider.getApplicationContext();SecretStore secrets=new SecretStore(context.getSharedPreferences("ux36-auth-"+UUID.randomUUID(),Context.MODE_PRIVATE));secrets.saveCodexTokens("access","refresh",System.currentTimeMillis()+3600000,"account");return secrets;}
    @Test public void scopeRevocationAfter401PreventsRefreshAndSecondTransmission(){
        SecretStore secrets=secrets();AtomicBoolean allowed=new AtomicBoolean(true);AtomicInteger credentials=new AtomicInteger(),sends=new AtomicInteger();
        assertThrows(RuntimeException.class,()->CodexImageGenerationClient.scopedRequest(new JSONObject(),"fixture",CancellationToken.uncancellable(),"fixture",()->{if(!allowed.get())throw new IllegalStateException("revoked");},"account",
                (refresh,token)->{credentials.incrementAndGet();return secrets.getCodexCredentials();},
                (body,auth,session,token)->{sends.incrementAndGet();allowed.set(false);return new ProviderHttp.Response(401,"unauthorized");}));
        assertEquals(1,sends.get());assertEquals(1,credentials.get());
    }
    @Test public void accountSwitchDuringCredentialRefreshNeverSendsUnderAnotherAccount(){
        SecretStore secrets=secrets();AtomicInteger sends=new AtomicInteger();
        assertThrows(RuntimeException.class,()->CodexImageGenerationClient.scopedRequest(new JSONObject(),"fixture",CancellationToken.uncancellable(),"fixture",()->{},"account",
                (refresh,token)->{if(refresh)secrets.saveCodexTokens("other","other",System.currentTimeMillis()+3600000,"other-account");return secrets.getCodexCredentials();},
                (body,auth,session,token)->{sends.incrementAndGet();return new ProviderHttp.Response(401,"unauthorized");}));
        assertEquals(1,sends.get());
    }
    @Test public void tokenRefreshPreservesAuthorizationGenerationAndRetriesExactlyOnce(){
        SecretStore secrets=secrets();String authorization=secrets.getCodexCredentials().authorizationId;AtomicInteger sends=new AtomicInteger();
        ProviderHttp.Response result=CodexImageGenerationClient.scopedRequest(new JSONObject(),"fixture",CancellationToken.uncancellable(),"fixture",()->assertEquals(authorization,secrets.getCodexCredentials().authorizationId),"account",
                (refresh,token)->{if(refresh)secrets.refreshCodexTokens("fresh","fresh",System.currentTimeMillis()+3600000,"account");return secrets.getCodexCredentials();},
                (body,auth,session,token)->new ProviderHttp.Response(sends.incrementAndGet()==1?401:200,"fixture"));
        assertEquals(200,result.status);assertEquals(2,sends.get());assertEquals(authorization,secrets.getCodexCredentials().authorizationId);
        secrets.clearCodexTokens();secrets.saveCodexTokens("access","refresh",System.currentTimeMillis()+3600000,"account");assertNotEquals(authorization,secrets.getCodexCredentials().authorizationId);
    }
    @Test public void generationPinsOriginAndRejectsRedirectWithoutSecondTransmission() throws Exception {
        AtomicInteger opens=new AtomicInteger();
        java.net.HttpURLConnection connection=new java.net.HttpURLConnection(new java.net.URL(OpenAICodexResponsesClient.ENDPOINT)) {
            @Override public void disconnect() { }
            @Override public boolean usingProxy() {return false;}
            @Override public void connect() {throw new AssertionError("No actual network");}
            @Override public int getResponseCode() {assertFalse(getInstanceFollowRedirects());return 307;}
            @Override public String getHeaderField(String name) {return "Location".equals(name)?"https://untrusted.invalid/redirect":null;}
            @Override public java.io.OutputStream getOutputStream() {assertFalse(getInstanceFollowRedirects());return new java.io.ByteArrayOutputStream();}
            @Override public java.io.InputStream getInputStream() {return new java.io.ByteArrayInputStream(new byte[0]);}
        };
        ProviderSettings settings=new ProviderSettings(ApplicationProvider.getApplicationContext());
        CodexImageGenerationClient client=new CodexImageGenerationClient(settings,(request,session,token)->
                OpenAICodexResponsesClient.sendImageRequest(request,secrets().getCodexCredentials(),session,token,endpoint->{
                    assertEquals(OpenAICodexResponsesClient.ENDPOINT,endpoint);opens.incrementAndGet();return connection;
                }));
        CodexImageGenerationException failure=assertThrows(CodexImageGenerationException.class,()->client.generate("fixture","A generic image",null,CancellationToken.uncancellable()));
        assertEquals(Integer.valueOf(307),failure.httpStatus);assertEquals(1,opens.get());assertFalse(connection.getInstanceFollowRedirects());
    }
}
