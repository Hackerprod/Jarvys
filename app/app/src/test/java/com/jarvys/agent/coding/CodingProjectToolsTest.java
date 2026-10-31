package com.jarvys.agent.coding;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreTool;
import com.jarvys.agent.CoreToolResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class CodingProjectToolsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private CoreTool tool(List<CoreTool> tools, String name) {
    for (CoreTool tool : tools) if (tool.declaration().name.equals(name)) return tool;
    throw new AssertionError(name);
  }

  private Map<String, Object> args(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
    return map;
  }

  @Test
  public void exposesEightToolsAndRemovesMutationsFromReadOnlyScope() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("tools");
    ProjectMutationService service = new ProjectMutationService(1024 * 1024);
    List<CoreTool> all = CodingProjectTools.create(scope, service, "owner", 4096);
    assertEquals(8, all.size());
    List<CoreTool> read =
        CodingProjectTools.create(
            scope.restrict(EnumSet.of(ProjectScope.Capability.READ)), service, "owner", 4096);
    assertEquals(4, read.size());
    for (CoreTool candidate : read) {
      assertTrue(
          Arrays.asList("ls", "read", "coding_glob", "coding_grep")
              .contains(candidate.declaration().name));
    }
    Map<?, ?> properties =
        (Map<?, ?>) tool(all, "coding_patch").declaration().jsonSchema().get("properties");
    Map<?, ?> operations = (Map<?, ?>) properties.get("operations");
    assertEquals("object", ((Map<?, ?>) operations.get("items")).get("type"));
  }

  @Test
  public void smallResponseBudgetFailsBeforeApplyingMutation() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("tools");
    CoreTool write =
        tool(
            CodingProjectTools.create(scope, new ProjectMutationService(1024), "owner", 20),
            "write");
    CoreToolResult result =
        write.execute(
            args(
                "path",
                "/project/file.txt",
                "content",
                "new",
                "expected_sha256",
                "missing",
                "expected_scope_version",
                0),
            CancellationToken.uncancellable());
    assertFalse(result.success);
    assertFalse(scope.resolve("file.txt").exists());
    assertEquals(0, scope.version());
  }

  @Test
  public void pagingMutationReceiptNeverReplaysEffects() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("tools");
    AtomicInteger savedReceipts = new AtomicInteger();
    CoreTool write =
        tool(
            CodingProjectTools.create(
                scope,
                new ProjectMutationService(1024 * 1024),
                "owner",
                600,
                receipt -> {
                  savedReceipts.incrementAndGet();
                  return "Recovered receipt saved";
                }),
            "write");
    char[] chars = new char[3000];
    Arrays.fill(chars, 'x');
    CoreToolResult result =
        write.execute(
            args(
                "path",
                "/project/file.txt",
                "content",
                new String(chars),
                "expected_sha256",
                "missing",
                "expected_scope_version",
                0),
            CancellationToken.uncancellable());
    assertTrue(result.content, result.success);
    assertEquals(1, scope.version());
    assertEquals(1, savedReceipts.get());
    Pattern cursor = Pattern.compile("result_cursor=\"([^\"]+)\"");
    StringBuilder complete = new StringBuilder();
    for (int pages = 0; ; pages++) {
      assertTrue("Pagination should terminate", pages < 100);
      assertTrue(result.content.length() <= 600);
      complete.append(result.content);
      Matcher next = cursor.matcher(result.content);
      if (!next.find()) break;
      result =
          write.execute(
              Collections.singletonMap("result_cursor", next.group(1)),
              CancellationToken.uncancellable());
      assertTrue(result.content, result.success);
      assertEquals(1, scope.version());
    }
    assertEquals(1, savedReceipts.get());
    assertTrue(complete.toString().contains("APPLIED"));
    assertEquals(
        ProjectScope.sha256(new String(chars).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        scope.revision("file.txt"));
  }

  @Test
  public void malformedCursorAndEscapedPathFailWithoutEffects() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("tools");
    CoreTool write =
        tool(
            CodingProjectTools.create(scope, new ProjectMutationService(1024), "owner", 2000),
            "write");
    assertFalse(
        write.execute(args("result_cursor", "unknown:0"), CancellationToken.uncancellable())
            .success);
    assertFalse(
        write.execute(
                args(
                    "path",
                    "/project/../escape",
                    "content",
                    "new",
                    "expected_sha256",
                    "missing",
                    "expected_scope_version",
                    0),
                CancellationToken.uncancellable())
            .success);
    assertEquals(0, scope.version());
    assertEquals(0, scope.rootDirectory().list().length);
  }
}
