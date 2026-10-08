package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.crew.BotDefinition;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewProfileRepository;
import com.jarvys.agent.proactive.ProactiveConversation;
import com.jarvys.agent.tasks.ScheduledTaskConversation;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BotIconServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    Context context;
    ProviderSettings settings;
    SecretStore secrets;
    CrewProfileRepository repository;
    BotIconStore icons;
    BotDefinition original;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        settings = new ProviderSettings(context);
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        secrets = new SecretStore(context.getSharedPreferences("bot-icon-test", Context.MODE_PRIVATE));
        secrets.saveCodexTokens("test-private-access", "test-private-refresh", System.currentTimeMillis() + 3600000L, "test-account");
        repository = new CrewProfileRepository(temporary.getRoot());
        icons = new BotIconStore(temporary.getRoot());
        CrewProfile profile = new CrewProfile("custom-orchid", 1, "Orchid", "A specialist", "Keep these runtime instructions untouched.",
                Collections.emptyList(), Collections.singletonList("board_read"));
        original = repository.create(profile, profile.capabilities, Collections.emptyList());
        original = repository.setIcon(original.id, original.revision,
                icons.save(original.id, BotIconStoreTest.png(8, 8), CancellationToken.uncancellable()));
    }

    @After public void cleanup() { settings.setProvider(ProviderSettings.Provider.OPENROUTER); secrets.clearCodexTokens(); }

    @Test public void forwardsArbitraryPromptWithoutHistoryAndUpdatesOnlyIconMetadata() throws Exception {
        String prompt = "  An origami axolotl studying starlight, copper watercolor; invent a nonliteral composition.\nSin letras.  ";
        AtomicReference<JSONObject> request = new AtomicReference<>();
        String previousProfile = original.profile.toJson().toString();
        BotDefinition saved = service((body, session, token) -> { request.set(body); return success(); })
                .generateAndAssign(original.id, original.revision, prompt, CancellationToken.uncancellable());
        assertEquals("Generate a new image from this user request: " + prompt,
                request.get().getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals(1, request.get().getJSONArray("input").length());
        assertEquals(1, request.get().getJSONArray("tools").length());
        assertFalse(request.get().toString().contains("image_url"));
        assertFalse(request.get().toString().contains("test-private"));
        assertFalse(request.get().toString().contains(original.profile.prompt));
        assertEquals(previousProfile, saved.profile.toJson().toString());
        assertEquals(original.revision + 1, saved.revision);
        assertEquals(original.profile.version, saved.profile.version);
        assertEquals(original.enabled, saved.enabled);
        assertNotEquals(original.iconRef, saved.iconRef);
        assertTrue(icons.resolve(saved.id, saved.iconRef).isFile());
        assertEquals(saved.iconRef, new CrewProfileRepository(temporary.getRoot()).definition(saved.id).iconRef);
        assertEquals(1, iconFiles().length);
    }

    @Test public void cancellationDuringGenerationLeavesExistingIconAndNoStagedImage() {
        CancellationToken token = CancellationToken.cancellable();
        BotIconService service = service((body, session, current) -> { token.cancel(); return success(); });
        assertThrows(CancellationException.class, () -> service.generateAndAssign(original.id, original.revision, "freeform", token));
        unchanged();
        assertEquals(1, iconFiles().length);
    }

    @Test public void alreadyCancelledRequestDoesNotReachBackend() {
        AtomicInteger requests = new AtomicInteger();
        BotIconService service = service((body, session, token) -> { requests.incrementAndGet(); return success(); });
        CancellationToken token = CancellationToken.cancellable();
        token.cancel();
        assertThrows(CancellationException.class, () -> service.generateAndAssign(original.id, original.revision, "freeform", token));
        assertEquals(0, requests.get());
        unchanged();
    }

    @Test public void cancelBeforeCommitGatePreservesOriginalEvenWithPreparedIcon() {
        CancellationToken token = CancellationToken.cancellable();
        String prepared = icons.save(original.id, BotIconStoreTest.png(12, 12), token);
        try (BotIconService.CommitGate gate = new BotIconService.CommitGate(token)) {
            token.cancel();
            assertThrows(CancellationException.class, () -> gate.commit(
                    () -> repository.setIcon(original.id, original.revision, prepared)));
            unchanged();
        } finally { icons.delete(original.id, prepared); }
        assertEquals(1, iconFiles().length);
    }

    @Test public void commitGateSerializesConcurrentCancelAndTreatsLateCancelAsCommitted() throws Exception {
        CancellationToken token = CancellationToken.cancellable();
        String prepared = icons.save(original.id, BotIconStoreTest.png(12, 12), token);
        java.util.concurrent.CountDownLatch commitStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishCommit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch cancelAttempted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (BotIconService.CommitGate gate = new BotIconService.CommitGate(token)) {
            java.util.concurrent.Future<?> commit = workers.submit(() -> gate.commit(() -> {
                commitStarted.countDown();
                try {
                    if (!finishCommit.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Commit was not released");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                repository.setIcon(original.id, original.revision, prepared);
            }));
            assertTrue(commitStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<Boolean> cancel = workers.submit(() -> {
                cancelAttempted.countDown();
                return token.cancel();
            });
            assertTrue(cancelAttempted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            // The final-action gate already owns this dispatch. Cancellation must wait for it,
            // then latch before any subsequent dispatch can start.
            assertFalse(cancel.isDone());
            assertFalse(token.isCancellationRequested());
            unchanged();
            finishCommit.countDown();
            commit.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(cancel.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(token.isCancellationRequested());
            assertEquals(prepared, repository.definition(original.id).iconRef);
            assertEquals(original.revision + 1, repository.definition(original.id).revision);
            assertTrue(icons.resolve(original.id, prepared).isFile());
            assertThrows(CancellationException.class, () -> gate.commit(
                    () -> fail("Cancellation must prevent a subsequent icon commit")));
        } finally { finishCommit.countDown(); workers.shutdownNow(); }
    }

    @Test public void newerIconWinsCasAndLosingGeneratedFileIsRemoved() {
        AtomicReference<BotDefinition> winner = new AtomicReference<>();
        BotIconService service = service((body, session, token) -> {
            winner.set(repository.setIcon(original.id, original.revision,
                    icons.save(original.id, BotIconStoreTest.png(10, 10), token)));
            return success();
        });
        BotIconService.Failure failure = assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(original.id, original.revision, "freeform", CancellationToken.uncancellable()));
        assertEquals(BotIconService.Reason.CONFLICT, failure.reason);
        assertEquals(winner.get().iconRef, repository.definition(original.id).iconRef);
        assertTrue(icons.resolve(original.id, original.iconRef).isFile());
        assertTrue(icons.resolve(original.id, winner.get().iconRef).isFile());
        assertEquals(2, iconFiles().length);
    }

    @Test public void simultaneousGeneratorsHaveExactlyOneWinnerAndNoOrphanedFiles() throws Exception {
        java.util.concurrent.CountDownLatch bothGenerating = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        BotIconService service = service((body, session, token) -> {
            bothGenerating.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Test generator was not released");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            return success();
        });
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Object> generate = () -> {
                try { return service.generateAndAssign(original.id, original.revision, "freeform", CancellationToken.cancellable()); }
                catch (BotIconService.Failure failure) { return failure; }
            };
            java.util.concurrent.Future<Object> first = workers.submit(generate);
            java.util.concurrent.Future<Object> second = workers.submit(generate);
            assertTrue(bothGenerating.await(10, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            Object a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            Object b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((a instanceof BotDefinition) != (b instanceof BotDefinition));
            BotDefinition saved = (BotDefinition) (a instanceof BotDefinition ? a : b);
            BotIconService.Failure failure = (BotIconService.Failure) (a instanceof BotDefinition ? b : a);
            assertEquals(BotIconService.Reason.CONFLICT, failure.reason);
            assertEquals(saved.iconRef, repository.definition(original.id).iconRef);
            assertTrue(icons.resolve(original.id, saved.iconRef).isFile());
            assertEquals(1, iconFiles().length);
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void disablingDuringGenerationKeepsPreviousIconAndRemovesNewBytes() {
        BotIconService service = service((body, session, token) -> {
            repository.setEnabled(original.id, original.revision, false);
            return success();
        });
        BotIconService.Failure failure = assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(original.id, original.revision, "freeform", CancellationToken.uncancellable()));
        assertEquals(BotIconService.Reason.DISABLED, failure.reason);
        assertEquals(original.iconRef, repository.definition(original.id).iconRef);
        assertEquals(1, iconFiles().length);
    }

    @Test public void providerChangeBeforeCompletionNeverFallsBackOrAssigns() {
        AtomicInteger requests = new AtomicInteger();
        BotIconService service = service((body, session, token) -> {
            requests.incrementAndGet(); settings.setProvider(ProviderSettings.Provider.OPENAI_API); return success();
        });
        BotIconService.Failure failure = assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(original.id, original.revision, "freeform", CancellationToken.uncancellable()));
        assertEquals(BotIconService.Reason.UNAVAILABLE, failure.reason);
        assertEquals(1, requests.get());
        unchanged();
        assertEquals(1, iconFiles().length);
    }

    @Test public void backendErrorsArePrivateAndPreserveExistingImage() {
        BotIconService service = service((body, session, token) -> { throw new CodexImageGenerationException(
                CodexImageGenerationException.Kind.QUOTA, 429, "private-code", "secret prompt and token", 0, "private-url", null); });
        BotIconService.Failure failure = assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(original.id, original.revision, "secret prompt", CancellationToken.uncancellable()));
        assertEquals(BotIconService.Reason.QUOTA, failure.reason);
        assertFalse(failure.toString().contains("secret"));
        assertFalse(failure.toString().contains("private"));
        assertNull(failure.getCause());
        unchanged();
    }

    @Test public void invalidImageCannotReplaceExistingImage() {
        byte[] invalid = BotIconStoreTest.png(8, 8);
        byte[] trailingPayload = java.util.Arrays.copyOf(invalid, invalid.length + 1);
        BotIconService service = service((body, session, token) -> response(trailingPayload));
        BotIconService.Failure failure = assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(original.id, original.revision, "freeform", CancellationToken.uncancellable()));
        assertEquals(BotIconService.Reason.INVALID_IMAGE, failure.reason);
        unchanged();
        assertEquals(1, iconFiles().length);
    }

    @Test public void builtinsMissingDisabledAndStaleTargetsAreRejectedBeforeBackend() {
        AtomicInteger requests = new AtomicInteger();
        BotIconService service = service((body, session, token) -> { requests.incrementAndGet(); return success(); });
        for (String builtin : new String[]{"coding", "android-use"}) {
            BotDefinition builtIn = repository.definition(builtin);
            assertEquals(BotIconService.Reason.BUILT_IN, assertThrows(BotIconService.Failure.class,
                    () -> service.generateAndAssign(builtin, builtIn.revision, "freeform", CancellationToken.uncancellable())).reason);
        }
        assertEquals(BotIconService.Reason.INVALID_TARGET, assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign("custom-missing", 1, "freeform", CancellationToken.uncancellable())).reason);
        assertEquals(BotIconService.Reason.CONFLICT, assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(original.id, original.revision - 1, "freeform", CancellationToken.uncancellable())).reason);
        BotDefinition disabled = repository.setEnabled(original.id, original.revision, false);
        assertEquals(BotIconService.Reason.DISABLED, assertThrows(BotIconService.Failure.class,
                () -> service.generateAndAssign(disabled.id, disabled.revision, "freeform", CancellationToken.uncancellable())).reason);
        assertEquals(0, requests.get());
    }

    @Test public void mainChatAvailabilityRequiresExistingCodexAccessAndBlocksBackgroundScopes() {
        BotIconService service = service((body, session, token) -> success());
        assertTrue(service.isAvailable());
        for (String session : new String[]{ProactiveConversation.SESSION_ID, ScheduledTaskConversation.SESSION_ID, ""}) {
            assertFalse(new BotIconService(context, session, settings, secrets, repository, icons,
                    client((body, id, token) -> success())).isAvailable());
        }
        assertThrows(BotIconService.Failure.class, () -> service.generateAndAssign(original.id, original.revision, "freeform", CancellationToken.crewChild()));
        settings.setProvider(ProviderSettings.Provider.OPENAI_API);
        assertFalse(service.isAvailable());
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        secrets.clearCodexTokens();
        assertFalse(service.isAvailable());
    }

    @Test public void invalidPromptsDoNotReachBackendOrMutateCatalog() {
        AtomicInteger requests = new AtomicInteger();
        BotIconService service = service((body, session, token) -> { requests.incrementAndGet(); return success(); });
        for (String invalid : new String[]{"", "  \n ", "hidden\0payload", String.join("", Collections.nCopies(BotIconService.MAX_PROMPT_CHARS + 1, "x"))}) {
            assertEquals(BotIconService.Reason.INVALID_PROMPT, assertThrows(BotIconService.Failure.class,
                    () -> service.generateAndAssign(original.id, original.revision, invalid, CancellationToken.uncancellable())).reason);
        }
        assertEquals(0, requests.get());
        unchanged();
    }

    @Test public void toolRejectsExtraReferencesAndNonintegralRevisionsWithoutGeneration() {
        AtomicInteger requests = new AtomicInteger();
        BotIconGenerationTool tool = new BotIconGenerationTool(service((body, session, token) -> { requests.incrementAndGet(); return success(); }));
        java.util.Map<String, Object> args = arguments();
        args.put("image_refs", Collections.singletonList("generated:private-chat-image"));
        assertFalse(tool.execute(args, CancellationToken.uncancellable()).success);
        args.remove("image_refs");
        args.put("expected_revision", original.revision + 0.5);
        assertFalse(tool.execute(args, CancellationToken.uncancellable()).success);
        args.put("expected_revision", String.valueOf(original.revision));
        assertFalse(tool.execute(args, CancellationToken.uncancellable()).success);
        args.put("expected_revision", original.revision);
        assertFalse(tool.execute(args, CancellationToken.crewChild()).success);
        assertEquals(0, requests.get());
        unchanged();
    }

    @Test public void iconToolResultAndAuditNeverExposePrivatePromptPathOrImagePayload() {
        BotIconGenerationTool tool = new BotIconGenerationTool(service((body, session, token) -> success()));
        java.util.Map<String, Object> args = arguments();
        args.put("prompt", "PRIVATE_ORCHID_PROMPT");
        CoreToolResult result = tool.execute(args, CancellationToken.uncancellable());
        assertTrue(result.success);
        assertTrue(result.content.contains(original.id));
        assertFalse(result.content.contains("PRIVATE_ORCHID_PROMPT"));
        assertFalse(result.content.contains(".png"));
        assertFalse(result.content.contains("bot_icons"));
        assertFalse(result.content.contains("base64"));
        assertFalse(tool.auditDetail(args).contains("PRIVATE_ORCHID_PROMPT"));
        assertFalse(tool.canDelegate());
        assertEquals(false, tool.declaration().jsonSchema().get("additionalProperties"));
        assertTrue(tool.declaration().description.contains("only when the user explicitly asks"));
        assertTrue(tool.declaration().description.contains("quota"));
    }

    @Test public void catalogExposesOnlyBoundedMetadataAndExactRevisionWithCrewOff() throws Exception {
        BotCatalogTool catalog = new BotCatalogTool(repository, "main-bot-test");
        CoreToolResult result = catalog.execute(Collections.emptyMap(), CancellationToken.uncancellable());
        assertTrue(result.success);
        JSONObject body = new JSONObject(result.content);
        org.json.JSONArray bots = body.getJSONArray("bots");
        assertEquals(3, bots.length());
        JSONObject custom = null;
        for (int i = 0; i < bots.length(); i++) {
            JSONObject bot = bots.getJSONObject(i);
            if (original.id.equals(bot.getString("bot_id"))) custom = bot;
            if (bot.getBoolean("built_in")) assertFalse(bot.getBoolean("icon_editable"));
            assertFalse(bot.has("prompt"));
            assertFalse(bot.has("capabilities"));
            assertFalse(bot.has("skillIds"));
            assertFalse(bot.has("iconRef"));
        }
        assertNotNull(custom);
        assertEquals(original.revision, custom.getInt("revision"));
        assertFalse(result.content.contains(original.profile.prompt));
        assertFalse(result.content.contains(original.iconRef));
        assertTrue(BotCatalogTool.isAvailable(context, 0, "main-bot-test"));
        assertFalse(BotCatalogTool.isAvailable(context, 1, "main-bot-test"));
        assertFalse(BotCatalogTool.isAvailable(context, 0, ProactiveConversation.SESSION_ID));
        assertFalse(BotCatalogTool.isAvailable(context, 0, ScheduledTaskConversation.SESSION_ID));
        assertFalse(catalog.execute(Collections.emptyMap(), CancellationToken.crewChild()).success);
        assertFalse(catalog.canDelegate());
    }

    @Test public void iconAndCatalogToolsAreNeverInheritedByCrewOrDelegation() {
        BotIconGenerationTool icon = new BotIconGenerationTool(service((body, session, token) -> success()));
        BotCatalogTool catalog = new BotCatalogTool(repository, "main-bot-test");
        CoreToolRegistry tools = new CoreToolRegistry(java.util.Arrays.asList(icon, catalog));
        assertTrue(tools.forDelegatedAgent().names().isEmpty());
        assertTrue(CoreAgentRuntime.crewBotCapabilityScope(tools).names().isEmpty());
        CoreToolRegistry main = new CoreAgentRuntime(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                java.util.Arrays.asList(icon, catalog)).createTools();
        assertTrue(main.names().contains(BotIconGenerationTool.NAME));
        assertTrue(main.names().contains(BotCatalogTool.NAME));
        for (ToolSpec declaration : main.declarations()) if ("delegate_subtask".equals(declaration.name)) {
            assertFalse(declaration.description.contains(BotIconGenerationTool.NAME));
            assertFalse(declaration.description.contains(BotCatalogTool.NAME));
        }
    }

    @Test public void catalogPaginatesWithoutDroppingExactIdentityOrRevision() throws Exception {
        for (int i = 0; i < 25; i++) {
            CrewProfile profile = new CrewProfile("custom-page-" + i, 1, "Bot " + i, "Description", "PRIVATE_RUNTIME_PROMPT",
                    Collections.emptyList(), Collections.emptyList());
            repository.create(profile, Collections.emptyList(), Collections.emptyList());
        }
        BotCatalogTool catalog = new BotCatalogTool(repository, "main-bot-test");
        JSONObject first = new JSONObject(catalog.execute(Collections.emptyMap(), CancellationToken.uncancellable()).content);
        assertEquals(25, first.getJSONArray("bots").length());
        int offset = first.getInt("next_offset");
        JSONObject second = new JSONObject(catalog.execute(Collections.singletonMap("offset", offset), CancellationToken.uncancellable()).content);
        assertEquals(3, second.getJSONArray("bots").length());
        assertTrue(second.isNull("next_offset"));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JSONObject page : new JSONObject[]{first, second}) {
            org.json.JSONArray bots = page.getJSONArray("bots");
            for (int i = 0; i < bots.length(); i++) assertTrue(ids.add(bots.getJSONObject(i).getString("bot_id")));
            assertFalse(page.toString().contains("PRIVATE_RUNTIME_PROMPT"));
        }
        assertEquals(28, ids.size());
        assertFalse(catalog.execute(Collections.singletonMap("offset", 0.2), CancellationToken.uncancellable()).success);
    }

    private java.util.Map<String, Object> arguments() {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("bot_id", original.id);
        args.put("expected_revision", original.revision);
        args.put("prompt", "A freeform icon");
        return args;
    }

    BotIconService service(CodexImageGenerationClient.RequestExecutor executor) {
        return new BotIconService(context, "main-bot-test", settings, secrets, repository, icons, client(executor));
    }
    CodexImageGenerationClient client(CodexImageGenerationClient.RequestExecutor executor) {
        return new CodexImageGenerationClient(settings, executor, (body, session, token) -> { throw new AssertionError("Icon generation must never call edits"); });
    }
    static ProviderHttp.Response success() { return response(BotIconStoreTest.png(16, 16)); }
    static ProviderHttp.Response response(byte[] bytes) {
        String body = "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\""
                + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\",\"size\":\"1024x1024\",\"output_format\":\"png\"}}";
        return new ProviderHttp.Response(200, body, body);
    }
    void unchanged() {
        BotDefinition current = repository.definition(original.id);
        assertEquals(original.iconRef, current.iconRef);
        assertEquals(original.revision, current.revision);
        assertTrue(icons.resolve(current.id, current.iconRef).isFile());
    }
    File[] iconFiles() { return new File(temporary.getRoot(), "bot_icons/" + original.id).listFiles(); }
}
