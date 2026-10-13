package com.jarvys.agent;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads messages durably appended to the active conversation since its starting input. */
final class MainChatTurnContextProvider implements CoreAgentLoop.TurnContextProvider {
    private final LocalRunStore store;
    private final String sessionId;
    private String cursorMessageId;
    private String latestSteeringMessageId = "";
    private boolean closed;

    MainChatTurnContextProvider(LocalRunStore store, String sessionId, String cursorMessageId) {
        if (store == null || sessionId == null || cursorMessageId == null || cursorMessageId.isEmpty())
            throw new IllegalArgumentException("Main chat steering requires its durable message cursor");
        this.store = store;
        this.sessionId = sessionId;
        this.cursorMessageId = cursorMessageId;
    }

    @Override public synchronized List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
        if (closed) return Collections.emptyList();
        return readAfterCursor();
    }

    /** A final empty drain and closure share this monitor with steering admission. */
    @Override public synchronized List<CoreAgentLoop.IncomingMessage> takeDurableMessagesAtFinalCheckpoint() {
        if (closed) return Collections.emptyList();
        List<CoreAgentLoop.IncomingMessage> messages = readAfterCursor();
        if (messages.isEmpty()) closed = true;
        return messages;
    }

    @Override public synchronized void closeForRunExit() {
        closed = true;
    }

    /** Dispatch a persisted follow-up only while this run can still consume it. */
    synchronized boolean dispatchPendingRequest(String requestId) {
        if (closed || store.readConversationMetadata(sessionId).deleted) return false;
        return store.dispatchInterruptRequest(sessionId, requestId) != null;
    }

    synchronized String latestSteeringMessageId() {
        List<JSONObject> unconsumed = store.readUserMessagesAfter(sessionId, cursorMessageId);
        if (!unconsumed.isEmpty()) {
            String latest = unconsumed.get(unconsumed.size() - 1).optString("messageId", "");
            if (!latest.isEmpty()) return latest;
        }
        return latestSteeringMessageId;
    }

    synchronized boolean hasUnconsumedMessages() {
        return !store.readUserMessagesAfter(sessionId, cursorMessageId).isEmpty();
    }

    private List<CoreAgentLoop.IncomingMessage> readAfterCursor() {
        List<JSONObject> rows = store.readUserMessagesAfter(sessionId, cursorMessageId);
        if (rows.isEmpty()) return Collections.emptyList();
        List<CoreAgentLoop.IncomingMessage> messages = new ArrayList<>();
        for (JSONObject row : rows) {
            String id = row.optString("messageId", "");
            if (id.isEmpty()) continue;
            messages.add(CoreAgentLoop.IncomingMessage.mainChat(id, row.optString("content", "")));
            cursorMessageId = id;
            latestSteeringMessageId = id;
        }
        return Collections.unmodifiableList(messages);
    }

    @Override public String takeUntrustedContext() { return ""; }
}
