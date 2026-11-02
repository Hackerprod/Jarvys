package com.jarvys.agent;

import com.jarvys.agent.crew.CrewInputIncorporation;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact local execution evidence. Never sanitizes, truncates, repairs, or grants replay permission. */
final class CentralYieldCheckpointCodec {
    private CentralYieldCheckpointCodec() { }

    static JSONObject encode(CoreAgentLoop.Checkpoint checkpoint) {
        try {
            MainChatTranscriptStore.requireSafeYieldCheckpoint(checkpoint);
            long remaining=CrewCheckpointStore.materializationBudget();
            remaining-=CrewCheckpointStore.estimatedSize(checkpoint.appliedIncomingIds,remaining);
            remaining-=CrewCheckpointStore.estimatedSize(checkpoint.toolLifecycle,remaining);
            remaining-=CrewCheckpointStore.estimatedSize(checkpoint.completedToolResults,remaining);
            for(CrewInputIncorporation proof:checkpoint.incorporations.values())
                remaining-=CrewCheckpointStore.estimatedSize(proof.toJson(),remaining);
            for(ConversationTurn turn:checkpoint.transcript) {
                for(Object value:Arrays.asList(turn.content,turn.modelContent,turn.thinking,turn.durableMessageId,
                        turn.systemEventReferenceId,turn.modelErrorCode,turn.toolCallId,turn.toolName))
                    remaining-=CrewCheckpointStore.estimatedSize(value,remaining);
                for(ConversationTurn.Image image:turn.images)
                    remaining-=CrewCheckpointStore.estimatedSize(image.jpegBase64,remaining);
                for(ModelReply.Call call:turn.toolCalls) {
                    remaining-=CrewCheckpointStore.estimatedSize(call.id,remaining);
                    remaining-=CrewCheckpointStore.estimatedSize(call.name,remaining);
                    remaining-=CrewCheckpointStore.estimatedSize(call.invalidArgumentsReason,remaining);
                    remaining-=CrewCheckpointStore.estimatedSize(call.arguments,remaining);
                }
                ConversationTurn.Source source=turn.source();
                if(source!=null)for(String part:Arrays.asList(source.conversationId,source.sourceId,source.callId))
                    remaining-=CrewCheckpointStore.estimatedSize(part,remaining);
                for(ModelReply.ProviderOutputItem item:turn.providerOutputItems)
                    remaining-=CrewCheckpointStore.estimatedSize(item.value,remaining);
                for(ChatAttachment attachment:turn.attachments)
                    remaining-=CrewCheckpointStore.estimatedSize(attachment.toJson(),remaining);
                if(turn.incomingMetadata!=null)
                    remaining-=CrewCheckpointStore.estimatedSize(encodeIncoming(turn.incomingMetadata),remaining);
            }
            JSONArray turns=new JSONArray();
            for(ConversationTurn turn:checkpoint.transcript)turns.put(encodeTurn(turn));
            JSONArray incorporations=new JSONArray();
            for(CrewInputIncorporation proof:checkpoint.incorporations.values())incorporations.put(proof.toJson());
            JSONObject value=new JSONObject().put("version",1).put("transcript",turns)
                .put("appliedIncomingIds",new JSONArray(checkpoint.appliedIncomingIds))
                .put("toolLifecycle",new JSONObject(checkpoint.toolLifecycle))
                .put("genuineUserIndex",checkpoint.genuineUserIndex).put("incorporations",incorporations)
                .put("completedToolResults",new JSONObject(checkpoint.completedToolResults));
            CrewCheckpointStore.estimatedSize(value,CrewCheckpointStore.materializationBudget());
            return value;
        } catch(Exception invalid) { throw invalid(invalid); }
    }
    static String encodeCanonical(CoreAgentLoop.Checkpoint checkpoint) { return canonical(encode(checkpoint)); }

    static CoreAgentLoop.Checkpoint decode(JSONObject value) {
        try {
            CrewCheckpointStore.estimatedSize(value,CrewCheckpointStore.materializationBudget());
            if(value.getInt("version")!=1)throw new IllegalArgumentException("Unknown snapshot version");
            List<ConversationTurn> turns=new ArrayList<>(); JSONArray rows=value.getJSONArray("transcript");
            for(int i=0;i<rows.length();i++)turns.add(decodeTurn(rows.getJSONObject(i)));
            Set<String> applied=new LinkedHashSet<>(); JSONArray ids=value.getJSONArray("appliedIncomingIds");
            for(int i=0;i<ids.length();i++) {
                String id=str(ids.get(i));if(id.isEmpty()||!applied.add(id))throw new IllegalArgumentException("Duplicate applied identity");
            }
            Map<String,String> states=stringMap(value.getJSONObject("toolLifecycle"));
            Map<String,String> results=stringMap(value.getJSONObject("completedToolResults"));
            for(String id:results.keySet())if(!"RESULT".equals(states.get(id)))throw new IllegalArgumentException("Unconfirmed complete result");
            Map<String,CrewInputIncorporation> incorporations=new LinkedHashMap<>();
            JSONArray proofs=value.getJSONArray("incorporations");
            for(int i=0;i<proofs.length();i++) {
                CrewInputIncorporation proof=CrewInputIncorporation.fromJsonStrict(proofs.getJSONObject(i));
                if(incorporations.put(proof.binding.mailboxMessageId,proof)!=null)throw new IllegalArgumentException("Duplicate incorporation");
            }
            CoreAgentLoop.Checkpoint checkpoint=new CoreAgentLoop.Checkpoint(turns,applied,states,
                value.getInt("genuineUserIndex"),incorporations,results);
            MainChatTranscriptStore.requireSafeYieldCheckpoint(checkpoint);
            if(!canonical(value).equals(encodeCanonical(checkpoint)))throw new IllegalArgumentException("Snapshot was not lossless");
            return checkpoint;
        } catch(Exception invalid) { throw invalid(invalid); }
    }

