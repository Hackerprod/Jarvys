package com.jarvys.agent;

import com.jarvys.agent.skills.SkillEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DelegateSubtaskTool implements CoreTool {
  private final List<String> availableSkills;
  private final List<String> availableTools;
  private final CoreToolRegistry capabilityScope;
  private final ToolSpec declaration;
  private final Runner runner;
  private final ScopedRunner scopedRunner;
  private final List<SkillEntry> skillEntries;

  public interface Runner {
    String run(
        String str, List<String> list, List<String> list2, CancellationToken cancellationToken);
  }

  public interface ScopedRunner {
    String run(
        String str,
        CoreToolRegistry coreToolRegistry,
        List<String> list,
        List<String> list2,
        CancellationToken cancellationToken);
  }

  public DelegateSubtaskTool(
      Runner runner, Collection<String> availableTools, Collection<SkillEntry> availableSkills) {
    this(runner, null, null, availableTools, availableSkills);
  }

  public DelegateSubtaskTool(
      ScopedRunner runner,
      CoreToolRegistry scope,
      Collection<SkillEntry> skills,
      boolean recursive) {
    this(null, runner, scope, names(scope, recursive), skills);
  }

  private DelegateSubtaskTool(
      Runner runner,
      ScopedRunner scopedRunner,
      CoreToolRegistry scope,
      Collection<String> availableTools,
      Collection<SkillEntry> availableSkills) {
    this.runner = runner;
    this.scopedRunner = scopedRunner;
    this.capabilityScope = scope;
    this.skillEntries = Collections.unmodifiableList(new ArrayList(availableSkills));
    this.availableTools = Collections.unmodifiableList(new ArrayList(availableTools));
    List<String> ids = new ArrayList<>();
    for (SkillEntry skill : availableSkills) {
      ids.add(skill.getMetadata().getId());
    }
    this.availableSkills = Collections.unmodifiableList(ids);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("objective", "string");
    properties.put("tools", "array");
    properties.put("skills", "array");
    this.declaration =
        new ToolSpec(
            "delegate_subtask",
            "jarvys/core",
            "Run a separate ReAct agent on a bounded subtask. It receives no parent transcript;"
                + " choose only listed tools and skill ids. Available tools: "
                + this.availableTools
                + ". Available skill ids: "
                + this.availableSkills,
            "core",
            ToolSpec.Status.IMPLEMENTED,
            properties,
            Collections.singletonList("objective"));
  }

  @Override // com.jarvys.agent.CoreTool
  public ToolSpec declaration() {
    return this.declaration;
  }

  @Override // com.jarvys.agent.CoreTool
  public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
    Object rawObjective = arguments.get("objective");
    if (!(rawObjective instanceof String) || ((String) rawObjective).trim().isEmpty()) {
      return CoreToolResult.failure("objective must be a non-empty string");
    }
    String objective = ((String) rawObjective).trim();
    if (objective.length() > 4000) {
      return CoreToolResult.failure("objective exceeds 4000 characters");
    }
    List<String> requestedTools = strings(arguments.get("tools"), "tools");
    List<String> requestedSkills = strings(arguments.get("skills"), "skills");
    if (requestedTools.size() > 8 || requestedSkills.size() > 8) {
      return CoreToolResult.failure("A subtask may receive at most 8 tools and 8 skills");
    }
    if (!this.availableTools.containsAll(requestedTools)) {
      return CoreToolResult.failure(
          "Subtask requested a tool outside this agent's capability scope");
    }
    if (!this.availableSkills.containsAll(requestedSkills)) {
      return CoreToolResult.failure(
          "Subtask requested a skill outside this agent's enabled skill scope");
    }
    token.throwIfCancelled();
    String result =
        this.scopedRunner == null
            ? this.runner.run(objective, requestedTools, requestedSkills, token)
            : this.scopedRunner.run(
                objective, this.capabilityScope, requestedTools, requestedSkills, token);
    if (result == null || result.trim().isEmpty()) {
      return CoreToolResult.failure("Subagent returned no final text");
    }
    return CoreToolResult.success(
        result.length() <= 12000 ? result : result.substring(0, 12000) + "…[truncated]");
  }

  private static List<String> names(CoreToolRegistry scope, boolean recursive) {
    List<String> result = new ArrayList<>();
    for (CoreTool tool : scope.handlers()) {
      if (tool.canDelegate()) {
        result.add(tool.declaration().name);
      }
    }
    result.remove("delegate_subtask");
    if (recursive) {
      result.add("delegate_subtask");
    }
    return result;
  }

  DelegateSubtaskTool forScope(CoreToolRegistry narrowed) {
    List<String> names = new ArrayList<>(this.availableTools);
    boolean recursive = false;
    names.retainAll(names(narrowed, false));
    names.remove("delegate_subtask");
    CoreToolRegistry handlers = narrowed.rawSubset(names);
    List<SkillEntry> selectedSkills = new ArrayList<>();
    CoreTool skillTool = handlers.get("read_skill");
    if (skillTool instanceof LoadSkillTool) {
      for (SkillEntry skill : ((LoadSkillTool) skillTool).availableSkills()) {
        if (this.availableSkills.contains(skill.getMetadata().getId())) {
          selectedSkills.add(skill);
        }
      }
    }
    if (narrowed.names().contains("delegate_subtask")
        && this.availableTools.contains("delegate_subtask")) {
      recursive = true;
    }
    return this.scopedRunner != null
        ? new DelegateSubtaskTool(this.scopedRunner, handlers, selectedSkills, recursive)
        : new DelegateSubtaskTool(this.runner, names(handlers, recursive), selectedSkills);
  }

  private static List<String> strings(Object value, String field) {
    if (value == null) {
      return Collections.emptyList();
    }
    if (!(value instanceof List)) {
      throw new IllegalArgumentException(field + " must be a list of strings");
    }
    List<String> result = new ArrayList<>();
    for (Object item : (List) value) {
      if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
        throw new IllegalArgumentException(field + " must contain only non-empty strings");
      }
      if (result.contains(item)) {
        throw new IllegalArgumentException(field + " must not contain duplicates");
      }
      result.add((String) item);
    }
    return result;
  }
}
