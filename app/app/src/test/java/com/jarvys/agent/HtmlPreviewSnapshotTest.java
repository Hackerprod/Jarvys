package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class HtmlPreviewSnapshotTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File files, workspaceRoot;
    private WorkspaceStore workspace;
    private DeliveredArtifactStore store;
    private LocalRunStore ledger;
    private Object priorSecrets;
    private final String session = "html-preview-chat";

    @Before public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        java.lang.reflect.Field singleton = SecretStore.class.getDeclaredField("singleton"); singleton.setAccessible(true);
        priorSecrets = singleton.get(null);
        singleton.set(null, new SecretStore(context.getSharedPreferences("html-preview-fixture", Context.MODE_PRIVATE)));
        files = temporary.getRoot(); ledger = new LocalRunStore(files);
        store = new DeliveredArtifactStore(new File(files, "jarvys"));
        workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), WorkspaceStore.projectIdForSession(session),
                null, null, null, session, false);
        workspaceRoot = new File(files, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(session));
        ledger.appendConversationMessage(session, "user", "Show the HTML app");
    }
    @After public void tearDown() throws Exception {
        java.lang.reflect.Field singleton = SecretStore.class.getDeclaredField("singleton"); singleton.setAccessible(true);
        singleton.set(null, priorSecrets); ArtifactOsShadow.reset(); AgentRunUiState.resetSession(session);
    }
    private CoreToolResult deliver(String path) {
        return new DeliverFileTool(session, workspace, store, ledger).execute(Collections.singletonMap("path", path), CancellationToken.uncancellable());
    }
    private ChatAttachment attachment(CoreToolResult result) throws Exception {
        assertTrue(result.content, result.success);
        String id = new JSONObject(result.content).getString("artifact_id").substring("delivered:".length());
        return ledger.findChatFile(session, "delivered", id);
    }
    private String asset(DeliveredArtifactStore artifacts, HtmlPreviewDescriptor descriptor, String path) throws Exception {
        try (InputStream input = artifacts.openPreview(session, descriptor.token, path)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
    private void write(String path, String content) { workspace.write(path, content); }
    private CoreTool previewTool(WorkspaceStore view) {
        return WorkspaceTools.create(view).stream().filter(t -> t.declaration().name.equals("preview_workspace")).findFirst().get();
    }

    @Test public void capturesHtmlCssImportsScriptsImagesAndRootRelativeAssetsAfterReopen() throws Exception {
        write("site/page.html", "<!doctype html><link rel='stylesheet' href='/styles/main.css'><script type='module' src='app.mjs'></script>"
                + "<img src='../images/a.svg'><img srcset='../images/a.svg 1x, ../images/b.svg 2x'>");
        write("styles/main.css", "@import 'theme.css'; body { background: url('../images/a.svg'); }");
        write("styles/theme.css", "h1 { color: teal }");
        write("site/app.mjs", "import { value } from './lib.js'; import('./lazy.js'); const icon = new URL('../images/b.svg', import.meta.url);");
        write("site/lib.js", "export const value = 42;"); write("site/lazy.js", "console.log('lazy');");
        write("images/a.svg", "<svg xmlns='http://www.w3.org/2000/svg'><circle r='4'/></svg>");
        write("images/b.svg", "<svg xmlns='http://www.w3.org/2000/svg'><rect width='8'/></svg>");
        write("unrelated.js", "Never capture every file in the project");
        CoreToolResult result = deliver("site/page.html"); ChatAttachment attachment = attachment(result);
        HtmlPreviewDescriptor descriptor = store.previewForAttachment(session, attachment);
        assertNotNull(descriptor); assertEquals(descriptor.token, result.previewId);
        assertEquals("site/page.html", descriptor.entryPath); assertEquals(8, descriptor.fileCount);
        assertTrue(descriptor.warnings.toString(), descriptor.warnings.isEmpty());
        write("styles/main.css", "changed"); write("site/page.html", "<html>new source</html>");
        assertTrue(new File(workspaceRoot, "site/lib.js").delete());
        DeliveredArtifactStore reopened = new DeliveredArtifactStore(new File(files, "jarvys"));
        HtmlPreviewDescriptor restored = reopened.resolvePreview(session, descriptor.token);
        assertEquals(descriptor.token, restored.token);
        assertTrue(asset(reopened, restored, "styles/main.css").contains("theme.css"));
        assertEquals("export const value = 42;", asset(reopened, restored, "site/lib.js"));
        assertThrows(java.io.IOException.class, () -> reopened.openPreview(session, restored.token, "unrelated.js"));
        assertFalse(result.content.contains(files.getAbsolutePath()));
    }

    @Test public void unchangedCaptureCoalescesButAssetOnlyChangesCreateNewImmutableArtifact() throws Exception {
        write("index.html", "<html><script src='app.js'></script></html>"); write("app.js", "const version = 1;");
        ChatAttachment first = attachment(deliver("index.html"));
        HtmlPreviewDescriptor old = store.previewForAttachment(session, first);
        ChatAttachment duplicate = attachment(deliver("./index.html")); assertEquals(first, duplicate);
        assertEquals(1, ledger.readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
        write("app.js", "const version = 2;");
        ChatAttachment next = attachment(deliver("index.html")); assertNotEquals(first.id, next.id);
        assertEquals(store.sha256(session, first), store.sha256(session, next));
        assertEquals("const version = 1;", asset(store, old, "app.js"));
        assertEquals("const version = 2;", asset(store, store.previewForAttachment(session, next), "app.js"));
        assertEquals(2, ledger.readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
    }

    @Test public void entryProvenanceAndMissingAssetRecoveryAffectIdentity() throws Exception {
        write("a/index.html", "<html><script src='app.js'></script></html>");
        write("b/index.html", "<html><script src='app.js'></script></html>");
        write("a/app.js", "window.site = 'a';"); write("b/app.js", "window.site = 'b';");
        ChatAttachment a = attachment(deliver("a/index.html")), b = attachment(deliver("b/index.html")); assertNotEquals(a.id, b.id);
        write("index.html", "<html><img src='later.svg'></html>");
        ChatAttachment missing = attachment(deliver("index.html")); assertFalse(store.previewForAttachment(session, missing).warnings.isEmpty());
        write("later.svg", "<svg></svg>"); ChatAttachment complete = attachment(deliver("index.html")); assertNotEquals(missing.id, complete.id);
    }

    @Test public void encodedEscapesPrivateZonesSymlinkDependenciesAndOtherChatsStayBlocked() throws Exception {
        write("index.html", "<html><img src='../outside.svg'><img src='%2e%2e/outside.svg'><img src='%252e%252e/outside.svg'>"
                + "<img src='memory/private.svg'><img src='skills/private.svg'><img src='attachments/private.svg'>"
                + "<img src='link.svg'><img src='linked/private.svg'><img src='https://example.invalid/image.svg'></html>");
        File outside = temporary.newFolder("private-assets"); Files.write(new File(outside, "private.svg").toPath(), "<svg>secret</svg>".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(workspaceRoot, "link.svg").toPath(), new File(outside, "private.svg").toPath());
        Files.createSymbolicLink(new File(workspaceRoot, "linked").toPath(), outside.toPath());
        ChatAttachment attachment = attachment(deliver("index.html")); HtmlPreviewDescriptor preview = store.previewForAttachment(session, attachment);
        assertEquals(1, preview.fileCount); assertFalse(preview.warnings.isEmpty());
        for (String path : Arrays.asList("../private.svg", "/index.html", "%2e%2e/private.svg", "%252e%252e/private.svg", "a/../index.html", "memory/private.svg", "link.svg", "linked/private.svg", "index.html?secret"))
            assertThrows(path, java.io.IOException.class, () -> store.openPreview(session, preview.token, path));
        assertThrows(IllegalArgumentException.class, () -> store.resolvePreview("other-chat", preview.token));
        assertThrows(IllegalArgumentException.class, () -> store.previewForAttachment("other-chat", attachment));
        assertFalse(deliver("link.svg").success);
    }

    @Test public void parserNormalizesOnlyInRootTraversalAndRejectsDangerousEncodings() throws Exception {
        assertEquals("images/logo.svg", HtmlPreviewCapture.resolveReference("styles/main.css", "../images/logo.svg?version=2#icon"));
        assertEquals("assets/logo.svg", HtmlPreviewCapture.resolveReference("site/index.html", "/assets/logo.svg"));
        assertEquals("images/my icon.svg", HtmlPreviewCapture.resolveReference("index.html", "images/my%20icon.svg"));
        for (String path : Arrays.asList("../../outside.svg", "%2fetc/passwd", "images/%2e%2e/private.svg", "images%5cprivate.svg", "%252fprivate.svg", "images/evil%00.svg", "images/.hidden.svg", "skills/app.js"))
            assertThrows(path, java.io.IOException.class, () -> HtmlPreviewCapture.resolveReference("index.html", path));
        assertNull(HtmlPreviewCapture.resolveReference("index.html", "file:///private/secret.svg"));
        assertNull(HtmlPreviewCapture.resolveReference("index.html", "content://private/secret.svg"));
        assertNull(HtmlPreviewCapture.resolveReference("index.html", "data:image/svg+xml;base64,PHN2Zz4="));
    }

    @Test public void missingOversizedAndDynamicAssetsAreReportedWithoutCopyingRepository() throws Exception {
        write("index.html", "<html><img src='missing.png'><img src='huge.png'><script>fetch('private.json'); import(name);</script></html>");
        try (RandomAccessFile large = new RandomAccessFile(new File(workspaceRoot, "huge.png"), "rw")) { large.setLength(HtmlPreviewCapture.MAX_FILE_BYTES + 1); }
        write("unused.css", "body {color:red}");
        CoreToolResult result = deliver("index.html"); HtmlPreviewDescriptor preview = store.previewForAttachment(session, attachment(result));
        assertEquals(1, preview.fileCount); assertTrue(preview.warnings.toString().contains("capture limits"));
        assertTrue(preview.warnings.toString().contains("Dynamic"));
        assertTrue(new JSONObject(result.content).getJSONArray("preview_warnings").length() >= 2);
        assertThrows(java.io.IOException.class, () -> store.openPreview(session, preview.token, "unused.css"));
    }

    @Test public void boundedFileCountAndTotalBytesRemainHonest() throws Exception {
        StringBuilder html = new StringBuilder("<html>");
        for (int i = 0; i < 140; i++) { html.append("<img src='i").append(i).append(".svg'>"); write("i" + i + ".svg", "<svg></svg>"); }
        write("index.html", html.append("</html>").toString());
        HtmlPreviewDescriptor count = store.previewForAttachment(session, attachment(deliver("index.html")));
        assertEquals(HtmlPreviewCapture.MAX_FILES, count.fileCount); assertTrue(count.warnings.toString().contains("file or total-byte limit"));
        html = new StringBuilder("<html>");
        for (int i = 0; i < 9; i++) {
            html.append("<img src='big").append(i).append(".png'>");
            try (RandomAccessFile large = new RandomAccessFile(new File(workspaceRoot, "big" + i + ".png"), "rw")) { large.setLength(HtmlPreviewCapture.MAX_FILE_BYTES); }
        }
        write("index.html", html.append("</html>").toString());
        HtmlPreviewDescriptor bytes = store.previewForAttachment(session, attachment(deliver("index.html")));
        assertTrue(bytes.totalBytes <= HtmlPreviewCapture.MAX_TOTAL_BYTES); assertFalse(bytes.warnings.isEmpty());
        assertEquals(8, bytes.fileCount);
    }

    @Test public void binaryHtmlSpoofedNamesAndApksStayBlockedWhileLegacyHtmlUsesOnlySavedOriginal() throws Exception {
        write("binary.html", "<html>valid prefix</html>"); Files.write(new File(workspaceRoot, "binary.html").toPath(), new byte[] {'<','h','t','m','l','>',0,1,2});
        CoreToolResult binary = deliver("binary.html"); assertTrue(binary.success); assertNull(binary.previewId);
        assertNull(store.previewForAttachment(session, attachment(binary))); assertFalse(new JSONObject(binary.content).getBoolean("preview_available"));
        write("package.apk", "<html>This is not HTML by source type</html>");
        Map<String,Object> args = new LinkedHashMap<>(); args.put("path", "package.apk"); args.put("filename", "renamed.html");
        CoreToolResult renamed = new DeliverFileTool(session, workspace, store, ledger).execute(args, CancellationToken.uncancellable());
        assertTrue(renamed.success); assertNull(renamed.previewId);
        CoreToolResult apk = deliver("package.apk"); assertNull(apk.previewId); assertEquals("application/vnd.android.package-archive", attachment(apk).mimeType);
        ChatAttachment connector = store.snapshotBytes(session, "<html>legacy single file</html>".getBytes(StandardCharsets.UTF_8), "legacy.html", "text/html", CancellationToken.uncancellable());
        HtmlPreviewDescriptor legacy = store.previewForAttachment(session, connector);
        assertNotNull(legacy); assertEquals(1, legacy.fileCount); assertTrue(legacy.warnings.toString().contains("linked assets were not captured"));
        assertEquals("<html>legacy single file</html>", asset(store, legacy, legacy.entryPath));
        assertThrows(java.io.IOException.class, () -> store.openPreview(session, legacy.token, "missing.css"));
        ChatAttachment spoofed = store.snapshotBytes(session, new byte[] {'<', 'h', 't', 'm', 'l', '>', 0}, "spoofed.html", "text/html", CancellationToken.uncancellable());
        assertNull(store.previewForAttachment(session, spoofed));
        assertThrows(IllegalArgumentException.class, () -> store.resolvePreview(session, HtmlPreviewDescriptor.TOKEN_PREFIX + spoofed.id));
        assertNotNull(store.resolve(session, connector));
    }

    @Test public void explicitProjectPreviewPersistsOwnedSnapshotAndLegacyPreviewRemainsCompatible() throws Exception {
        File coding = workspace.codingProjectScope().rootDirectory();
        File site = new File(coding, "site"); assertTrue(site.mkdir());
        Files.write(new File(site, "app.html").toPath(), "<html><script src='main.js'></script></html>".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(site, "main.js").toPath(), "window.working = true;".getBytes(StandardCharsets.UTF_8));
        CoreToolResult result = previewTool(workspace).execute(Collections.singletonMap("path", "/project/site/app.html"), CancellationToken.uncancellable());
        ChatAttachment attachment = attachment(result); assertNotNull(attachment);
        assertTrue(HtmlPreviewDescriptor.isSnapshotToken(result.previewId));
        assertEquals("site/app.html", store.previewForAttachment(session, attachment).entryPath);
        assertFalse(previewTool(workspace.forDelegatedAgent()).execute(Collections.singletonMap("path", "/project/site/app.html"), CancellationToken.crewChild()).success);
        write("index.html", "<html>legacy</html>");
        assertEquals(workspace.projectId(), previewTool(workspace).execute(Collections.emptyMap(), CancellationToken.uncancellable()).previewId);
    }

    @Test public void assetCorruptionAndManifestOwnershipChangesFailClosed() throws Exception {
        write("index.html", "<html><script src='app.js'></script></html>"); write("app.js", "let x = 1;");
        ChatAttachment attachment = attachment(deliver("index.html")); HtmlPreviewDescriptor preview = store.previewForAttachment(session, attachment);
        File asset = new File(files, "jarvys/delivered/" + session + "/" + attachment.id + ".preview/app.js");
        Files.write(asset.toPath(), "let x = 2;".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> store.resolvePreview(session, preview.token));
        assertThrows(java.io.IOException.class, () -> store.openPreview(session, preview.token, "app.js"));
        Files.write(asset.toPath(), "let x = 1;".getBytes(StandardCharsets.UTF_8));
        File manifest = new File(files, "jarvys/delivered/" + session + "/" + attachment.id + ".json");
        JSONObject json = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        json.put("conversation", "other-chat"); Files.write(manifest.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> store.resolvePreview(session, preview.token));
    }

    @Test public void readOnlyReopenDoesNotInitializePreferencesCredentialsOrMutableWorkspace() throws Exception {
        write("index.html", "<html>static offline</html>"); ChatAttachment attachment = attachment(deliver("index.html"));
        assertTrue(new File(workspaceRoot, "index.html").delete());
        Context app = ApplicationProvider.getApplicationContext();
        Context failIfCredentials = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { throw new AssertionError("Preview must not initialize credentials or settings"); }
        };
        DeliveredArtifactStore reopened = new DeliveredArtifactStore(failIfCredentials);
        HtmlPreviewDescriptor preview = reopened.previewForAttachment(session, attachment);
        assertEquals("<html>static offline</html>", asset(reopened, preview, preview.entryPath));
    }

    @Test public void supportedScriptImageAndFontExtensionsHaveExactNoSniffMimeTypes() {
        assertEquals("application/javascript", WorkspaceStore.mimeType("app.mjs"));
        assertEquals("application/javascript", WorkspaceStore.mimeType("app.js"));
        assertEquals("image/avif", WorkspaceStore.mimeType("photo.avif"));
        assertEquals("image/bmp", WorkspaceStore.mimeType("photo.bmp"));
        assertEquals("font/otf", WorkspaceStore.mimeType("type.otf"));
    }

    @Test public void trustedPlatformFilesDirAliasWorksButWorkspaceDescendantSymlinksDoNot() throws Exception {
        File physical = temporary.newFolder("physical-files");
        File alias = new File(temporary.getRoot(), "platform-files-alias"); Files.createSymbolicLink(alias.toPath(), physical.toPath());
        String chat = "aliased-preview";
        WorkspaceStore aliased = new WorkspaceStore(new File(alias, "jarvys/workspaces"), WorkspaceStore.projectIdForSession(chat),
                null, null, null, chat, false);
        aliased.write("index.html", "<html><link rel='stylesheet' href='theme.css'></html>"); aliased.write("theme.css", "body { color: green }");
        DeliveredArtifactStore aliasedArtifacts = new DeliveredArtifactStore(new File(alias, "jarvys"));
        ChatAttachment attachment = aliasedArtifacts.snapshot(chat, aliased, "index.html", null, CancellationToken.uncancellable());
        HtmlPreviewDescriptor preview = aliasedArtifacts.previewForAttachment(chat, attachment);
        assertEquals(2, preview.fileCount);
        File workspace = new File(physical, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(chat));
        File saved = new File(physical, "saved-workspace"); Files.move(workspace.toPath(), saved.toPath());
        Files.createSymbolicLink(workspace.toPath(), saved.toPath());
        assertThrows(java.io.IOException.class, () -> aliasedArtifacts.snapshot(chat, aliased, "index.html", null, CancellationToken.uncancellable()));
        assertEquals(2, aliasedArtifacts.resolvePreview(chat, preview.token).fileCount);
    }

    @Test(timeout = 15000) public void malformedNestedCssUrlsStayBoundedAndDoNotLeakPartialAssets() throws Exception {
        write("index.html", "<html><link rel='stylesheet' href='bad.css'></html>");
        StringBuilder malformed = new StringBuilder();
        while (malformed.length() < 240000) malformed.append("url(");
        write("bad.css", malformed.toString());
        HtmlPreviewDescriptor preview = store.previewForAttachment(session, attachment(deliver("index.html")));
        assertEquals(2, preview.fileCount);
    }

    @Test @Config(sdk = 24, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
    public void api24CaptureUsesVerifiedStreamsAndSurvivesReopen() throws Exception {
        write("index.html", "<html><link rel='stylesheet' href='site.css'></html>"); write("site.css", "body { color: navy }");
        ChatAttachment attachment = attachment(deliver("index.html"));
        DeliveredArtifactStore reopened = new DeliveredArtifactStore(new File(files, "jarvys"));
        assertEquals("body { color: navy }", asset(reopened, reopened.previewForAttachment(session, attachment), "site.css"));
    }
}
