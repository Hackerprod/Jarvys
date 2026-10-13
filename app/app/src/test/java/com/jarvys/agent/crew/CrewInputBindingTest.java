package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import com.jarvys.agent.CoreInputIncorporationTest;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONObject;
import org.junit.Test;

public class CrewInputBindingTest {
    @Test public void strictRoundTripDoesNotCoerceOrAcceptUnknownFields() throws Exception {
        CrewInputBinding binding = CoreInputIncorporationTest.bound("id", "  text\n", true).inputBinding;
        assertTrue(binding.samePayload(CrewInputBinding.fromJsonStrict(new JSONObject(binding.toJson().toString()))));
        Iterator<String> keys = binding.toJson().keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject missing = binding.toJson(); missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> CrewInputBinding.fromJsonStrict(missing));
            JSONObject badType = binding.toJson().put(key, key.equals("schemaVersion") ? "1" : 1);
            assertThrows(IllegalArgumentException.class, () -> CrewInputBinding.fromJsonStrict(badType));
        }
        assertThrows(IllegalArgumentException.class, () -> CrewInputBinding.fromJsonStrict(binding.toJson().put("extra", true)));
        assertThrows(IllegalArgumentException.class, () -> CrewInputBinding.fromJsonStrict(binding.toJson().put("deliverySha256", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> CrewInputBinding.fromJsonStrict(binding.toJson().put("admissionCycleId", "not-cycle")));
    }

    @Test public void exactWhitespaceRefsRoutingAndMalformedUnicodeAreDistinct() {
        CrewMessage original = CoreInputIncorporationTest.bound("id", "  text\n\ud800  ", true);
        CrewMessage trimmed = new CrewMessage(original.id, original.conversationId, original.from, original.to,
                original.type, original.text.trim(), original.refs, 2L);
        assertFalse(original.inputBinding.matchesDelivery(trimmed));
        assertThrows(IllegalArgumentException.class, () -> trimmed.withInputBinding(original.inputBinding));
        CrewMessage refs = new CrewMessage(original.id, original.conversationId, original.from, original.to,
                original.type, original.text, Collections.singletonList("extra"), 2L);
        assertFalse(original.inputBinding.matchesDelivery(refs));
        CrewMessage surrogate = new CrewMessage(original.id, original.conversationId, original.from, original.to,
                original.type, original.text.replace('\ud800', '\ud801'), original.refs, 2L);
        assertFalse(original.inputBinding.matchesDelivery(surrogate));
        CrewMessage later = new CrewMessage(original.id, original.conversationId, original.from, original.to,
                original.type, original.text, original.refs, 900L);
        assertTrue(original.inputBinding.matchesDelivery(later));
    }

    @Test public void messageJsonRejectsForgedOriginAndCrossOwnerRouting() throws Exception {
        CrewMessage original = CoreInputIncorporationTest.bound("id", "text", true);
        assertTrue(original.inputBinding.samePayload(CrewMessage.fromJson(original.toJson()).inputBinding));
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(original.toJson().put("from", "chief")));
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(original.toJson().put("conversationId", "other")));
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(original.toJson().put("type", "ANSWER")));
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(original.toJson().put("to", "other")));
        JSONObject forged = original.toJson().put("native_origin", "NATIVE_UI");
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(forged));
    }

    @Test public void proofTransportAndAppliedMailboxAreStrict() throws Exception {
        CrewInputBinding binding = CoreInputIncorporationTest.bound("id", "text", true).inputBinding;
        CrewInputIncorporation proof = new CrewInputIncorporation(binding, binding.admissionCycleId,
                CrewInputIncorporation.TransportKind.USER, CrewInputIncorporation.transportSha256("text"));
        assertTrue(proof.samePayload(CrewInputIncorporation.fromJsonStrict(new JSONObject(proof.toJson().toString()))));
        assertThrows(IllegalArgumentException.class, () -> new CrewInputIncorporation(binding, binding.admissionCycleId,
                CrewInputIncorporation.TransportKind.ASK_CHIEF, proof.transportSha256));
        assertThrows(IllegalArgumentException.class, () -> CrewInputIncorporation.fromJsonStrict(proof.toJson().put("schemaVersion", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new com.jarvys.agent.CoreAgentLoop.Checkpoint(Collections.emptyList(),
                Collections.emptySet(), Collections.emptyMap(), -1, Collections.singletonMap("id", proof)));
        assertThrows(IllegalArgumentException.class, () -> new com.jarvys.agent.CoreAgentLoop.Checkpoint(Collections.emptyList(),
                Collections.singleton("id"), Collections.emptyMap(), -1, Collections.singletonMap("other", proof)));
    }
}
