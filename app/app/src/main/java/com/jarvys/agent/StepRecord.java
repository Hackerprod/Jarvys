package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One graph execution_check record, completed with Validator's post-state and result. */
public final class StepRecord {
    public final int number;
    public final ScreenData before;
    public final List<Map<String, Object>> decisions;
    public final long startedAtMillis;
    public ScreenData after;
    public String result;
    public boolean success;
    public String summary;

    public StepRecord(int number, ScreenData before, List<Map<String, Object>> decisions) {
        this.number = number;
        this.before = before;
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> decision : decisions) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(decision)));
        }
        this.decisions = Collections.unmodifiableList(copy);
        this.startedAtMillis = System.currentTimeMillis();
    }
}
