package com.jarvys.agent;

import static org.junit.Assert.*;
import java.util.*;
import com.jarvys.agent.crew.CrewCoordinationEvidence;
import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import com.jarvys.agent.crew.CrewMessage;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=android.app.Application.class)
public class CentralYieldCheckpointCodecTest {
    private CoreAgentLoop.Checkpoint rich() {
        ChatAttachment attachment=new ChatAttachment("12345678-1234-1234-1234-123456789abc","photo.jpg","image/jpeg",12,
            ChatAttachment.Kind.IMAGE,"12345678-1234-1234-1234-123456789abc-photo.jpg");
        ConversationTurn.IncomingMetadata incoming=new ConversationTurn.IncomingMetadata(ConversationInput.Origin.USER,
            "input","native","user","steer",Arrays.asList("reference"),false,null);
        ConversationTurn user=ConversationTurn.restoreExactSnapshot(new ConversationTurn("user","secret-like sk-test-value\ud800",3,incoming),
            "retained thinking",1,Collections.singletonList(attachment),"exact model content",Collections.singletonList(new ConversationTurn.Image("aW1hZ2U=",2,3)),"user-id");
        user.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,"central","user-id",""));
        Map<String,Object> args=new LinkedHashMap<>();args.put("z",Arrays.asList("sensitive value",null,true));args.put("a",2);
        ConversationTurn calls=ConversationTurn.toolCalls("evidence",Collections.singletonList(new ModelReply.Call("call","read",args)),
            Collections.singletonList(ModelReply.ProviderOutputItem.reasoning("opaque", "codex","responses","model")));
        calls.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH,"central","batch",""));
        ConversationTurn result=ConversationTurn.toolResult("call","read","exact retained result");
        result.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH_RESULT,"central","batch","call"));
        return new CoreAgentLoop.Checkpoint(Arrays.asList(user,calls,result,ConversationTurn.systemEvent("native-event","event evidence"),
            ConversationTurn.modelError("network")),Collections.singleton("input"),Collections.singletonMap("call","RESULT"),0,
            Collections.emptyMap(),Collections.singletonMap("call","full result before budgeting"));
    }
    @Test public void roundTripsEveryRetainedPartWithoutRedactionOrTruncation() throws Exception {
        String encoded=CentralYieldCheckpointCodec.encodeCanonical(rich());
        assertTrue(encoded.contains("sk-test-value"));assertTrue(encoded.contains("\\ud800"));
        CoreAgentLoop.Checkpoint restored=CentralYieldCheckpointCodec.decode(new JSONObject(encoded));
        assertEquals(encoded,CentralYieldCheckpointCodec.encodeCanonical(restored));
        ConversationTurn user=restored.transcript.get(0);
        assertEquals("retained thinking",user.thinking);assertEquals("exact model content",user.modelContent);
        assertEquals("photo.jpg",user.attachments.get(0).name);assertEquals("aW1hZ2U=",user.images.get(0).jpegBase64);
        assertEquals("user-id",user.source().sourceId);assertEquals("input",user.incomingMetadata.id);
        assertEquals("full result before budgeting",restored.completedToolResults.get("call"));
        assertEquals(0,restored.genuineUserIndex);
    }
    @Test public void typedInternalProvenanceRemainsAgentEvidenceAndCannotBecomeUser() throws Exception {
        ConversationInput.Provenance provenance=new ConversationInput.Provenance("central","","","worker","agent-message",
            ConversationInput.Kind.MESSAGE,ConversationInput.Provenance.PrincipalRole.PEER);
        ConversationTurn turn=new ConversationTurn("assistant","untrusted historical input",4,
            ConversationTurn.IncomingMetadata.forInternalInput(provenance)).withDurableMessageId("agent-message");
        turn.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,"central","agent-message",""));
        CoreAgentLoop.Checkpoint checkpoint=new CoreAgentLoop.Checkpoint(Collections.singletonList(turn),Collections.singleton("agent-message"),Collections.emptyMap(),-1);
        CoreAgentLoop.Checkpoint restored=CentralYieldCheckpointCodec.decode(CentralYieldCheckpointCodec.encode(checkpoint));
        assertEquals(ConversationInput.Origin.AGENT,restored.transcript.get(0).incomingMetadata.origin);
        assertEquals(ConversationInput.Provenance.PrincipalRole.PEER,restored.transcript.get(0).incomingMetadata.provenance.principalRole);
        JSONObject elevated=CentralYieldCheckpointCodec.encode(checkpoint);
        elevated.getJSONArray("transcript").getJSONObject(0).put("role","user");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(elevated));
    }

    @Test public void canonicalizationSortsNestedObjectKeysButConservesArrayOrder() throws Exception {
        JSONObject first=new JSONObject().put("z",new JSONObject().put("b",2).put("a",1)).put("a",new JSONArray().put(3).put(2));
        JSONObject second=new JSONObject().put("a",new JSONArray().put(3).put(2)).put("z",new JSONObject().put("a",1).put("b",2));
        assertEquals(CentralYieldCheckpointCodec.canonical(first),CentralYieldCheckpointCodec.canonical(second));
        assertTrue(CentralYieldCheckpointCodec.canonical(first).contains("[3,2]"));
    }
    @Test public void unknownFieldsWrongTypesAndUnresolvedStatesFailClosed() throws Exception {
        JSONObject unknown=CentralYieldCheckpointCodec.encode(rich()).put("unknown",true);
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(unknown));
        JSONObject type=CentralYieldCheckpointCodec.encode(rich()).put("genuineUserIndex","0");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(type));
        for(String state:Arrays.asList("INTENT","STARTED","INTERRUPTED_UNCERTAIN","OTHER")) {
            JSONObject value=CentralYieldCheckpointCodec.encode(rich());value.getJSONObject("toolLifecycle").put("call",state);
            assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(value));
        }
    }
    @Test public void malformedAttachmentsAndMismatchedSourceAreNeverDroppedOrRepaired() throws Exception {
        JSONObject missing=CentralYieldCheckpointCodec.encode(rich());missing.getJSONArray("transcript").getJSONObject(0).getJSONArray("attachments").put(0,JSONObject.NULL);
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(missing));
        JSONObject source=CentralYieldCheckpointCodec.encode(rich());source.getJSONArray("transcript").getJSONObject(0).getJSONObject("source").put("sourceId","different");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(source));
        JSONObject attachment=CentralYieldCheckpointCodec.encode(rich());attachment.getJSONArray("transcript").getJSONObject(0).getJSONArray("attachments").getJSONObject(0).put("relativePath","../escape");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(attachment));
    }
    @Test public void duplicateAppliedIdentityAndUnmatchedCompletedResultAreRejected() throws Exception {
        JSONObject duplicate=CentralYieldCheckpointCodec.encode(rich());duplicate.getJSONArray("appliedIncomingIds").put("input");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(duplicate));
        JSONObject result=CentralYieldCheckpointCodec.encode(rich());result.getJSONObject("completedToolResults").put("not-a-call","not admitted");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(result));
    }
    private CoreAgentLoop.Checkpoint argumentSnapshot(Object value, String invalidReason) {
        Map<String,Object> args=new LinkedHashMap<>();args.put("value",value);
        ConversationTurn calls=ConversationTurn.toolCalls("",Collections.singletonList(
            new ModelReply.Call("call","read",args,invalidReason)));
        ConversationTurn result=ConversationTurn.toolResult("call","read","historical observation");
        return new CoreAgentLoop.Checkpoint(Arrays.asList(calls,result),Collections.emptySet(),
            Collections.singletonMap("call",invalidReason==null?"RESULT":"NEVER_LAUNCHED"),-1);
    }

    @Test public void nullEmptyNulAndAllUnicodeFormsPreserveExactUtf16AndDistinctHashes() throws Exception {
        Object[] values={null,"",String.valueOf((char)0),"before"+(char)0+"after",
            String.valueOf((char)0xd800),String.valueOf((char)0xdc00),"\ud83d\ude00", "Español 日本語 café"};
        Set<String> digests=new HashSet<>();
        for(Object original:values) {
            String encoded=CentralYieldCheckpointCodec.encodeCanonical(argumentSnapshot(original,null));
            byte[] bytes=encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String readBack=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(encoded,readBack);
            CoreAgentLoop.Checkpoint restored=CentralYieldCheckpointCodec.decode(new JSONObject(readBack));
            Object actual=restored.transcript.get(0).toolCalls.get(0).arguments.get("value");
            if(original==null)assertNull(actual);
            else assertArrayEquals(((String)original).toCharArray(),((String)actual).toCharArray());
            assertEquals(encoded,CentralYieldCheckpointCodec.encodeCanonical(restored));
            String digest=CrewCheckpointStore.digest(bytes);
            assertTrue("Different exact input must not collapse into an existing hash",digests.add(digest));
            assertEquals(digest,CrewCheckpointStore.digest(CentralYieldCheckpointCodec.encodeCanonical(restored)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test public void exactInvalidArgumentReasonRemainsHistoricalNeverLaunchedEvidence() throws Exception {
        CoreAgentLoop.Checkpoint original=argumentSnapshot(null,"Malformed provider arguments");
        String encoded=CentralYieldCheckpointCodec.encodeCanonical(original);
        CoreAgentLoop.Checkpoint restored=CentralYieldCheckpointCodec.decode(new JSONObject(encoded));
        assertEquals("Malformed provider arguments",restored.transcript.get(0).toolCalls.get(0).invalidArgumentsReason);
        assertEquals("NEVER_LAUNCHED",restored.toolLifecycle.get("call"));
        assertEquals(encoded,CentralYieldCheckpointCodec.encodeCanonical(restored));
        JSONObject missing=CentralYieldCheckpointCodec.encode(original);
        missing.getJSONArray("transcript").getJSONObject(0).getJSONArray("calls").getJSONObject(0).remove("invalidArgumentsReason");
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(missing));
    }

    @Test public void requiredFieldsCannotDisappearAtAnyStructuredLevel() throws Exception {
        for(String key:Arrays.asList("version","transcript","appliedIncomingIds","toolLifecycle","genuineUserIndex","incorporations","completedToolResults")) {
            JSONObject value=CentralYieldCheckpointCodec.encode(rich());value.remove(key);
            assertThrows("Missing root field: "+key,IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(value));
        }
        for(String key:Arrays.asList("kind","role","content","attachments","images","source","incomingMetadata","thinking","modelContent","durableMessageId")) {
            JSONObject value=CentralYieldCheckpointCodec.encode(rich());value.getJSONArray("transcript").getJSONObject(0).remove(key);
            assertThrows("Missing turn field: "+key,IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(value));
        }
    }

    @Test public void nestedUnknownFieldsAndInvalidImageShapesCannotBeSilentlyDiscarded() throws Exception {
        for(String part:Arrays.asList("source","attachment","image","incoming","call")) {
            JSONObject value=CentralYieldCheckpointCodec.encode(rich());JSONObject user=value.getJSONArray("transcript").getJSONObject(0);
            JSONObject target;
            switch(part) {
                case "source":target=user.getJSONObject("source");break;
                case "attachment":target=user.getJSONArray("attachments").getJSONObject(0);break;
                case "image":target=user.getJSONArray("images").getJSONObject(0);break;
                case "incoming":target=user.getJSONObject("incomingMetadata");break;
                default:target=value.getJSONArray("transcript").getJSONObject(1).getJSONArray("calls").getJSONObject(0);
            }
            target.put("unexpected",true);
            assertThrows("Unknown field in "+part,IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(value));
        }
        JSONObject invalid=CentralYieldCheckpointCodec.encode(rich());invalid.getJSONArray("transcript").getJSONObject(0)
            .getJSONArray("images").getJSONObject(0).put("width",0);
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(invalid));
        JSONObject undercount=CentralYieldCheckpointCodec.encode(rich());undercount.getJSONArray("transcript").getJSONObject(0).put("imageCount",0);
        assertThrows(IllegalArgumentException.class,()->CentralYieldCheckpointCodec.decode(undercount));
    }

    @Test public void exactSourceAttachmentAndImageChangesChangePersistedIdentity() throws Exception {
        JSONObject original=CentralYieldCheckpointCodec.encode(rich());
        String baseline=CrewCheckpointStore.digest(CentralYieldCheckpointCodec.canonical(original)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String part:Arrays.asList("source","attachment","image")) {
            JSONObject changed=new JSONObject(CentralYieldCheckpointCodec.canonical(original));
            JSONObject user=changed.getJSONArray("transcript").getJSONObject(0);
            if("source".equals(part))user.getJSONObject("source").put("conversationId","other-session");
            else if("attachment".equals(part))user.getJSONArray("attachments").getJSONObject(0).put("sizeBytes",13);
            else user.getJSONArray("images").getJSONObject(0).put("jpegBase64","Y2hhbmdlZA==");
            String canonical=CentralYieldCheckpointCodec.canonical(changed);
            assertNotEquals(baseline,CrewCheckpointStore.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(canonical,CentralYieldCheckpointCodec.encodeCanonical(CentralYieldCheckpointCodec.decode(changed)));
        }
    }

    @Test public void nonemptyHistoricalIncorporationProofConservesExactBindingAndTransport() throws Exception {
        String central = "jarvys-central";
        String cycle = "00000000-0000-0000-0000-000000000101";
        String firstCycle = "00000000-0000-0000-0000-000000000102";
        String messageId = "retained-worker-question";
        String text = "Which dates? Preserve this exact whitespace.  \n";
        CrewCoordinationEvidence source = CrewCoordinationEvidence.create(
            central, "retained-mission", "child-worker", cycle, messageId, "QUESTION",
            text, Collections.emptyList(), 123456789L);
        CrewInputBinding binding = CrewInputBinding.treeReport(
            source.evidenceId, central, "retained-workspace", central, "retained-assignment",
            "retained-mission", "central-parent", messageId, source.contentSha256, cycle,
            "child-worker", CrewMessage.Type.QUESTION, text);
        CrewInputIncorporation proof = new CrewInputIncorporation(binding, firstCycle,
            CrewInputIncorporation.TransportKind.INBOX,
            CrewInputIncorporation.transportSha256(text));
        ConversationInput.Provenance provenance = new ConversationInput.Provenance(
            central, "retained-workspace", "retained-assignment", "child-worker", messageId,
            ConversationInput.Kind.QUESTION, ConversationInput.Provenance.PrincipalRole.SUBORDINATE);
        ConversationTurn input = new ConversationTurn("assistant", text, 5,
            ConversationTurn.IncomingMetadata.forInternalInput(provenance)).withDurableMessageId(messageId);
        input.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,
            central, messageId, ""));
        CoreAgentLoop.Checkpoint original = new CoreAgentLoop.Checkpoint(
            Collections.singletonList(input), Collections.singleton(messageId), Collections.emptyMap(), -1,
            Collections.singletonMap(messageId, proof), Collections.emptyMap());
        String encoded = CentralYieldCheckpointCodec.encodeCanonical(original);
        CoreAgentLoop.Checkpoint restored = CentralYieldCheckpointCodec.decode(new JSONObject(encoded));
        assertEquals(1, restored.incorporations.size());
        CrewInputIncorporation actual = restored.incorporations.get(messageId);
        assertNotNull(actual);
        assertTrue(proof.samePayload(actual));
        assertTrue(binding.samePayload(actual.binding));
        assertEquals(firstCycle, actual.firstIncorporationCycleId);
        assertEquals(CrewInputIncorporation.TransportKind.INBOX, actual.transportKind);
        assertEquals(CrewInputIncorporation.transportSha256(text), actual.transportSha256);
        assertEquals(Collections.singleton(messageId), restored.appliedIncomingIds);
        assertEquals(ConversationInput.Origin.AGENT, restored.transcript.get(0).incomingMetadata.origin);
        assertEquals(ConversationInput.Provenance.PrincipalRole.SUBORDINATE,
            restored.transcript.get(0).incomingMetadata.provenance.principalRole);
        assertEquals(encoded, CentralYieldCheckpointCodec.encodeCanonical(restored));
        assertEquals(CrewCheckpointStore.digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            CrewCheckpointStore.digest(CentralYieldCheckpointCodec.encodeCanonical(restored)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test public void opaqueCodexItemsAndProviderIdentityAreExactlyRetainedOnAssistantAndCalls() throws Exception {
        String opaque = new JSONObject().put("type", "reasoning")
            .put("id", "rs_native_history")
            .put("encrypted_content", "opaque-not-executable:" + (char) 0 + (char) 0xd800)
            .put("summary", new JSONArray().put(new JSONObject()
                .put("type", "summary_text").put("text", "Retained summary 日本語"))).toString();
        List<ModelReply.ProviderOutputItem> assistantItems = Arrays.asList(
            ModelReply.ProviderOutputItem.reasoning(opaque, "codex", "responses", "retained-model"),
            ModelReply.ProviderOutputItem.message("Exact assistant item \ud83d\ude00"));
        ConversationTurn assistant = ConversationTurn.assistantReply("retained assistant", 3, assistantItems)
            .withDurableMessageId("retained-assistant");
        assistant.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,
            "jarvys-central", "retained-assistant", ""));
        ConversationTurn calls = ConversationTurn.toolCalls("retained proposal",
            Collections.singletonList(new ModelReply.Call("retained-call", "read_conversation_artifact",
                Collections.singletonMap("artifact_id", "retained-artifact"))), Arrays.asList(
                    ModelReply.ProviderOutputItem.reasoning(opaque, "codex", "responses", "retained-model"),
                    ModelReply.ProviderOutputItem.toolCall("retained-call")));
        calls.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH,
            "jarvys-central", "retained-batch", ""));
        ConversationTurn result = ConversationTurn.toolResult("retained-call", "read_conversation_artifact", "retained output");
        result.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH_RESULT,
            "jarvys-central", "retained-batch", "retained-call"));
        CoreAgentLoop.Checkpoint original = new CoreAgentLoop.Checkpoint(Arrays.asList(assistant, calls, result),
            Collections.emptySet(), Collections.singletonMap("retained-call", "RESULT"), -1);
        String encoded = CentralYieldCheckpointCodec.encodeCanonical(original);
        CoreAgentLoop.Checkpoint restored = CentralYieldCheckpointCodec.decode(new JSONObject(encoded));
        for (int index : Arrays.asList(0, 1)) {
            ModelReply.ProviderOutputItem reasoning = restored.transcript.get(index).providerOutputItems.get(0);
            assertEquals(ModelReply.ProviderOutputItem.Kind.REASONING, reasoning.kind);
            assertArrayEquals(opaque.toCharArray(), reasoning.value.toCharArray());
            assertTrue(reasoning.belongsTo("codex", "responses", "retained-model"));
        }
        assertEquals(ModelReply.ProviderOutputItem.Kind.MESSAGE,
            restored.transcript.get(0).providerOutputItems.get(1).kind);
        assertEquals("Exact assistant item \ud83d\ude00", restored.transcript.get(0).providerOutputItems.get(1).value);
        assertEquals(ModelReply.ProviderOutputItem.Kind.TOOL_CALL,
            restored.transcript.get(1).providerOutputItems.get(1).kind);
        assertEquals("retained-call", restored.transcript.get(1).providerOutputItems.get(1).value);
        assertEquals(encoded, CentralYieldCheckpointCodec.encodeCanonical(restored));
    }

    @Test public void sourcedCompactionRetainsExactWrappedSummaryCountAndProtectedGenuineUser() throws Exception {
        ConversationTurn summary = ConversationTurn.compactionSummary("Decision: retain trip dates and accessibility needs.", 17);
        summary.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.COMPACTION,
            "jarvys-central", "retained-compaction", ""));
        ConversationTurn user = new ConversationTurn("user", "Continue the original request", 18)
            .withDurableMessageId("original-user");
        user.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,
            "jarvys-central", "original-user", ""));
        CoreAgentLoop.Checkpoint original = new CoreAgentLoop.Checkpoint(Arrays.asList(summary, user),
            Collections.singleton("original-user"), Collections.emptyMap(), 1);
        String encoded = CentralYieldCheckpointCodec.encodeCanonical(original);
        CoreAgentLoop.Checkpoint restored = CentralYieldCheckpointCodec.decode(new JSONObject(encoded));
        ConversationTurn actual = restored.transcript.get(0);
        assertEquals(ConversationTurn.Kind.COMPACTION_SUMMARY, actual.kind);
        assertEquals(summary.content, actual.content);
        assertEquals(summary.modelContent, actual.modelContent);
        assertEquals(17, actual.summarizedMessageCount);
        assertEquals(summary.source(), actual.source());
        assertEquals(ConversationTurn.Source.Kind.COMPACTION, actual.source().kind);
        assertEquals(1, restored.genuineUserIndex);
        assertEquals("original-user", restored.transcript.get(restored.genuineUserIndex).durableMessageId);
        assertEquals(encoded, CentralYieldCheckpointCodec.encodeCanonical(restored));
    }

}
