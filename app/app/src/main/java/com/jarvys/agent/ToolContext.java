package com.jarvys.agent;

import com.jarvys.agent.device.AccessibilityDriver;

public final class ToolContext {
    public final UnifiedController controller;
    public final AccessibilityDriver driver;
    public final AgentState state;
    public final CancellationToken token;
    public final LocalRunStore store;
    public final AgentModel model;

    ToolContext(UnifiedController controller, AccessibilityDriver driver, AgentState state,
                CancellationToken token, LocalRunStore store, AgentModel model) {
        this.controller = controller;
        this.driver = driver;
        this.state = state;
        this.token = token;
        this.store = store;
        this.model = model;
    }
}
