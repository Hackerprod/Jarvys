package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One principal provider attempt. Budgeting and dispatch share these final logical inputs. */
final class PreparedModelRequest {
    final String instructions;
    final List<ConversationTurn> transcript;
    final List<ConversationTurn> sourceTranscript;
    final String prompt;
    final List<ToolSpec> tools;
    final int contextWindow;
    final com.jarvys.agent.chatdb.InteractionProtectionSnapshot protection;
    final MemoryBoundary memoryBoundary;

    static final class MemoryBoundary {
        final MemoryWriteGate.Ticket ticket;
        final com.jarvys.agent.chatdb.MemoryNamespace namespace;
        MemoryBoundary(MemoryWriteGate.Ticket ticket, com.jarvys.agent.chatdb.MemoryNamespace namespace) {
            this.ticket = ticket; this.namespace = namespace;
        }
    }

    PreparedModelRequest(String instructions, List<ConversationTurn> transcript, String prompt,
                         List<ToolSpec> tools, int contextWindow) {
        this(instructions, transcript, prompt, tools, contextWindow, null, transcript, null);
    }

    PreparedModelRequest(String instructions, List<ConversationTurn> transcript, String prompt,
                         List<ToolSpec> tools, int contextWindow,
                         com.jarvys.agent.chatdb.InteractionProtectionSnapshot protection, List<ConversationTurn> sourceTranscript,
                         MemoryBoundary memoryBoundary) {
        this.protection = protection;
        this.memoryBoundary = memoryBoundary;
        this.sourceTranscript = Collections.unmodifiableList(new ArrayList<>(sourceTranscript));
        this.instructions = instructions == null ? "" : instructions;
        this.transcript = Collections.unmodifiableList(new ArrayList<>(transcript));
        this.prompt = prompt == null ? "" : prompt;
        List<ToolSpec> declarations = new ArrayList<>();
        for (ToolSpec tool : tools) declarations.add(new ToolSpec(tool.name, tool.source,
                tool.description, tool.category, tool.status, tool.propertyTypes, tool.required,
                freezeMap(tool.jsonSchema())));
        this.tools = Collections.unmodifiableList(declarations);
        this.contextWindow = contextWindow;
    }

    private static Map<String, Object> freezeMap(Map<String, ?> input) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) copy.put(entry.getKey(), freeze(entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("model_schema_key_invalid");
                copy.put((String) entry.getKey(), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) copy.add(freeze(item));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
    int estimateTokens() { return estimate(transcript); }
    int overheadTokens() { return estimate(Collections.emptyList()); }
    private int estimate(List<ConversationTurn> turns) {
        StringBuilder declarations = new StringBuilder();
        for (ToolSpec tool : tools) declarations.append(tool.name).append('\n')
                .append(tool.description).append('\n').append(tool.jsonSchema()).append('\n');
        return ConversationCompactionPolicy.estimatePreparedTokens(instructions, declarations.toString(), turns, prompt);
    }
    void requireFits(CancellationToken token) {
        token.throwIfCancelled();
        if (ConversationCompactionPolicy.shouldCompact(estimateTokens(), contextWindow))
            throw new IllegalStateException("context_protected_budget_exceeded");
    }
}
