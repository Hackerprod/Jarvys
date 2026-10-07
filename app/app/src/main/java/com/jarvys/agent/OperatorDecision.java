package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OperatorDecision {
    public final List<Map<String, Object>> toolCalls;
    public final boolean finish;
    public final String message;

    public OperatorDecision(List<Map<String, Object>> calls, boolean finish, String message) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> call : calls) copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(call)));
        this.toolCalls = Collections.unmodifiableList(copy);
        this.finish = finish;
        this.message = message == null ? "" : message;
    }

    public static OperatorDecision action(String name, Map<String, Object> arguments) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("name", name);
        call.put("arguments", new LinkedHashMap<>(arguments));
        return new OperatorDecision(Collections.singletonList(call), false, "");
    }

    public static OperatorDecision finish(String message) {
        return new OperatorDecision(Collections.emptyList(), true, message);
    }
}
