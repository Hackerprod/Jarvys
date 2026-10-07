package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageEditRecoveryTest {
    private Context context() { return ApplicationProvider.getApplicationContext(); }
    private String reference() { return "attachment:" + UUID.randomUUID(); }
    private byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        return output.toByteArray();
    }
    private String response(byte[] png) throws Exception {
        return new JSONObject().put("data", new JSONArray().put(new JSONObject()
                .put("b64_json", Base64.encodeToString(png, Base64.NO_WRAP))))
                .put("size", "1024x1024").toString();
    }

    @Test public void explicitReferencesRejectPathsUrlsGuessesDuplicatesAndTooMany() {
        String reference = reference();
        assertEquals(Collections.singletonList(reference), ImageReferenceResolver.parse(Collections.singletonList(reference)));
        assertTrue(ImageReferenceResolver.parse(null).isEmpty());
        for (Object invalid : new Object[]{"attachment:1", Collections.singletonList("/tmp/x.png"),
                Collections.singletonList("https://example.org/image.png"), Collections.singletonList(1),
                Arrays.asList(reference, reference), Collections.nCopies(6, reference)}) {
            assertThrows(IllegalArgumentException.class, () -> ImageReferenceResolver.parse(invalid));
        }
        assertThrows(UnsupportedOperationException.class, () -> ImageReferenceResolver.parse(Collections.singletonList(reference)).add(reference()));
    }

    @Test public void canonicalBase64RejectsWhitespaceWrongPaddingAndUnusedBits() {
        for (String valid : new String[]{"AA==", "AAA=", "AAAA", "YQ=="}) assertTrue(ImageEditInput.isCanonicalBase64(valid));
        for (String invalid : new String[]{"", "A", "AAA", "AA===", "AB==", "AAB=", "AA= ", "\nAA==", "====", "-AAA"}) {
            assertFalse(invalid, ImageEditInput.isCanonicalBase64(invalid));
            assertThrows(IllegalArgumentException.class, () -> new ImageEditInput("image/png", invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ImageEditInput("image/gif", "AA=="));
    }

    @Test public void editRequestUsesOnlySelectedImagesAndDedicatedModel() throws Exception {
        ImageEditInput one = new ImageEditInput("image/png", "AA==");
        ImageEditInput two = new ImageEditInput("image/jpeg", "AAA=");
        JSONObject request = CodexImageGenerationClient.buildEditRequest("keep both", "1536x1024", Arrays.asList(one, two));
        assertEquals("gpt-image-2", request.getString("model"));
        assertEquals(1, request.getInt("n"));
        assertEquals("keep both", request.getString("prompt"));
        assertEquals("1536x1024", request.getString("size"));
        assertEquals(2, request.getJSONArray("images").length());
        assertEquals(one.dataUrl(), request.getJSONArray("images").getJSONObject(0).getString("image_url"));
        assertEquals(two.dataUrl(), request.getJSONArray("images").getJSONObject(1).getString("image_url"));
        assertFalse(request.has("input"));
        assertFalse(request.has("stream"));
        assertFalse(request.has("tools"));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.buildEditRequest("x", null, Collections.emptyList()));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.buildEditRequest("x", "1x1", Collections.singletonList(one)));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.buildEditRequest("x", null, Collections.nCopies(6, one)));
    }

    @Test public void strictResponseAcceptsCompletePngAndRejectsCorruptionTruncationAndTrailingData() throws Exception {
        byte[] png = png();
        assertArrayEquals(png, CodexImageGenerationClient.parseEditResponse(response(png)).bytes);
        byte[] corrupt = png.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertEquals(CodexImageGenerationException.Kind.INVALID_IMAGE,
                assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.parseEditResponse(response(corrupt))).kind);
        byte[] truncated = Arrays.copyOf(png, png.length - 12);
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.parseEditResponse(response(truncated)));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.parseEditResponse(response(png) + "{}"));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.parseEditResponse("{data:[]}"));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.parseEditResponse("[]"));
        assertThrows(CodexImageGenerationException.class, () -> CodexImageGenerationClient.parseEditResponse("{\"data\":[{\"b64_json\":\"AB==\"}]}"));
    }

    @Test public void editErrorNeverRetainsPromptImagePayloadOrTransportCause() {
        ProviderSettings settings = new ProviderSettings(context());
        String privateText = "private attachment payload";
        CodexImageGenerationClient client = new CodexImageGenerationClient(settings,
                (request, session, token) -> { throw new IllegalStateException(privateText); });
        CodexImageGenerationException error = assertThrows(CodexImageGenerationException.class, () -> client.edit(
                "chat", privateText, null, Collections.singletonList(new ImageEditInput("image/png", "AA==")), CancellationToken.uncancellable()));
        assertEquals(CodexImageGenerationException.Kind.NETWORK, error.kind);
        assertFalse(error.getMessage().contains(privateText));
        assertNull(error.getCause());
        CodexImageGenerationClient rejected = new CodexImageGenerationClient(settings,
                (request, session, token) -> new ProviderHttp.Response(400,
                        "{\"error\":{\"code\":\"secret_code\",\"message\":\"private attachment payload\"}}", "private attachment payload"));
        error = assertThrows(CodexImageGenerationException.class, () -> rejected.edit("chat", "x", null,
                Collections.singletonList(new ImageEditInput("image/png", "AA==")), CancellationToken.uncancellable()));
        assertEquals("image_edit_failed", error.errorCode);
        assertFalse(error.apiMessage.contains(privateText));
        assertNull(error.getCause());
    }

    @Test public void editExecutorIsSeparateFromTextGenerationAndReturnsDecodedImage() throws Exception {
        ProviderSettings settings = new ProviderSettings(context());
        byte[] bytes = png();
        String response = response(bytes);
        final int[] calls = {0, 0};
        CodexImageGenerationClient client = new CodexImageGenerationClient(settings,
                (request, session, token) -> { calls[0]++; throw new AssertionError("wrong endpoint"); },
                (request, session, token) -> { calls[1]++; return new ProviderHttp.Response(200, response, response); });
        assertArrayEquals(bytes, client.edit("chat", "x", null,
                Collections.singletonList(new ImageEditInput("image/png", "AA==")), CancellationToken.uncancellable()).bytes);
        assertArrayEquals(new int[]{0, 1}, calls);
    }

    @Test public void transportPinsOriginDisablesRedirectsAndAlwaysClosesStreams() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.body = "{\"ok\":true}";
        ProviderHttp.Response response = OpenAICodexImagesClient.sendEditRequest(new JSONObject().put("prompt", "test"), credentials(), "chat-id",
                CancellationToken.uncancellable(), url -> {
                    assertEquals(OpenAICodexImagesClient.EDIT_ENDPOINT, url.toString());
                    return connection;
                });
        assertEquals(200, response.status);
        assertFalse(connection.getInstanceFollowRedirects());
        assertEquals("POST", connection.getRequestMethod());
        assertEquals("chat-id", connection.getRequestProperty("conversation_id"));
        assertEquals("Bearer fixture-access", connection.getRequestProperty("Authorization"));
        assertTrue(connection.disconnected);
        assertTrue(connection.inputClosed);
        assertTrue(connection.outputClosed);
        assertTrue(new String(connection.written.toByteArray(), StandardCharsets.UTF_8).contains("test"));
    }

    @Test public void transportCancellationDisconnectsAndDoesNotReadOrLeakFailureDetails() throws Exception {
        FakeConnection connection = new FakeConnection();
        CancellationToken token = CancellationToken.cancellable();
        connection.afterWrite = token::cancel;
        assertThrows(java.util.concurrent.CancellationException.class, () -> OpenAICodexImagesClient.sendEditRequest(
                new JSONObject(), credentials(), "chat", token, url -> connection));
        assertTrue(connection.disconnected);
        assertTrue(connection.outputClosed);
        ProviderTransportException failure = assertThrows(ProviderTransportException.class, () -> OpenAICodexImagesClient.sendEditRequest(
                new JSONObject(), credentials(), "chat", CancellationToken.uncancellable(), url -> {
                    throw new IOException("Bearer private-secret");
                }));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private-secret"));
    }

    private SecretStore.CodexCredentials credentials() throws Exception {
        java.lang.reflect.Constructor<SecretStore.CodexCredentials> constructor = SecretStore.CodexCredentials.class
                .getDeclaredConstructor(String.class, String.class, long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("fixture-access", "fixture-refresh", Long.MAX_VALUE, "fixture-account");
    }

    private static final class FakeConnection extends HttpURLConnection {
        String body = "{}";
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        boolean disconnected;
        boolean inputClosed;
        boolean outputClosed;
        Runnable afterWrite;
        FakeConnection() throws Exception { super(new URL(OpenAICodexImagesClient.EDIT_ENDPOINT)); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public int getResponseCode() { return 200; }
        @Override public String getHeaderField(String name) { return null; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { written.write(value); }
                @Override public void close() { outputClosed = true; if (afterWrite != null) afterWrite.run(); }
            };
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() { inputClosed = true; }
            };
        }
    }
}
