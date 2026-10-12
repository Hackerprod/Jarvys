package com.jarvys.agent;

import static org.junit.Assert.*;
import com.jarvys.agent.crew.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class CrewCoordinationCheckpointTest {
    @Test public void productionSanitizerPreservesReferenceWithoutPretendingCopiedBodyIsExact() throws Exception {
        String text = " { \"password\" : \"must-not-survive\", \"ordinary\" : \"x\" } ";
        CrewCoordinationEvidence source = CrewCoordinationEvidence.create("source", "mission", "worker",
                "11111111-1111-1111-1111-111111111111", "request", "QUESTION", text,
                Collections.singletonList("password=private-ref-value"), 100L);
        CrewMessage message = new CrewMessage("request", "source", "worker", "chief", CrewMessage.Type.QUESTION,
                text, source.refs, 100L).withCoordination(source);
        // This is the exact production metadata transformation used by CrewCheckpointStore.save.
        JSONObject sanitized = new JSONObject(CrewCheckpointStore.sanitizeText(message.toJson().toString()));
        CrewMessage historical = CrewMessage.fromJson(sanitized);
        assertFalse(historical.text.contains("must-not-survive"));
        assertFalse(historical.refs.get(0).contains("private-ref-value"));
        assertNotEquals(source.text, historical.text);
        assertTrue(historical.coordination.matches(source));
        CrewCoordinationEvidence transformed = CrewCoordinationEvidence.create("source", "mission", "worker", source.cycleId,
                "request", "QUESTION", historical.text, historical.refs, 100L);
        assertEquals(source.evidenceId, transformed.evidenceId);
        assertNotEquals(source.contentSha256, transformed.contentSha256);
        assertFalse(historical.coordination.matches(transformed));
    }
    @Test public void checkpointCycleReaderDoesNotInferLegacyCycle() throws Exception {
        java.lang.reflect.Method read = CrewCheckpointCoordinator.class.getDeclaredMethod("historicalCycleId", JSONObject.class);
        read.setAccessible(true);
        assertEquals("", read.invoke(null, new JSONObject()));
        String cycle = "11111111-1111-1111-1111-111111111111";
        assertEquals(cycle, read.invoke(null, new JSONObject().put("cycleId", cycle)));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> read.invoke(null, new JSONObject().put("cycleId", 7)));
    }
}
