package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.coding.ProjectMutationService;
import com.jarvys.agent.coding.ProjectScope;
import com.jarvys.agent.coding.ProjectScopeStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Synthetic provider and host filesystem evidence; no real provider or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProjectImageToolTest {
    private Context context;
    private ProviderSettings settings;
    private SecretStore secrets;
    private Object priorSecrets;
    private ProjectScope scope;
    private String session;
    private byte[] png;
    private AtomicInteger calls;
    private AtomicReference<JSONObject> request;
    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext(); session = "ux36-image-" + UUID.randomUUID();
        java.lang.reflect.Field field = SecretStore.class.getDeclaredField("singleton"); field.setAccessible(true); priorSecrets = field.get(null);
        secrets = new SecretStore(context.getSharedPreferences(session, Context.MODE_PRIVATE));
        secrets.saveCodexTokens("test-access", "test-refresh", System.currentTimeMillis() + 3600000, "test-account"); field.set(null, secrets);
        settings = new ProviderSettings(context); settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX); settings.setOpenAiModel("gpt-5.4");
        scope = new ProjectScopeStore(context.getFilesDir()).open(session);
        new LocalRunStore(context).appendConversationMessage(session, "user", "Implement my project with suitable generated visuals");
        png = png(8, 6, false); calls = new AtomicInteger(); request = new AtomicReference<>();
    }
    @After public void cleanup() throws Exception {
        java.lang.reflect.Field field = SecretStore.class.getDeclaredField("singleton"); field.setAccessible(true); field.set(null, priorSecrets);
        settings.setProvider(ProviderSettings.Provider.OPENROUTER); ArtifactOsShadow.reset(); AgentRunUiState.resetSession(session);
    }
    private CodexImageGenerationClient client(Runnable onSend) {
        return new CodexImageGenerationClient(settings, (body, id, token) -> {
            calls.incrementAndGet(); request.set(body); onSend.run();
            return new ProviderHttp.Response(200, generated(png));
        }, (body, id, token) -> {
            calls.incrementAndGet(); request.set(body); onSend.run();
            try { return new ProviderHttp.Response(200, new JSONObject().put("data", new JSONArray().put(new JSONObject().put("b64_json", Base64.encodeToString(png, Base64.NO_WRAP)))).toString()); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }
    private ProjectImageTool tool() throws Exception { return tool(scope, () -> {}, () -> {}); }
    private ProjectImageTool tool(ProjectScope selected, Runnable guard, Runnable onSend) throws Exception {
        return new ProjectImageTool(context, selected, "fixture-worker", guard, text -> "fixture:receipt", settings, secrets, client(onSend));
    }
    private Map<String,Object> generation(String path) { return args("action", "generate", "path", path, "prompt", "A blue abstract image", "expected_sha256", "missing", "expected_scope_version", scope.version()); }
    private CoreToolResult generate(ProjectImageTool tool, String path) { return tool.execute(generation(path), CancellationToken.crewChild()); }
    private JSONObject success(CoreToolResult result) throws Exception { assertTrue(result.content, result.success); return new JSONObject(result.content); }

    @Test public void generatedBytesAreJournaledInProjectAndReopenedWithStableHash() throws Exception {
        JSONObject receipt = success(generate(tool(), "assets/hero.png"));
        assertEquals(1, calls.get()); assertEquals("APPLIED", receipt.getString("status"));
        assertEquals("/project/assets/hero.png", receipt.getString("captain_path"));
        assertEquals(ProjectScope.sha256(png), receipt.getJSONObject("output").getString("sha256"));
        assertEquals(8, receipt.getJSONObject("output").getInt("width")); assertEquals(6, receipt.getJSONObject("output").getInt("height"));
        assertFalse(receipt.getJSONObject("output").getBoolean("appearance_verified"));
        assertArrayEquals(png, Files.readAllBytes(scope.resolve("assets/hero.png").toPath()));
        assertEquals(ProjectScope.sha256(png), new ProjectScopeStore(context.getFilesDir()).open(session).revision("assets/hero.png"));
        assertTrue(scope.mutationRecovery().entries.stream().anyMatch(entry -> receipt.optString("journal_id").equals(entry.id)));
        assertFalse(receipt.getBoolean("user_attachment_delivered"));
        assertFalse(new LocalRunStore(context).readConversationImageReferences(session).stream().anyMatch(image -> image.generatedPath != null));
    }
    @Test public void inspectProvidesFreshBinaryEvidenceWithoutProviderCall() throws Exception {
        Files.write(scope.resolve("existing.png").toPath(), png);
        JSONObject info = success(tool().execute(args("action", "inspect", "path", "existing.png"), CancellationToken.crewChild()));
        assertEquals(ProjectScope.sha256(png), info.getString("sha256")); assertEquals(0, calls.get());
        assertFalse(info.getBoolean("appearance_verified")); assertEquals(scope.version(), info.getLong("scope_version"));
    }
    @Test public void editUsesOnlyExplicitHashedProjectSnapshotAndPreservesOriginal() throws Exception {
        Files.write(scope.resolve("source.png").toPath(), png);
        Map<String,Object> args = generation("edited.png"); args.put("reference_images", Collections.singletonList(args("path", "source.png", "sha256", ProjectScope.sha256(png))));
        success(tool().execute(args, CancellationToken.crewChild()));
        assertEquals(1, calls.get()); assertTrue(request.get().has("images")); assertEquals(1, request.get().getJSONArray("images").length());
        assertTrue(request.get().getJSONArray("images").getJSONObject(0).getString("image_url").startsWith("data:image/png;base64,"));
        assertFalse(request.get().toString().contains("source.png")); assertArrayEquals(png, Files.readAllBytes(scope.resolve("source.png").toPath()));
    }
    @Test public void staleDestinationAndVersionRejectBeforeQuota() throws Exception {
        Files.write(scope.resolve("existing.png").toPath(), png);
        assertFalse(generate(tool(), "existing.png").success);
        Map<String,Object> args = generation("new.png"); args.put("expected_scope_version", scope.version()+1);
        assertFalse(tool().execute(args, CancellationToken.crewChild()).success); assertEquals(0, calls.get());
    }
    @Test public void traversalSymlinkAndUnownedReferencesRejectBeforeQuota() throws Exception {
        Files.write(scope.resolve("source.png").toPath(), png);
        Files.createSymbolicLink(new File(scope.rootDirectory(), "link.png").toPath(), scope.resolve("source.png").toPath());
        for (String path : Arrays.asList("../source.png", "/source.png", "link.png")) {
            Map<String,Object> args = generation("out.png"); args.put("reference_images", Collections.singletonList(args("path", path, "sha256", ProjectScope.sha256(png))));
            assertFalse(path, tool().execute(args, CancellationToken.crewChild()).success);
        }
        assertFalse(generate(tool(), "../escaped.png").success); assertEquals(0, calls.get());
    }
    @Test public void editsCannotOverwriteTheirReferenceOriginals() throws Exception {
        Files.write(scope.resolve("source.png").toPath(), png);
        Map<String,Object> args=generation("source.png");args.put("expected_sha256",ProjectScope.sha256(png));
        args.put("reference_images",Collections.singletonList(args("path","./source.png","sha256",ProjectScope.sha256(png))));
        assertFalse(tool().execute(args,CancellationToken.crewChild()).success);assertEquals(0,calls.get());
        assertArrayEquals(png,Files.readAllBytes(scope.resolve("source.png").toPath()));
    }
    @Test public void staleReferenceHashRejectsBeforeQuota() throws Exception {
        Files.write(scope.resolve("source.png").toPath(), png);
        Map<String,Object> args = generation("out.png"); args.put("reference_images", Collections.singletonList(args("path", "source.png", "sha256", String.join("", Collections.nCopies(64,"0")))));
        assertFalse(tool().execute(args, CancellationToken.crewChild()).success); assertEquals(0, calls.get());
    }
    @Test public void readOnlyScopeAndRevokedAuthorityCannotInvokeStaleHandler() throws Exception {
        ProjectScope readOnly = scope.restrict(Collections.singleton(ProjectScope.Capability.READ));
        assertFalse(generate(tool(readOnly, () -> {}, () -> {}), "out.png").success);
        assertFalse(generate(tool(scope, () -> { throw new IllegalStateException("revoked"); }, () -> {}), "out.png").success);
        assertEquals(0, calls.get());
    }
    @Test public void providerOrAccountRevocationAfterResponsePreventsPublication() throws Exception {
        ProjectImageTool selected = tool(scope, () -> {}, () -> settings.setProvider(ProviderSettings.Provider.OPENROUTER));
        assertFalse(generate(selected, "out.png").success); assertEquals("missing", scope.revision("out.png")); assertEquals(1, calls.get());
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        selected = tool(scope, () -> {}, () -> secrets.saveCodexTokens("other", "other", System.currentTimeMillis()+3600000, "different-account"));
        assertFalse(generate(selected, "out.png").success); assertEquals("missing", scope.revision("out.png")); assertEquals(2, calls.get());
    }
    @Test public void sameAccountReconnectRevokesOldHandlerButTokenRefreshDoesNot() throws Exception {
        ProjectImageTool selected = tool(scope, () -> {}, () -> {
            secrets.clearCodexTokens(); secrets.saveCodexTokens("new", "new", System.currentTimeMillis()+3600000, "test-account");
        });
        assertFalse(generate(selected, "reconnected.png").success); assertEquals("missing", scope.revision("reconnected.png"));
        selected = tool(scope, () -> {}, () -> secrets.refreshCodexTokens("fresh", "fresh", System.currentTimeMillis()+3600000, "test-account"));
        success(generate(selected, "refreshed.png")); assertEquals(2, calls.get());
    }
    @Test public void replacementRequiresAndPreservesExactBinaryRevisionEvidence() throws Exception {
        Files.write(scope.resolve("replace.png").toPath(), png);
        String before = ProjectScope.sha256(png); png = png(9,7,false);
        Map<String,Object> args = generation("replace.png"); args.put("expected_sha256", before);
        JSONObject receipt = success(tool().execute(args,CancellationToken.crewChild()));
        assertEquals(before, receipt.getJSONArray("applied").getJSONObject(0).getString("before_sha256"));
        assertArrayEquals(png, Files.readAllBytes(scope.resolve("replace.png").toPath()));
    }
    @Test public void cancellationDuringProviderCallReleasesLeaseAndDoesNotPublish() throws Exception {
        CancellationToken token = CancellationToken.crewChild();
        ProjectImageTool selected = tool(scope, () -> {}, token::cancel);
        assertThrows(java.util.concurrent.CancellationException.class, () -> selected.execute(generation("out.png"), token));
        assertEquals("missing", scope.revision("out.png")); assertEquals(1, calls.get());
        try (ProjectScope.WriterLease ignored = scope.acquireWriter("next", scope.version())) { assertNotNull(ignored); }
    }
    @Test public void corruptOutputIsRejectedAndNeverRetried() throws Exception {
        png = Arrays.copyOf(png, png.length - 5);
        assertFalse(generate(tool(), "broken.png").success); assertEquals(1, calls.get()); assertEquals("missing", scope.revision("broken.png"));
    }
    @Test public void boundedPngResizePreservesAlphaAndReportsDimensions() throws Exception {
        png = png(512, 512, true);
        Map<String,Object> args = generation("bounded.png"); args.put("max_bytes", 16384);
        JSONObject receipt = success(tool().execute(args, CancellationToken.crewChild()));
        JSONObject output = receipt.getJSONObject("output"); assertTrue(output.getBoolean("resized"));
        assertTrue(output.getInt("size_bytes") <= 16384); assertTrue(output.getInt("width") < 512);
        assertEquals(ProjectScope.sha256(png),receipt.getJSONObject("provider_image").getString("sha256"));
        assertEquals(512,receipt.getJSONObject("provider_image").getInt("width"));
        assertEquals(16384,receipt.getInt("requested_max_bytes"));assertFalse(receipt.getBoolean("original_provider_bytes_saved"));
        Bitmap decoded = android.graphics.BitmapFactory.decodeFile(scope.resolve("bounded.png").getPath());
        assertTrue(decoded.hasAlpha()); decoded.recycle();
    }
    @Test public void repeatedMutationDoesNotRegenerateWhenPreviousOutputAlreadyExists() throws Exception {
        ProjectImageTool selected = tool(); Map<String,Object> args = generation("once.png");
        success(selected.execute(args, CancellationToken.crewChild()));
        assertFalse(selected.execute(args, CancellationToken.crewChild()).success); assertEquals(1, calls.get());
    }
    @Test public void privateReferenceImportIsExplicitMainOnlyAndPreservesOriginal() throws Exception {
        String id = UUID.randomUUID().toString(); GeneratedImageStore store = new GeneratedImageStore(context);
        String path = store.save(session,id,png); new LocalRunStore(context).appendGeneratedImageEvent(session,path,"blue","blue","8x6","image/png");
        Map<String,Object> args = args("image_ref", "generated:"+id,"path","imported.png","expected_sha256","missing","expected_scope_version",scope.version());
        ImportProjectImageTool bridge = new ImportProjectImageTool(context, session);
        assertFalse(bridge.execute(args, CancellationToken.crewChild()).success);
        JSONObject result = success(bridge.execute(args, CancellationToken.uncancellable()));
        assertTrue(result.getBoolean("original_preserved")); assertFalse(result.getBoolean("provider_called"));
        assertArrayEquals(png, Files.readAllBytes(store.resolve(session,path).toPath())); assertTrue(scope.resolve("imported.png").isFile()); assertEquals(0,calls.get());
    }
    @Test public void importRejectsCrossConversationReferenceWithoutCopy() throws Exception {
        String id = UUID.randomUUID().toString(), other = "other-"+UUID.randomUUID(); GeneratedImageStore store = new GeneratedImageStore(context);
        String path = store.save(other,id,png); new LocalRunStore(context).appendGeneratedImageEvent(other,path,"blue","blue","8x6","image/png");
        Map<String,Object> args = args("image_ref","generated:"+id,"path","out.png","expected_sha256","missing","expected_scope_version",scope.version());
        assertFalse(new ImportProjectImageTool(context,session).execute(args,CancellationToken.uncancellable()).success); assertEquals("missing",scope.revision("out.png"));
    }
    @Test public void generatedAssetIsCapturedByImmutableHtmlDelivery() throws Exception {
        success(generate(tool(),"assets/hero.png"));
        new ProjectMutationService().apply(scope,"fixture",scope.version(),Collections.singletonList(ProjectMutationService.Operation.add("index.html","<!doctype html><html><body><img src='assets/hero.png'></body></html>")),CancellationToken.uncancellable());
        WorkspaceStore workspace = new WorkspaceStore(context,WorkspaceStore.projectIdForSession(session),session,false);
        JSONObject receipt = success(new DeliverFileTool(context,session,workspace).execute(args("path","/project/index.html"),CancellationToken.uncancellable()));
        assertTrue(receipt.getBoolean("preview_available")); assertEquals(2,receipt.getInt("preview_file_count")); assertEquals(0,receipt.getJSONArray("preview_warnings").length());
        assertEquals(1,calls.get());
    }
    private static byte[] png(int width,int height,boolean noise) {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        if (noise) { java.util.Random random=new java.util.Random(36); int[] pixels=new int[width*height]; for(int i=0;i<pixels.length;i++) pixels[i]=random.nextInt(); bitmap.setPixels(pixels,0,width,0,0,width,height); }
        else bitmap.eraseColor(Color.BLUE);
        ByteArrayOutputStream output=new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); bitmap.recycle(); return output.toByteArray();
    }
    private static String generated(byte[] png) {
        try {return new JSONObject().put("type","response.output_item.done").put("item",new JSONObject().put("type","image_generation_call").put("status","completed").put("result",Base64.encodeToString(png,Base64.NO_WRAP)).put("size","1024x1024").put("output_format","png")).toString();}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private static Map<String,Object> args(Object... pairs){Map<String,Object> values=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;}
}
