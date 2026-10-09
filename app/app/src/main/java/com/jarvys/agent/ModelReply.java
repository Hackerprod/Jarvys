package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelReply {
    public static final class Call {
        public final String id;
        public final String name;
        public final Map<String, Object> arguments;

        public Call(String id, String name, Map<String, Object> arguments) {
            this.id = id;
            this.name = name;
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    public final String text;
    public final List<Call> calls;
    public final String rawResponseBody;
    public final Integer httpStatus;
    public final String model;
    public final Integer contextTokensUsed;
    /** Additive evidence only. Phase 0 does not use this to change loop or history behavior. */
    public final ResponseDiagnostics diagnostics;

    public ModelReply(String text, List<Call> calls) {
        this(text, calls, "", null, "");
    }

    public ModelReply(String text, List<Call> calls, String rawResponseBody, Integer httpStatus, String model) {
        this(text, calls, rawResponseBody, httpStatus, model, null);
    }

    public ModelReply(String text, List<Call> calls, String rawResponseBody, Integer httpStatus,
                      String model, Integer contextTokensUsed) {
        this(text, calls, rawResponseBody, httpStatus, model, contextTokensUsed,
                ResponseDiagnostics.unknown());
    }

    public ModelReply(String text, List<Call> calls, String rawResponseBody, Integer httpStatus,
                      String model, Integer contextTokensUsed, ResponseDiagnostics diagnostics) {
        this.text = text == null ? "" : text;
        this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
        this.rawResponseBody = rawResponseBody == null ? "" : rawResponseBody;
        this.httpStatus = httpStatus;
        this.model = model == null ? "" : model;
        this.contextTokensUsed = contextTokensUsed != null && contextTokensUsed > 0 ? contextTokensUsed : null;
        this.diagnostics = diagnostics == null ? ResponseDiagnostics.unknown() : diagnostics;
    }
}
