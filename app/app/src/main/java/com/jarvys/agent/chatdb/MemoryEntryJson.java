package com.jarvys.agent.chatdb;

import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Pure shared canonical entry rendering; authorization and payload hydration happen before this. */
public final class MemoryEntryJson {
    private MemoryEntryJson() {}
    public static JSONObject entry(PersonalMemoryHit hit) {
      JSONObject item = new JSONObject();
      put(item, "id", hit.getId()); put(item, "kind", hit.getKind()); put(item, "type", hit.getObservationType()); put(item, "layer", hit.getLayer());
      put(item, "project", hit.getProject()); put(item, "scope", hit.getScope());
      put(item, "pinned", hit.getPinned());
      put(item,"superseded_by_id",hit.getSupersededById()==null?JSONObject.NULL:hit.getSupersededById());
      put(item,"review_after",hit.getReviewAfter()==null?JSONObject.NULL:hit.getReviewAfter()); put(item,"state",hit.getReviewState());
      put(item, "duplicate_count", hit.getDuplicateCount());
      put(item, "last_seen_at", hit.getLastSeenAt() == null ? JSONObject.NULL : hit.getLastSeenAt());
      put(item, "title", hit.getTitle() == null ? JSONObject.NULL : hit.getTitle()); put(item, "label", hit.getLabel()); putTopic(item, hit);
      put(item, "date", hit.getEventAt() == null ? iso(hit.getUpdatedAt()) : iso(hit.getEventAt()));
      put(item, "narration", hit.getNarration()); put(item, "evidence_count", hit.getEvidenceCount());
      if (!hit.getLinks().isEmpty()) {
        JSONArray links = new JSONArray();
        for (PersonalMemoryLinkView link : hit.getLinks()) {
          JSONObject entry = new JSONObject();
          // "out" reads "this entry <relation> that one"; "in" is the reverse (superseded_by, ...).
          put(entry, "relation", "in".equals(link.getDirection()) ? invert(link.getRelation()) : link.getRelation());
          put(entry, "id", link.getId()); put(entry, "title", link.getTitle());
          put(entry,"title_bytes_base64",link.getTitleBytesBase64()==null ? JSONObject.NULL : link.getTitleBytesBase64());
          put(entry,"title_is_utf8",link.getTitleIsUtf8()==null ? JSONObject.NULL : link.getTitleIsUtf8());
          links.put(entry);
        }
        put(item, "links", links);
      }
      if (!hit.getRelations().isEmpty()) {
        JSONArray relations=new JSONArray();
        for(com.jarvys.agent.chatdb.MemoryRelationView relation:hit.getRelations()) {
          relations.put(relationJson(relation));
        }
        put(item,"relations",relations);
      }
      return item;
    }

    /** The display may contain replacement characters; only the base64 metadata preserves raw identity. */
    private static void putTopic(JSONObject item, PersonalMemoryHit hit) {
      put(item,"title_bytes_base64",hit.getTitleBytesBase64()==null ? JSONObject.NULL : hit.getTitleBytesBase64());
      put(item,"title_is_utf8",hit.getTitleIsUtf8()==null ? JSONObject.NULL : hit.getTitleIsUtf8());
      put(item, "topic_key", hit.getTopicKey() == null ? JSONObject.NULL : hit.getTopicKey());
      put(item, "topic_key_bytes_base64", hit.getTopicKeyBytesBase64() == null ? JSONObject.NULL : hit.getTopicKeyBytesBase64());
      put(item, "topic_key_is_utf8", hit.getTopicKeyIsUtf8() == null ? JSONObject.NULL : hit.getTopicKeyIsUtf8());
    }

    private static String invert(String relation) {
      return "supersedes".equals(relation) ? "superseded_by" : relation + "_of";
    }

    public static JSONObject relationJson(com.jarvys.agent.chatdb.MemoryRelationView relation) {
      JSONObject entry=new JSONObject();
      put(entry,"judgment_id",relation.getJudgmentId());put(entry,"relation",relation.getRelation());put(entry,"status",relation.getStatus());
      put(entry,"direction",relation.getDirection());put(entry,"source_id",relation.getSourceId());put(entry,"target_id",relation.getTargetId());
      put(entry,"source_title",relation.getSourceTitle());put(entry,"target_title",relation.getTargetTitle());
      put(entry,"source_missing",relation.getSourceMissing());put(entry,"target_missing",relation.getTargetMissing());
      put(entry,"source_title_bytes_base64",relation.getSourceTitleBytesBase64());put(entry,"source_title_is_utf8",relation.getSourceTitleIsUtf8());
      put(entry,"target_title_bytes_base64",relation.getTargetTitleBytesBase64());put(entry,"target_title_is_utf8",relation.getTargetTitleIsUtf8());
      put(entry,"reason",relation.getReason());put(entry,"evidence",relation.getEvidence());put(entry,"confidence",relation.getConfidence());
      put(entry,"marked_by_actor",relation.getMarkedByActor());put(entry,"marked_by_kind",relation.getMarkedByKind());put(entry,"marked_by_model",relation.getMarkedByModel());
      if(relation.getMarkedByModel()!=null) put(entry,"model_provenance","caller_reported");
      put(entry,"memory_session_id",relation.getMemorySessionId());put(entry,"created_at",relation.getCreatedAt());put(entry,"updated_at",relation.getUpdatedAt());
      return entry;
    }

    private static String iso(long epochMillis) { return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime().toString(); }
    private static void put(JSONObject target,String key,Object value) {
        try { target.put(key,value==null?JSONObject.NULL:value); }
        catch(JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
