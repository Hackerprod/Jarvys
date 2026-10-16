package com.jarvys.agent;

import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, user-safe result returned into the ReAct transcript. */
public final class CoreToolResult {
    public final boolean success;
    public final String content;
    public final boolean completeContentRequired;
    public final String previewId;
    public final boolean finishRun;
    /** Exact result of this invocation only; generic tool results intentionally have no sidecar. */
    public final IncomingBatch incomingBatch;
    /** Durable persistence receipt used by generic post-turn evidence validation. */
    public final String persistenceEvidence;

    public static final class IncomingBatch {
        public final List<String> messageIds;
        public final Map<String, CrewInputBinding> bindings;
        public final String transportSha256;
        private IncomingBatch(List<CrewMessage> answers, String rawContent) {
            List<String> ids = new ArrayList<>(); Map<String, CrewInputBinding> values = new LinkedHashMap<>();
            for (CrewMessage answer : answers) {
                if (answer == null || answer.type != CrewMessage.Type.ANSWER || !"chief".equals(answer.from)
                        || answer.activity != null || ids.contains(answer.id))
                    throw new IllegalArgumentException("Invalid exact chief answer batch");
                ids.add(answer.id);
                if (answer.inputBinding != null && answer.inputBinding.matchesDelivery(answer))
                    values.put(answer.id, answer.inputBinding);
            }
            this.messageIds = Collections.unmodifiableList(ids);
            this.bindings = Collections.unmodifiableMap(values);
            this.transportSha256 = CrewInputIncorporation.transportSha256(rawContent);
        }
        public boolean matches(String toolName, String rawContent) {
            return "ask_chief".equals(toolName) && transportSha256.equals(CrewInputIncorporation.transportSha256(rawContent));
        }
    }

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun) {
        this(success, content, completeContentRequired, previewId, finishRun, null);
    }

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun, IncomingBatch incomingBatch) {
        this(success, content, completeContentRequired, previewId, finishRun, incomingBatch, null);
    }

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun, IncomingBatch incomingBatch, String persistenceEvidence) {
        this.incomingBatch = incomingBatch;
        this.persistenceEvidence = persistenceEvidence;
        this.success = success;
        this.content = content == null ? "" : content;
        this.completeContentRequired = completeContentRequired;
        this.previewId = previewId;
        this.finishRun = finishRun;
    }

    public static CoreToolResult crewAnswers(List<CrewMessage> answers) {
        List<CrewMessage> exact = new ArrayList<>(answers);
        String content = exact.isEmpty()
                ? "The user sent new guidance; it will be delivered as a trusted user turn on the next model turn."
                : CrewManager.formatMessages(exact);
        return new CoreToolResult(true, content, false, null, false, new IncomingBatch(exact, content));
    }

    public static CoreToolResult success(String content) { return new CoreToolResult(true, content, false, null, false); }
    public static CoreToolResult complete(String content) { return new CoreToolResult(true, content, true, null, false); }
    public static CoreToolResult finish(String content) { return new CoreToolResult(true, content, false, null, true); }
    public static CoreToolResult preview(String content, String previewId) {
        return new CoreToolResult(true, content, false, previewId, false);
    }
    public static CoreToolResult failure(String content) { return new CoreToolResult(false, content, false, null, false); }
    public static CoreToolResult persisted(String content, String evidence) {
        if (evidence == null || evidence.trim().isEmpty()) throw new IllegalArgumentException("Persistence evidence is required");
        return new CoreToolResult(true, content, false, null, false, null, evidence);
    }
}
