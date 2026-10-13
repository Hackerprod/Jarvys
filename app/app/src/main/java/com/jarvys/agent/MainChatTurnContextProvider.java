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

    MainChatTurnContextProvider(LocalRunStore store, String sessionId, String cursorMessageId) {
        if (store == null || sessionId == null || cursorMessageId == null || cursorMessageId.isEmpty())
            throw new IllegalArgumentException("Main chat steering requires its durable message cursor");
        this.store = store;
        this.sessionId = sessionId;
        this.cursorMessageId = cursorMessageId;
    }

    @Override public synchronized List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
        List<JSONObject> rows = store.readUserMessagesAfter(sessionId, cursorMessageId);
        if (rows.isEmpty()) return Collections.emptyList();
        List<CoreAgentLoop.IncomingMessage> messages = new ArrayList<>();
        for (JSONObject row : rows) {
            String id = row.optString("messageId", "");
            if (id.isEmpty()) continue;
            messages.add(CoreAgentLoop.IncomingMessage.mainChat(id, row.optString("content", "")));
            cursorMessageId = id;
        }
        return Collections.unmodifiableList(messages);
    }

    @Override public String takeUntrustedContext() { return ""; }
}
