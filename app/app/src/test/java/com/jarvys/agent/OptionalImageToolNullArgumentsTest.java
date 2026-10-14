package com.jarvys.agent;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Null-argument validation and receiver behavior without opening a ProjectScope. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OptionalImageToolNullArgumentsTest {
    private Context context;
    private ProviderSettings settings;
    private ProviderSettings.Provider originalProvider;
    private SecretStore secrets;
    private String session;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        settings = new ProviderSettings(context);
        originalProvider = settings.getProvider();
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        session = "optional-null-" + UUID.randomUUID();
        secrets = new SecretStore(context.getSharedPreferences(session, Context.MODE_PRIVATE));
        secrets.saveCodexTokens("synthetic-access", "synthetic-refresh",
                Long.MAX_VALUE, "synthetic-account");
    }

    @After public void cleanup() {
        settings.setProvider(originalProvider);
        AgentRunUiState.resetSession(session);
    }

    @Test public void projectImageNullOptionalsAreRemovedBeforeTheToolReceiverAndKeepDefaults() {
        ToolSpec declaration = ProjectImageTool.declarationSpec();
        assertEquals(ProjectImageTool.NAME, declaration.name);
        Map<String, Object> raw = arguments(
                "action", "generate", "path", "assets/optional.png",
                "prompt", "A blue abstract image", "expected_sha256", "missing",
                "expected_scope_version", 0, "size", null, "max_bytes", null,
                "reference_images", null);

        ToolArgumentValidator.Result validated = ToolArgumentValidator.coerceAndValidate(
                raw, declaration.jsonSchema());

        assertNull(validated.error);
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        CoreTool receiver = new CoreTool() {
            @Override public ToolSpec declaration() { return declaration; }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                received.set(new LinkedHashMap<>(arguments));
                return CoreToolResult.success("captured");
            }
        };
        assertTrue(receiver.execute(validated.arguments, CancellationToken.uncancellable()).success);
        assertNotNull(received.get());
        assertFalse(received.get().containsKey("size"));
        assertFalse(received.get().containsKey("max_bytes"));
        assertFalse(received.get().containsKey("reference_images"));

        assertNull(ProjectImageTool.sizeArgumentOrDefault(received.get()));
        assertEquals(ProjectImageAssets.MAX_BYTES,
                ProjectImageTool.maxBytesArgumentOrDefault(received.get()));
    }

    @Test public void codexImageNullSizeIsAbsentFromSyntheticRequestAndKeepsRequestDefaults() throws Exception {
        AtomicReference<JSONObject> capturedRequest = new AtomicReference<>();
        AtomicInteger generationCalls = new AtomicInteger();
        AtomicInteger editCalls = new AtomicInteger();
        CodexImageGenerationClient client = new CodexImageGenerationClient(settings,
                (request, currentSession, token) -> {
                    capturedRequest.set(request);
                    generationCalls.incrementAndGet();
                    return new ProviderHttp.Response(400,
                            "{\"error\":{\"message\":\"synthetic rejection\"}}");
                },
                (request, currentSession, token) -> {
                    editCalls.incrementAndGet();
                    return new ProviderHttp.Response(400,
                            "{\"error\":{\"message\":\"unexpected edit\"}}");
                });
        CoreTool tool = new CodexImageGenerationTool(context, session, settings, secrets, client);
        ToolArgumentValidator.Result validated = ToolArgumentValidator.coerceAndValidate(
                arguments("prompt", "A blue abstract image", "size", null, "image_refs", null),
                tool.declaration().jsonSchema());

        assertNull(validated.error);
        assertEquals(Collections.singleton("prompt"), validated.arguments.keySet());
        CoreToolResult result = tool.execute(validated.arguments, CancellationToken.uncancellable());

        assertFalse(result.success); // Synthetic HTTP failure proves the request path was exercised.
        assertEquals(1, generationCalls.get());
        assertEquals(0, editCalls.get());
        JSONObject request = capturedRequest.get();
        assertNotNull(request);
        JSONObject imageTool = request.getJSONArray("tools").getJSONObject(0);
        assertEquals("image_generation", imageTool.getString("type"));
        assertEquals("generate", imageTool.getString("action"));
        assertEquals(CodexImageGenerationClient.DEFAULT_FORMAT, imageTool.getString("output_format"));
        assertFalse(imageTool.has("size"));
        assertFalse(request.getBoolean("store"));
        assertTrue(request.getBoolean("stream"));
        assertFalse(request.getBoolean("parallel_tool_calls"));
        assertEquals("image_generation", request.getJSONObject("tool_choice").getString("type"));
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }
}
