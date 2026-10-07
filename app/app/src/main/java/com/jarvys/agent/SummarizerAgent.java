package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Real provider-backed visual-transition summarizer, dispatched asynchronously by the graph. */
public final class SummarizerAgent {
    private final ModelProviderClient provider;

    public SummarizerAgent(ModelProviderClient provider) {
        this.provider = provider;
    }

    public String summarize(StepRecord step, CancellationToken token) {
        return summarize(step, "jarvys-summary-" + java.util.UUID.randomUUID(), token);
    }

    public String summarize(StepRecord step, String sessionId, CancellationToken token) {
        token.throwIfCancelled();
        List<ScreenData> images = new ArrayList<>();
        if (step.before != null) images.add(step.before);
        if (step.after != null) images.add(step.after);
        ModelReply reply = provider.complete(AgentPrompts.SUMMARIZER, AgentPrompts.summarizerUser(step),
                images, Collections.emptyList(), sessionId, token);
        if (reply.text.trim().isEmpty()) throw new IllegalStateException("Summarizer returned empty text");
        return reply.text.trim();
    }
}
