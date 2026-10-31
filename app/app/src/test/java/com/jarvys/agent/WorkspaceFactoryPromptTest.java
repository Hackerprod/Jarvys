package com.jarvys.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Declaration-only prompt checks: no manager, model, tool execution or provider traffic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WorkspaceFactoryPromptTest {
  private final CoreAgentRuntime runtime = new CoreAgentRuntime(
      Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

  @Test public void workspaceDeclarationKeepsSavedCrewAdmissionPrerequisite() {
    for (List<CoreTool> declared : Arrays.<List<CoreTool>>asList(
        Collections.singletonList(new Stub("workspaces")),
        Arrays.asList(new Stub("workspaces"), new Stub("crew_spawn")))) {
      String prompt = runtime.instructions(new CoreToolRegistry(declared), true);
      // The context-free runtime has Crew OFF. Tool declaration cannot bypass that setting.
      assertTrue(prompt.contains("Crew is OFF in the saved settings"));
      assertTrue(prompt.contains("Do not call workspaces action=delegate"));
      assertTrue(prompt.contains("never enable it automatically"));
      assertTrue(prompt.contains("Existing workspace reads, results and file delivery remain available"));
      assertFalse(prompt.contains("workspaces action=delegate with role=coding and its workspace_id"));
      assertFalse(prompt.contains("crew_spawn role=coding"));
      assertFalse(prompt.contains("If crew_spawn is unavailable"));
      assertFalse(prompt.contains("Coding/Crew must be enabled before building"));
      assertTrue(prompt.contains("Full factory instructions and apk_factory are Coding-only"));
      assertPolicyAndExplicitInstall(prompt);
      // This context-free fixture has no image backend. Workspaces must retain honest guidance.
      assertTrue(prompt.contains("does not expose the project_image backend to Coding"));
      assertFalse(prompt.contains("Coding can currently receive project_image"));
    }
  }

  @Test public void coordinationGuidanceRequiresDeclaredCentralToolAndKeepsLocalNoticeSemantics() {
    String central = runtime.instructions(new CoreToolRegistry(
        Collections.singletonList(new Stub("workspaces"))), true);
    assertTrue(central.contains("Opening a workspace does not authorize recovery or new work"));
    assertTrue(central.contains("Worker messages arrive as attributed internal conversation inputs"));
    assertTrue(central.contains("only to inspect retained evidence and receipts"));
    assertTrue(central.contains("results/read_result"));
    assertTrue(central.contains("inbox/read_message"));
    assertTrue(central.contains("reply only to an eligible live request"));
    assertTrue(central.contains("use workspaces action=message"));
    assertTrue(central.contains("do not read the inbox first"));
    assertFalse(central.contains("There is no automatic background delivery"));
    String ordinary = runtime.instructions(new CoreToolRegistry(Collections.emptyList()), true);
    assertFalse(ordinary.contains("Worker messages arrive as attributed internal conversation inputs"));
  }

  @Test public void enabledCrewRoutesFactoryWhileOffDoesNotPromiseAWorker() {
    for (com.jarvys.agent.crew.CrewMode mode : Arrays.asList(
        com.jarvys.agent.crew.CrewMode.AUTO, com.jarvys.agent.crew.CrewMode.ALWAYS)) {
      String prompt = CoreAgentRuntime.workspaceFactoryDiscovery(mode);
      assertTrue(prompt.contains("use workspaces to create or select a workspace"));
      assertTrue(prompt.contains("workspaces action=delegate with role=coding and its workspace_id"));
      assertFalse(prompt.contains("Crew is OFF"));
    }
    String disabled = CoreAgentRuntime.workspaceFactoryDiscovery(com.jarvys.agent.crew.CrewMode.OFF);
    assertTrue(disabled.contains("Crew is OFF"));
    assertTrue(disabled.contains("Do not call workspaces action=delegate"));
    assertFalse(disabled.contains("with role=coding"));
  }

  @Test public void legacyMainChatPreservesCrewDiscoveryAndSavedSigningPolicy() {
    for (List<CoreTool> declared : Arrays.<List<CoreTool>>asList(
        Collections.singletonList(new Stub("crew_spawn")), Collections.emptyList())) {
      String prompt = runtime.instructions(new CoreToolRegistry(declared), true);
      assertTrue(prompt.contains("crew_spawn role=coding"));
      assertTrue(prompt.contains("If crew_spawn is unavailable"));
      assertTrue(prompt.contains("Coding/Crew must be enabled before building"));
      assertFalse(prompt.contains("workspaces action=delegate with role=coding"));
      assertPolicyAndExplicitInstall(prompt);
    }
  }

  @Test public void factoryAndProjectImageDiscoveryRemainMainChatAndDeclarationScoped() {
    String isolated = runtime.instructions(
        new CoreToolRegistry(Collections.singletonList(new Stub("workspaces"))), false);
    assertFalse(isolated.contains("APK factory discovery:"));
    assertFalse(isolated.contains("does not expose the project_image backend to Coding"));
    assertFalse(isolated.contains("Coding can currently receive project_image"));
    String undeclared = runtime.instructions(new CoreToolRegistry(Collections.emptyList()), true);
    assertFalse(undeclared.contains("does not expose the project_image backend to Coding"));
    assertFalse(undeclared.contains("Coding can currently receive project_image"));
  }

  private static void assertPolicyAndExplicitInstall(String prompt) {
    assertTrue(prompt.contains("signing follows the saved SIGN Allow/Ask/Deny policy"));
    assertFalse(prompt.contains("signing needs its own approval"));
    assertTrue(prompt.contains("Installation requires a separate explicit user request and Android's confirmation"));
    assertTrue(prompt.contains("it is never automatic"));
  }

  private static final class Stub implements CoreTool {
    private final String name;
    Stub(String name) { this.name = name; }
    @Override public ToolSpec declaration() {
      return new ToolSpec(name, "fixture", "declaration only", "test", ToolSpec.Status.IMPLEMENTED,
          Collections.emptyMap(), Collections.emptyList());
    }
    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
      throw new AssertionError("This declaration-only fixture must not execute tools");
    }
  }
}
