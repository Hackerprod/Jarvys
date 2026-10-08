package com.jarvys.agent;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewMode;
import com.jarvys.agent.crew.CrewTools;
import com.jarvys.agent.device.ScreenData;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Scripted runtime contracts, not a claim about live-model title quality. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewMissionTitleRuntimeTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());
    private Context context;
    private Object previousSecrets;

    @Before public void installTestSecrets() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        Field field = SecretStore.class.getDeclaredField("singleton");
        field.setAccessible(true);
        previousSecrets = field.get(null);
        android.content.SharedPreferences preferences = context.getSharedPreferences("crew-title-secrets", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        field.set(null, new SecretStore(preferences));
        CrewMode.write(context, CrewMode.AUTO);
    }

    @After public void restoreTestSecrets() throws Exception {
        Field field = SecretStore.class.getDeclaredField("singleton");
        field.setAccessible(true);
        field.set(null, previousSecrets);
    }

    @Test public void effectiveCaptainGuidanceRequiresSemanticSameTurnTitleOnlyWhenCrewIsAvailable() {
        CoreAgentRuntime runtime = new CoreAgentRuntime(context, "title-guidance", Collections.emptyList());
        try (CrewManager manager = manager("title-guidance", new AtomicInteger())) {
            String absent = runtime.instructions(EMPTY, true);
            assertFalse(absent.contains("supply task_title"));
            String present = runtime.instructions(new CoreToolRegistry(CrewTools.captain(manager, EMPTY)), true);
            for (String expected : Arrays.asList("same turn as your first crew_spawn", "semantic title",
                    "entire user mission", "3–6 words", "user's language", "60 Unicode code points",
                    "independently of bot name", "full instructions in mission", "first valid title",
                    "Do not make a separate title-generation call")) {
                assertTrue(expected, present.contains(expected));
            }
            CrewMode.write(context, CrewMode.OFF);
            assertFalse(runtime.instructions(new CoreToolRegistry(CrewTools.captain(manager, EMPTY)), true)
                    .contains("supply task_title"));
        }
    }

    @Test public void realCaptainLoopCarriesTitleInSpawnWithoutAdditionalModelRequest() throws Exception {
        String session = "title-loop";
        String request = "  Compara las fuentes y explica su coherencia.\nIncluye las diferencias y las fechas exactas.  ";
        String workerMission = "  Revisa la primera fuente.\nDevuelve todas las fechas con evidencia.  ";
        String title = "Revisar coherencia de fuentes";
        AtomicInteger captainCalls = new AtomicInteger(), workerCalls = new AtomicInteger();
        CoreAgentRuntime runtime = new CoreAgentRuntime(context, session, Collections.emptyList());
        try (CrewManager manager = manager(session, workerCalls)) {
            manager.beginMission("mission", request);
            CoreToolRegistry captain = new CoreToolRegistry(CrewTools.captain(manager, EMPTY));
            ModelProviderClient provider = new ModelProviderClient() {
                @Override public ModelReply complete(String systemPrompt, String userPrompt, List<ScreenData> images,
                        List<ToolSpec> tools, String sessionId, CancellationToken token) {
                    throw new AssertionError("Unexpected non-conversation provider request");
                }
                @Override public ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                        String userPrompt, List<ScreenData> images, List<ToolSpec> tools, String sessionId, CancellationToken token) {
                    assertTrue(systemPrompt.contains("same turn as your first crew_spawn"));
                    assertTrue(tools.stream().anyMatch(spec -> "crew_spawn".equals(spec.name)
                            && spec.jsonSchema().toString().contains("task_title")));
                    if (captainCalls.incrementAndGet() == 1) {
                        assertEquals(request.trim(), userPrompt);
                        Map<String,Object> arguments = new LinkedHashMap<>();
                        arguments.put("role", "custom"); arguments.put("name", "Explorador");
                        arguments.put("tools", Collections.emptyList()); arguments.put("mission", workerMission);
                        arguments.put("task_title", title);
                        return new ModelReply("", Collections.singletonList(new ModelReply.Call("spawn", "crew_spawn", arguments)));
                    }
                    assertEquals(2, captainCalls.get());
                    assertTrue(history.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                            && turn.content.contains("Spawned Explorador")));
                    return new ModelReply("Scripted captain result", Collections.emptyList());
                }
            };
            CoreAgentLoop loop = new CoreAgentLoop(new CoreAgentModel(provider, session), captain,
                    runtime.instructions(captain, true), session);
            loop.run(request, Collections.emptyList(), CancellationToken.uncancellable(), null);
            assertEquals(1, manager.bots().size());
            CrewManager.Bot bot = manager.bots().get(0);
            CountDownLatch finished = new CountDownLatch(1);
            Thread waiter = new Thread(() -> { try { bot.awaitTermination(); finished.countDown(); } catch (InterruptedException ignored) { } });
            waiter.setDaemon(true); waiter.start();
            try { assertTrue(finished.await(5, TimeUnit.SECONDS)); }
            finally { waiter.interrupt(); }
            assertEquals(2, captainCalls.get());
            assertEquals(1, workerCalls.get());
            assertEquals(workerMission, bot.mission);
            CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
            assertEquals(title, snapshot.title);
            assertEquals(request, snapshot.originalInstructions);
            assertEquals("Explorador", snapshot.bots.get(0).name);
        }
    }

    private static CrewManager manager(String session, AtomicInteger workerCalls) {
        return new CrewManager(session, EMPTY, (bot, crew) -> EMPTY,
                (bot, tools, incoming) -> new CoreAgentLoop((history, prompt, specs, token) -> {
                    workerCalls.incrementAndGet();
                    assertFalse(prompt.contains(bot.missionTitle().title));
                    return new ModelReply("Worker result", Collections.emptyList());
                }, tools, "Worker-only instructions", session, CorePromptBudget.standard(), null,
                        CoreAgentLoop.Limits.UNBOUNDED, incoming, null), null);
    }
}
