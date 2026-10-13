package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelReply {
    /** An item from a provider's assistant output, kept opaque unless its adapter understands it. */
    public static final class ProviderOutputItem {
        public enum Kind { REASONING, MESSAGE, TOOL_CALL }
        public final Kind kind;
        /** Raw JSON for REASONING; assistant text for MESSAGE; call ID for TOOL_CALL. */
        public final String value;
        /** Request identity is present only for newly captured provider reasoning. */
        public final String provider;
        public final String api;
        public final String model;

        private ProviderOutputItem(Kind kind, String value, String provider, String api, String model) {
            this.kind = kind;
            this.value = value == null ? "" : value;
            this.provider = provider;
            this.api = api;
            this.model = model;
        }

        public static ProviderOutputItem reasoning(String rawJson) {
            return new ProviderOutputItem(Kind.REASONING, rawJson, null, null, null);
        }
        public static ProviderOutputItem reasoning(String rawJson, String provider, String api, String model) {
            return new ProviderOutputItem(Kind.REASONING, rawJson, provider, api, model);
        }
        public static ProviderOutputItem message(String text) {
            return new ProviderOutputItem(Kind.MESSAGE, text, null, null, null);
        }
        public static ProviderOutputItem toolCall(String callId) {
            return new ProviderOutputItem(Kind.TOOL_CALL, callId, null, null, null);
        }

        public ProviderOutputItem withIdentity(String provider, String api, String model) {
            if (kind != Kind.REASONING) return this;
            return reasoning(value, provider, api, model);
        }

        public boolean belongsTo(String provider, String api, String model) {
            return kind == Kind.REASONING && this.provider != null && this.api != null && this.model != null
                    && this.provider.equals(provider) && this.api.equals(api) && this.model.equals(model);
        }
    }

    public static final class Call {
        public final String id;
        public final String name;
        public final Map<String, Object> arguments;
        /** Non-null when the provider returned arguments that could not be parsed as an object. */
        public final String invalidArgumentsReason;

        public Call(String id, String name, Map<String, Object> arguments) {
            this(id, name, arguments, null);
        }

        public Call(String id, String name, Map<String, Object> arguments, String invalidArgumentsReason) {
            this.id = id;
            this.name = name;
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments == null ? Collections.emptyMap() : arguments));
            this.invalidArgumentsReason = invalidArgumentsReason == null || invalidArgumentsReason.trim().isEmpty()
                    ? null : invalidArgumentsReason.trim();
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
    /** Provider-specific ordered output items. Only the matching provider adapter may replay them. */
    public final List<ProviderOutputItem> providerOutputItems;

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
        this(text, calls, rawResponseBody, httpStatus, model, contextTokensUsed, diagnostics,
                Collections.emptyList());
    }

    public ModelReply(String text, List<Call> calls, String rawResponseBody, Integer httpStatus,
                      String model, Integer contextTokensUsed, ResponseDiagnostics diagnostics,
                      List<ProviderOutputItem> providerOutputItems) {
        this.text = text == null ? "" : text;
        this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
        this.rawResponseBody = rawResponseBody == null ? "" : rawResponseBody;
        this.httpStatus = httpStatus;
        this.model = model == null ? "" : model;
        this.contextTokensUsed = contextTokensUsed != null && contextTokensUsed > 0 ? contextTokensUsed : null;
        this.diagnostics = diagnostics == null ? ResponseDiagnostics.unknown() : diagnostics;
        this.providerOutputItems = Collections.unmodifiableList(new ArrayList<>(
                providerOutputItems == null ? Collections.emptyList() : providerOutputItems));
    }
}