    private static JSONObject encodeTurn(ConversationTurn turn) throws Exception {
        JSONArray calls=new JSONArray();
        for(ModelReply.Call call:turn.toolCalls)calls.put(new JSONObject().put("id",call.id).put("name",call.name)
            .put("arguments",new JSONObject(call.arguments)).put("invalidArgumentsReason",call.invalidArgumentsReason==null?JSONObject.NULL:call.invalidArgumentsReason));
        JSONArray images=new JSONArray();
        for(ConversationTurn.Image image:turn.images)images.put(new JSONObject().put("width",image.width)
            .put("height",image.height).put("jpegBase64",image.jpegBase64));
        JSONObject row=new JSONObject().put("kind",turn.kind.name()).put("role",turn.role).put("content",turn.content)
            .put("thinking",turn.thinking).put("modelContent",turn.modelContent).put("imageCount",turn.imageCount)
            .put("images",images).put("attachments",ChatAttachment.toJsonArray(turn.attachments))
            .put("originalMessageIndex",turn.originalMessageIndex).put("summarizedMessageCount",turn.summarizedMessageCount)
            .put("durableMessageId",turn.durableMessageId).put("systemEventReferenceId",turn.systemEventReferenceId)
            .put("modelErrorCode",turn.modelErrorCode).put("toolCallId",turn.toolCallId).put("toolName",turn.toolName)
            .put("calls",calls).put("providerOutputItems",ProviderOutputItemsCodec.encode(turn.providerOutputItems))
            .put("incomingMetadata",turn.incomingMetadata==null?JSONObject.NULL:encodeIncoming(turn.incomingMetadata));
        ConversationTurn.Source source=turn.source();
        row.put("source",source==null?JSONObject.NULL:new JSONObject().put("kind",source.kind.name())
            .put("conversationId",source.conversationId).put("sourceId",source.sourceId).put("callId",source.callId));
        return row;
    }
    private static JSONObject encodeIncoming(ConversationTurn.IncomingMetadata incoming) throws Exception {
        return new JSONObject().put("origin",incoming.origin.name()).put("id",incoming.id).put("source",incoming.source)
            .put("sender",incoming.sender).put("type",incoming.type).put("references",new JSONArray(incoming.references))
            .put("untrusted",incoming.untrusted).put("provenance",incoming.provenance==null?JSONObject.NULL:incoming.provenance.toJson());
    }
    private static ConversationTurn decodeTurn(JSONObject row) throws Exception {
        ConversationTurn.Kind kind=ConversationTurn.Kind.valueOf(str(row.get("kind")));
        String role=str(row.get("role")),content=str(row.get("content"));
        List<ModelReply.ProviderOutputItem> items=ProviderOutputItemsCodec.decode(row.getJSONArray("providerOutputItems"));
        ConversationTurn.IncomingMetadata incoming=null;
        if(!row.isNull("incomingMetadata")) {
            JSONObject in=row.getJSONObject("incomingMetadata"); List<String> references=new ArrayList<>();
            JSONArray refs=in.getJSONArray("references");for(int i=0;i<refs.length();i++)references.add(str(refs.get(i)));
            ConversationInput.Provenance provenance=in.isNull("provenance")?null:ConversationInput.Provenance.fromJson(in.getJSONObject("provenance"));
            if(!in.isNull("provenance")&&provenance==null)throw new IllegalArgumentException("Invalid provenance");
            Object untrusted=in.get("untrusted");if(!(untrusted instanceof Boolean))throw new IllegalArgumentException("Invalid trust field");
            incoming=new ConversationTurn.IncomingMetadata(ConversationInput.Origin.valueOf(str(in.get("origin"))),
                str(in.get("id")),str(in.get("source")),str(in.get("sender")),str(in.get("type")),references,(Boolean)untrusted,provenance);
        }
        ConversationTurn base;
        switch(kind) {
            case MESSAGE:
                if(!items.isEmpty()) {
                    if(!"assistant".equals(role)||incoming!=null)throw new IllegalArgumentException("Provider output owner changed");
                    base=ConversationTurn.assistantReply(content,row.getInt("originalMessageIndex"),items);
                } else base=new ConversationTurn(role,content,row.getInt("originalMessageIndex"),incoming);
                break;
            case TOOL_CALLS:
                List<ModelReply.Call> calls=new ArrayList<>();JSONArray encoded=row.getJSONArray("calls");
                for(int i=0;i<encoded.length();i++) {
                    JSONObject call=encoded.getJSONObject(i);calls.add(new ModelReply.Call(str(call.get("id")),str(call.get("name")),map(call.getJSONObject("arguments")),
                        call.isNull("invalidArgumentsReason")?null:str(call.get("invalidArgumentsReason"))));
                }
                base=ConversationTurn.toolCalls(content,calls,items);break;
            case TOOL_RESULT: base=ConversationTurn.toolResult(str(row.get("toolCallId")),str(row.get("toolName")),content);break;
            case COMPACTION_SUMMARY: base=ConversationTurn.restoredCompactionSummary(content,row.getInt("summarizedMessageCount"));break;
            case MODEL_ERROR: base=ConversationTurn.modelError(str(row.get("modelErrorCode")));break;
            case SYSTEM_EVENT: base=ConversationTurn.systemEvent(str(row.get("systemEventReferenceId")),content);break;
            default: throw new IllegalArgumentException("Unknown snapshot turn");
        }
        List<ChatAttachment> attachments=new ArrayList<>();JSONArray encodedAttachments=row.getJSONArray("attachments");
        for(int i=0;i<encodedAttachments.length();i++) {
            JSONObject encoded=encodedAttachments.getJSONObject(i); ChatAttachment attachment=ChatAttachment.fromJson(encoded);
            if(attachment==null||!canonical(encoded).equals(canonical(attachment.toJson())))throw new IllegalArgumentException("Invalid attachment");
            attachments.add(attachment);
        }
        List<ConversationTurn.Image> images=new ArrayList<>();JSONArray encodedImages=row.getJSONArray("images");
        for(int i=0;i<encodedImages.length();i++) {
            JSONObject image=encodedImages.getJSONObject(i);images.add(new ConversationTurn.Image(str(image.get("jpegBase64")),image.getInt("width"),image.getInt("height")));
        }
        ConversationTurn result=ConversationTurn.restoreExactSnapshot(base,str(row.get("thinking")),row.getInt("imageCount"),
            attachments,str(row.get("modelContent")),images,str(row.get("durableMessageId")));
        if(!row.isNull("source")) {
            JSONObject source=row.getJSONObject("source");ConversationTurn.Source bound=new ConversationTurn.Source(
                ConversationTurn.Source.Kind.valueOf(str(source.get("kind"))),str(source.get("conversationId")),str(source.get("sourceId")),str(source.get("callId")));
            if(kind==ConversationTurn.Kind.MESSAGE&&!result.durableMessageId.equals(bound.sourceId))throw new IllegalArgumentException("Message source mismatch");
            result.bindSource(bound);
        }
        return result;
    }
    private static String str(Object value) { if(!(value instanceof String))throw new IllegalArgumentException("Expected exact string");return (String)value; }
    private static Map<String,String> stringMap(JSONObject value) throws Exception {
        Map<String,String> result=new LinkedHashMap<>();Iterator<String> keys=value.keys();
        while(keys.hasNext()){String key=keys.next();if(key.isEmpty())throw new IllegalArgumentException("Empty identity");result.put(key,str(value.get(key)));}return result;
    }
    private static Map<String,Object> map(JSONObject value) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();Iterator<String> keys=value.keys();while(keys.hasNext()){String key=keys.next();result.put(key,plain(value.get(key)));}return result;
    }
    private static Object plain(Object value) throws Exception {
        if(value==JSONObject.NULL)return null;if(value instanceof JSONObject)return map((JSONObject)value);
        if(value instanceof JSONArray){List<Object> result=new ArrayList<>();JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)result.add(plain(array.get(i)));return result;}return value;
    }
    static String canonical(JSONObject value) { return CrewCheckpointStore.escapeJsonSurrogates(canonicalValue(value)); }
    private static String canonicalValue(Object value) {
        try {
            if(value==null||value==JSONObject.NULL)return "null";
            if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());Collections.sort(keys);
                StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonicalValue(object.get(key)));}return out.append('}').toString();}
            if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonicalValue(array.get(i)));}return out.append(']').toString();}
            if(value instanceof String)return JSONObject.quote((String)value);
            if(value instanceof Boolean)return value.toString();
            if(value instanceof Number)return JSONObject.numberToString((Number)value);
            throw new IllegalArgumentException("Unsupported exact JSON value");
        } catch(Exception invalid){throw invalid(invalid);}
    }
    private static IllegalArgumentException invalid(Exception cause){return new IllegalArgumentException("Invalid exact Central yield checkpoint",cause);}
}
