package com.jarvys.agent;

import com.jarvys.agent.chatdb.MessagePartInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** A durable conversation input, independent of who is speaking and how the provider encodes it. */
public final class ConversationInput {
  public static final String PROVENANCE_PART_KIND = "conversation_input_provenance";
  public enum Origin { USER, AGENT, SYSTEM }
  public enum Kind { RESULT, BLOCKER, QUESTION, MESSAGE, REPLY, ANSWER, STATUS, FINDING, TASK, CRITIQUE, OTHER }

  public static final class Provenance {
    /** Runtime-assigned trust principal; message text cannot select or elevate this role. */
    public enum PrincipalRole { SUPERVISOR, SUBORDINATE, PEER }

    public final String sessionId;
    public final String workspaceId;
    public final String assignmentId;
    public final String botId;
    public final String messageId;
    public final Kind kind;
    public final PrincipalRole principalRole;
    public final String correlationMessageId;
    public final String referenceId;
    public final String contentSha256;
    public final long assignmentRevision;
    public final String requestId;
    public final String missionId;
    public final String cycleId;

    public Provenance(String sessionId, String workspaceId, String assignmentId, String botId,
        String messageId, Kind kind) {
      this(sessionId, workspaceId, assignmentId, botId, messageId, kind, PrincipalRole.PEER);
    }

    public Provenance(String sessionId, String workspaceId, String assignmentId, String botId,
        String messageId, Kind kind, PrincipalRole principalRole) {
      this(sessionId, workspaceId, assignmentId, botId, messageId, kind, principalRole,
          "", "", "", 0L, "", "", "");
    }

    public Provenance(String sessionId, String workspaceId, String assignmentId, String botId,
        String messageId, Kind kind, PrincipalRole principalRole, String correlationMessageId,
        String referenceId, String contentSha256, long assignmentRevision, String requestId,
        String missionId, String cycleId) {
      this.sessionId = required(sessionId, "source session");
      this.workspaceId = optional(workspaceId);
      this.assignmentId = optional(assignmentId);
      this.botId = optional(botId);
      this.messageId = required(messageId, "source message");
      this.kind = kind == null ? Kind.MESSAGE : kind;
      // Old or malformed provenance must never acquire supervisor authority by default.
      this.principalRole = principalRole == null ? PrincipalRole.PEER : principalRole;
      this.correlationMessageId = optional(correlationMessageId);
      this.referenceId = optional(referenceId);
      this.contentSha256 = optional(contentSha256);
      this.assignmentRevision = assignmentRevision;
      this.requestId = optional(requestId);
      this.missionId = optional(missionId);
      this.cycleId = optional(cycleId);
      if (assignmentRevision < 0L || (!this.referenceId.isEmpty() &&
          (!this.referenceId.matches("workspace-coordination-[a-f0-9]{64}")
              || !this.contentSha256.matches("[a-f0-9]{64}") || assignmentRevision < 1L)))
        throw new IllegalArgumentException("Conversation input coordination reference is invalid");
    }

    public JSONObject toJson() {
      try {
        return new JSONObject().put("sessionId", sessionId).put("workspaceId", workspaceId)
            .put("assignmentId", assignmentId).put("botId", botId).put("messageId", messageId)
            .put("kind", kind.name()).put("principalRole", principalRole.name())
            .put("correlationMessageId", correlationMessageId).put("referenceId", referenceId)
            .put("contentSha256", contentSha256).put("assignmentRevision", assignmentRevision)
            .put("requestId", requestId).put("missionId", missionId).put("cycleId", cycleId);
      } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Provenance fromJson(JSONObject json) {
      if (json == null) throw new IllegalArgumentException("Conversation input provenance is required");
      return new Provenance(json.optString("sessionId", ""), json.optString("workspaceId", ""),
          json.optString("assignmentId", ""), json.optString("botId", ""),
          json.optString("messageId", ""), parseKind(json.optString("kind", "MESSAGE")),
          parsePrincipalRole(json.optString("principalRole", "PEER")),
          json.optString("correlationMessageId", ""), json.optString("referenceId", ""),
          json.optString("contentSha256", ""), json.optLong("assignmentRevision", 0L),
          json.optString("requestId", ""), json.optString("missionId", ""), json.optString("cycleId", ""));
    }

    private static Kind parseKind(String raw) {
      try { return Kind.valueOf(raw); }
      catch (RuntimeException invalid) { return Kind.OTHER; }
    }

    private static PrincipalRole parsePrincipalRole(String raw) {
      try { return PrincipalRole.valueOf(raw); }
      catch (RuntimeException invalid) { return PrincipalRole.PEER; }
    }
  }

  public final String conversationId;
  public final String content;
  public final String messageId;
  public final Origin origin;
  public final Provenance provenance;

  public ConversationInput(String conversationId, String messageId, String content, Origin origin,
      Provenance provenance) {
    this.conversationId = required(conversationId, "conversation");
    this.messageId = required(messageId, "message");
    this.content = content == null ? "" : content;
    this.origin = origin == null ? Origin.USER : origin;
    if (this.origin == Origin.USER && provenance != null)
      throw new IllegalArgumentException("User input must not carry agent provenance");
    if (this.origin != Origin.USER && provenance == null)
      throw new IllegalArgumentException("Internal input requires typed provenance");
    if (this.origin != Origin.USER && this.content.isBlank())
      throw new IllegalArgumentException("Internal conversation input content is required");
    this.provenance = provenance;
  }

  /** Stable across retries and unique to the source session and source message. */
  public String stableId() {
    String source = provenance == null ? conversationId : provenance.sessionId;
    return "conversation-input-" + sha256(origin.name() + "\u0000" + source + "\u0000" + messageId);
  }

  public MessagePartInput provenancePart() {
    if (provenance == null) return null;
    try {
      return new MessagePartInput(PROVENANCE_PART_KIND,
          new JSONObject().put("origin", origin.name()).put("provenance", provenance.toJson()).toString());
    } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
  }

  public static Origin parseOrigin(String value) {
    try { return Origin.valueOf(value); }
    catch (RuntimeException invalid) { return null; }
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Conversation input " + field + " is required");
    return value;
  }

  private static String optional(String value) { return value == null ? "" : value; }

  private static String sha256(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder encoded = new StringBuilder(bytes.length * 2);
      for (byte item : bytes) encoded.append(String.format(Locale.ROOT, "%02x", item & 0xff));
      return encoded.toString();
    } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
  }
}
