package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ToolLoopDetectorTest {
    @Test public void changingErrorDetailsStillCountAsRepeatedFailures() {
        ToolLoopDetector detector = new ToolLoopDetector();
        String key = detector.argumentsKey(call(Collections.singletonMap("path", "/project")));
        for (int i = 0; i < 3; i++) {
            detector.record("ls", key, false, "identity.json -> .journal-" + i + ".tmp");
        }
        assertEquals(1, detector.noProgressStreak("ls", key));
        assertEquals(3, detector.consecutiveFailureCount("ls", key));
    }

    @Test public void changedArgumentsAndSuccessfulResultPermitRecovery() {
        ToolLoopDetector detector = new ToolLoopDetector();
        String failed = detector.argumentsKey(call(Collections.singletonMap("path", "/project")));
        String changed = detector.argumentsKey(call(Collections.singletonMap("path", "/memory")));
        detector.record("ls", failed, false, "failure one");
        detector.record("ls", changed, true, "memory files");
        detector.record("ls", failed, false, "failure two");
        assertEquals(1, detector.consecutiveFailureCount("ls", failed));
        assertEquals(0, detector.consecutiveFailureCount("ls", changed));
        detector.record("ls", failed, true, "recovered");
        assertEquals(0, detector.consecutiveFailureCount("ls", failed));
        detector.record("ls", failed, false, "new failure");
        assertEquals(1, detector.consecutiveFailureCount("ls", failed));
    }

    @Test public void reorderedArgumentsHaveTheSameFailureSignature() {
        ToolLoopDetector detector = new ToolLoopDetector();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("path", "/project");
        first.put("max_chars", 2000);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("max_chars", 2000);
        second.put("path", "/project");
        String key = detector.argumentsKey(call(first));
        detector.record("ls", key, false, "failure");
        assertEquals(key, detector.argumentsKey(call(second)));
        assertEquals(1, detector.consecutiveFailureCount("ls", detector.argumentsKey(call(second))));
    }

    private static ModelReply.Call call(Map<String, Object> arguments) {
        return new ModelReply.Call("test", "ls", arguments);
    }
}
