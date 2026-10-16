package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import java.util.List;
import java.util.Collections;

public interface ModelProviderClient {
    /** Incremental user-facing text only; providers must not send reasoning or tool arguments here. */
    interface TextDeltaListener {
        void onTextDelta(String delta);
    }

    ModelReply complete(String systemPrompt, String userPrompt, List<ScreenData> images,
                        List<ToolSpec> tools, String sessionId, CancellationToken token);

    default ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                           String userPrompt, String sessionId, CancellationToken token) {
        return completeConversation(systemPrompt, history, userPrompt, Collections.emptyList(),
                Collections.emptyList(), sessionId, token);
    }

    default ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                           String userPrompt, List<ToolSpec> tools,
                                           String sessionId, CancellationToken token) {
        return completeConversation(systemPrompt, history, userPrompt, Collections.emptyList(), tools, sessionId, token);
    }

    ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                   String userPrompt, List<ScreenData> images, List<ToolSpec> tools,
                                   String sessionId, CancellationToken token);

    /** Optional streaming entry point. Providers without text streaming preserve buffered behavior. */
    default ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                            String userPrompt, List<ScreenData> images, List<ToolSpec> tools,
                                            String sessionId, CancellationToken token,
                                            TextDeltaListener listener) {
        return completeConversation(systemPrompt, history, userPrompt, images, tools, sessionId, token);
    }
}
