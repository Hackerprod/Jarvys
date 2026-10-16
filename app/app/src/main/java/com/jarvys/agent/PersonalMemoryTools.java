package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.PersonalMemoryHit;
import com.jarvys.agent.chatdb.PersonalMemoryReceipt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Generic SQLite-backed memory capabilities with durable receipt evidence. */
final class PersonalMemoryTools {
  private PersonalMemoryTools() {}

  static List<CoreTool> create(Context context, String sourceMessageId) {
    return create(context, sourceMessageId, null, true);
  }

  static List<CoreTool> create(Context context, String sourceMessageId, String conversationId) {
    return create(context, sourceMessageId, conversationId, true);
  }

  static List<CoreTool> createForExtraction(Context context, String conversationId) {
    return create(context, "", conversationId, false);
  }

  static List<CoreTool> createForConsolidation(Context context, List<String> sourceIds) {
    if (context == null) return Collections.emptyList();
    return Collections.singletonList(new MemoryTool(
        ChatDatabaseBridge.getInstance(context), "", null, "memory_consolidate", sourceIds));
  }

  private static List<CoreTool> create(Context context, String sourceMessageId,
                                       String conversationId, boolean allowDestructive) {
    if (context == null) return Collections.emptyList();
    ChatDatabaseBridge bridge = ChatDatabaseBridge.getInstance(context);
    List<CoreTool> result = new ArrayList<>();
    result.add(new MemoryTool(bridge, sourceMessageId, conversationId, "user_profile_set"));
    result.add(new MemoryTool(bridge, sourceMessageId, conversationId, "memory_save"));
    result.add(new MemoryTool(bridge, sourceMessageId, conversationId, "memory_search"));
    result.add(new MemoryTool(bridge, sourceMessageId, conversationId, "memory_get"));
    if (allowDestructive) {
      result.add(new MemoryTool(bridge, sourceMessageId, conversationId, "memory_forget"));
      result.add(new MemoryTool(bridge, sourceMessageId, conversationId, "memory_correct"));
    }
    return result;
  }

  private static final class MemoryTool implements CoreTool {
    private final ChatDatabaseBridge bridge;
    private final String sourceMessageId;
    private final String conversationId;
    private final String name;
    private final List<String> expectedSourceIds;
    private final ToolSpec declaration;

    MemoryTool(ChatDatabaseBridge bridge, String sourceMessageId, String conversationId, String name) {
      this(bridge, sourceMessageId, conversationId, name, Collections.emptyList());
    }

    MemoryTool(ChatDatabaseBridge bridge, String sourceMessageId, String conversationId, String name,
               List<String> expectedSourceIds) {
      this.bridge = bridge;
      this.sourceMessageId = sourceMessageId == null ? "" : sourceMessageId;
      this.conversationId = conversationId;
      this.name = name;
      this.expectedSourceIds = expectedSourceIds == null ? Collections.emptyList() : new ArrayList<>(expectedSourceIds);
      Map<String, String> properties = new LinkedHashMap<>();
      List<String> required;
      String description;
      switch (name) {
        case "user_profile_set":
          properties.put("directive", "string"); properties.put("section", "string"); properties.put("supersedes_id", "string");
          required = Arrays.asList("directive", "section");
          description = "Save an explicit user fact or preference as an active global profile directive. If changing an existing fact, search/read first and pass its id as supersedes_id. The result confirms the committed id and revision.";
          break;
        case "memory_save":
          properties.put("text", "string"); properties.put("layer", "string"); properties.put("category", "string"); properties.put("importance", "number");
          required = Arrays.asList("text", "layer");
          description = "Save a durable user memory note in the global user scope. Use curated for stable facts, daily for dated notes, or session_snapshot for reset summaries. The result confirms the committed id and revision.";
          break;
        case "memory_search":
          properties.put("query", "string"); properties.put("limit", "integer"); required = Collections.singletonList("query");
          description = "Search saved global user memory with full-text BM25 ranking. Use when an answer depends on prior preferences, people, or decisions.";
          break;
        case "memory_get":
          properties.put("ids", "string_or_array"); required = Collections.singletonList("ids");
          description = "Read memory entries by id after a search or before changing a saved entry.";
          break;
        case "memory_consolidate":
          properties.put("source_ids", "string_or_array"); properties.put("summary", "string");
          required = Arrays.asList("source_ids", "summary");
          description = "Update an audited curated summary using exactly the supplied persisted daily-note ids. Source notes remain unchanged.";
          break;
        case "memory_forget":
          properties.put("id", "string"); required = Collections.singletonList("id");
          description = "Forget a saved memory entry by id. The result confirms the durable tombstone revision.";
          break;
        default:
          properties.put("id", "string"); properties.put("new_text", "string"); required = Arrays.asList("id", "new_text");
          description = "Correct a saved memory entry by id. The prior entry is retained in revision history and superseded by a new entry.";
      }
      declaration = new ToolSpec(name, "agent_memory", description, "memory", ToolSpec.Status.IMPLEMENTED, properties, required);
    }

