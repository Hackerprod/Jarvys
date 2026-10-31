package com.jarvys.agent;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ToolLoopDetectorTest {
    @Test public void thirdIdenticalFailureIsTheFirstInterventionPoint() {
        ToolLoopDetector detector = new ToolLoopDetector();
        ModelReply.Call call = call(Collections.singletonMap("path", "/project"));
        String key = detector.argumentsKey(call);

        assertEquals(1, detector.recordOutcome(call.name, key, false, "permission denied"));
        assertEquals(2, detector.recordOutcome(call.name, key, false, "permission denied"));
        assertEquals(ToolLoopDetector.FAILURE_THRESHOLD,
                detector.recordOutcome(call.name, key, false, "permission denied"));
    }

    @Test public void changedFailureResultDoesNotAccumulateTheSameSignature() {
        ToolLoopDetector detector = new ToolLoopDetector();
        ModelReply.Call call = call(Collections.singletonMap("path", "/project"));
        String key = detector.argumentsKey(call);

        assertEquals(1, detector.recordOutcome(call.name, key, false, "permission denied"));
        assertEquals(1, detector.recordOutcome(call.name, key, false, "file missing"));
        assertEquals(2, detector.recordOutcome(call.name, key, false, "file missing"));
    }

    @Test public void canonicalArgumentsIgnoreMapInsertionOrder() {
        ToolLoopDetector detector = new ToolLoopDetector();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("path", "/project");
        first.put("max_chars", 2000);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("max_chars", 2000);
        second.put("path", "/project");
        String firstKey = detector.argumentsKey(call(first));
        String secondKey = detector.argumentsKey(call(second));

        assertEquals(firstKey, secondKey);
        assertEquals(1, detector.recordOutcome("ls", firstKey, false, "failure"));
        assertEquals(2, detector.recordOutcome("ls", secondKey, false, "failure"));
    }

    @Test public void successfulRepeatsNeverTriggerContinuousDetectionAndResetFailureStreak() {
        ToolLoopDetector detector = new ToolLoopDetector();
        String key = detector.argumentsKey(call(Collections.singletonMap("path", "/project")));
        assertEquals(1, detector.recordOutcome("ls", key, false, "failure"));
        assertEquals(0, detector.recordOutcome("ls", key, true, "unchanged result"));
        for (int i = 0; i < 100; i++) assertEquals(0, detector.recordOutcome("ls", key, true, "unchanged result"));
        assertEquals(1, detector.recordOutcome("ls", key, false, "failure"));
    }

    private static ModelReply.Call call(Map<String, Object> arguments) {
        return new ModelReply.Call("test", "ls", arguments);
    }
}
