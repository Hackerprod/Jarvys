package com.jarvys.agent;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads messages durably appended to the active conversation since its starting input. */
final class MainChatTurnContextProvider implements CoreAgentLoop.TurnContextProvider {
    private final LocalRunStore store;
    private final String sessionId;
    private final String initialCursorMessageId;
    private String cursorMessageId;
    private final Set<String> collectedMessageIds = new HashSet<>();
    private final Set<String> incorporatedMessageIds = new HashSet<>();
    private boolean closed;

    MainChatTurnContextProvider(LocalRunStore store, String sessionId, String cursorMessageId) {
        if (store == null || sessionId == null || cursorMessageId == null || cursorMessageId.isEmpty())
            throw new IllegalArgumentException("Main chat steering requires its durable message cursor");
        this.store = store;
        this.sessionId = sessionId;
        this.initialCursorMessageId = cursorMessageId;
        this.cursorMessageId = cursorMessageId;
        this.incorporatedMessageIds.addAll(store.incorporatedConversationMessageIds(sessionId));
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

    /** Acknowledges only messages this provider handed to the loop and the loop appended. */
    @Override public synchronized void durableMessagesIncorporated(
            List<CoreAgentLoop.IncomingMessage> messages) {
        if (messages == null) return;
        List<String> acknowledged = new ArrayList<>();
        for (CoreAgentLoop.IncomingMessage message : messages) {
            if (message != null && collectedMessageIds.contains(message.id))
                acknowledged.add(message.id);
        }
        store.markConversationMessagesIncorporated(sessionId, acknowledged);
        incorporatedMessageIds.addAll(acknowledged);
    }

    /** Dispatch a persisted follow-up only while this run can still consume it. */
    synchronized boolean dispatchPendingRequest(String requestId) {
        if (closed || store.readConversationMetadata(sessionId).deleted) return false;
        return store.dispatchInterruptRequest(sessionId, requestId) != null;
    }

    /** Latest persisted message that the loop has not confirmed in its transcript. */
    synchronized String latestUnincorporatedSteeringMessageId() {
        String latest = "";
        for (JSONObject row : store.readUserMessagesAfter(sessionId, initialCursorMessageId)) {
            String id = row.optString("messageId", "");
            if (!id.isEmpty() && !incorporatedMessageIds.contains(id)) latest = id;
        }
        return latest;
    }

    synchronized boolean hasUnconsumedMessages() {
        return !latestUnincorporatedSteeringMessageId().isEmpty();
    }

    private List<CoreAgentLoop.IncomingMessage> readAfterCursor() {
        List<JSONObject> rows = store.readUserMessagesAfter(sessionId, cursorMessageId);
        if (rows.isEmpty()) return Collections.emptyList();
        List<CoreAgentLoop.IncomingMessage> messages = new ArrayList<>();
        for (JSONObject row : rows) {
            String id = row.optString("messageId", "");
            if (id.isEmpty()) continue;
            cursorMessageId = id;
            if (incorporatedMessageIds.contains(id)) continue;
            messages.add(CoreAgentLoop.IncomingMessage.mainChat(id, row.optString("content", "")));
            collectedMessageIds.add(id);
        }
        return Collections.unmodifiableList(messages);
    }

    @Override public String takeUntrustedContext() { return ""; }
}