    @Override public ToolSpec declaration() { return declaration; }

    @Override public AccessClassification accessClassification() {
      return ("memory_search".equals(name) || "memory_get".equals(name)) ? AccessClassification.READ : AccessClassification.WRITE;
    }

    @Override public PersistenceEffect persistenceEffect() { return PersistenceEffect.MEMORY; }

    @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token) {
      token.throwIfCancelled();
      try {
        switch (name) {
          case "user_profile_set": {
            String section = stringArg(args, "section");
            PersonalMemoryReceipt receipt = bridge.personalAgentSetDirective(section, stringArg(args, "directive"), sourceMessageId,
                "User explicitly stated this in the current conversation.", optionalString(args, "supersedes_id"), conversationId);
            return writeResult(receipt);
          }
          case "memory_save": {
            String layer = stringArg(args, "layer");
            double importance = args.get("importance") instanceof Number ? ((Number) args.get("importance")).doubleValue() : 0.5d;
            PersonalMemoryReceipt receipt = bridge.personalAgentSaveMemory(stringArg(args, "text"), layer,
                optionalString(args, "category"), importance, sourceMessageId);
            return writeResult(receipt);
          }
          case "memory_search": {
            int limit = args.get("limit") instanceof Number ? ((Number) args.get("limit")).intValue() : 8;
            return CoreToolResult.success(renderHits(bridge.personalAgentSearch(stringArg(args, "query"), limit)));
          }
          case "memory_get":
            return CoreToolResult.success(renderHits(bridge.personalAgentGet(readIds(args.get("ids")))));
          case "memory_consolidate": {
            List<String> sourceIds = readIds(args.get("source_ids"));
            if (sourceIds.isEmpty() || !new java.util.HashSet<>(sourceIds).equals(new java.util.HashSet<>(expectedSourceIds)))
              throw new IllegalArgumentException("consolidation_sources_must_match_persisted_input");
            return writeResult(bridge.personalAgentConsolidateDailyNotes(sourceIds, stringArg(args, "summary")));
          }
          case "memory_forget":
            return writeResult(bridge.personalAgentForget(stringArg(args, "id")));
          default:
            return writeResult(bridge.personalAgentCorrect(stringArg(args, "id"), stringArg(args, "new_text")));
        }
      } catch (RuntimeException failure) {
        String message = failure.getMessage() == null ? "memory_write_failed" : failure.getMessage();
        return CoreToolResult.failure("Memory operation failed: " + message);
      }
    }

    private static CoreToolResult writeResult(PersonalMemoryReceipt receipt) {
      String evidence = "id=" + receipt.getId() + "; status=" + receipt.getStatus() + "; revision=" + receipt.getRevision() + "; scope=" + receipt.getScope();
      return CoreToolResult.persisted("Persisted memory receipt: " + evidence, evidence);
    }

    private static String renderHits(List<PersonalMemoryHit> hits) {
      JSONArray array = new JSONArray();
      for (PersonalMemoryHit hit : hits) {
        JSONObject item = new JSONObject();
        try {
          item.put("id", hit.getId()); item.put("text", hit.getText()); item.put("scope", hit.getScope()); item.put("layer", hit.getLayer()); item.put("score", hit.getScore());
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        array.put(item);
      }
      return array.toString();
    }

    private static List<String> readIds(Object value) {
      if (value instanceof String) return Collections.singletonList((String) value);
      if (value instanceof List) {
        List<String> ids = new ArrayList<>();
        for (Object item : (List<?>) value) if (item instanceof String) ids.add((String) item);
        return ids;
      }
      return Collections.emptyList();
    }

    private static String stringArg(Map<String, Object> args, String key) {
      Object value = args.get(key);
      if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
      return ((String) value).trim();
    }

    private static String optionalString(Map<String, Object> args, String key) {
      Object value = args.get(key);
      return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
    }
  }
}
