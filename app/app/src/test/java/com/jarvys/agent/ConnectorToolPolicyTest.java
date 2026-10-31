package com.jarvys.agent;

import com.jarvys.agent.skills.AllowedToolPolicy;
import com.jarvys.agent.connectors.ConnectorDefinition;
import com.jarvys.agent.connectors.ConnectorOperation;
import com.jarvys.agent.connectors.ConnectorRegistry;
import com.jarvys.agent.connectors.ConnectorConnectionPreferences;
import com.jarvys.agent.connectors.ConnectorRuntime;
import com.jarvys.agent.connectors.ConnectorConnectionFlow;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConnectorToolPolicyTest {
    @Test public void connectorNameAndGroupPassSkillValidation() {
        assertNull(AllowedToolPolicy.validate(
                Arrays.asList("calendar_search_events", "connector:calendar"),
                Set.of("calendar_search_events", "calendar_create_event"), Set.of("connector:calendar")));
        assertEquals("Unknown or disabled tools: connector:unknown",
                AllowedToolPolicy.validate(Collections.singletonList("connector:unknown"),
                        Set.of("calendar_search_events"), Set.of("connector:calendar")));
    }

    @Test public void subagentAllowedToolsMatchIndividualNamesGroupsAndWildcard() {
        String name = "calendar_search_events";
        String group = "connector:calendar";
        assertTrue(CoreToolAccessPolicy.matches(name, group, null));
        assertTrue(CoreToolAccessPolicy.matches(name, group, Collections.singletonList(name)));
        assertTrue(CoreToolAccessPolicy.matches(name, group, Collections.singletonList(group)));
        assertTrue(CoreToolAccessPolicy.matches(name, group, Collections.singletonList("*")));
        assertFalse(CoreToolAccessPolicy.matches(name, group, Collections.singletonList("connector:contacts")));
    }

    @Test public void connectorToolNamesAreFunctionCallingSafeAndSubagentSubsetPreservesRequestedTool() {
        assertEquals("calendar_search_events", CoreConnectorTool.toolName("calendar", "search_events"));
        assertEquals("calendar_sync_events", CoreConnectorTool.toolName("Calendar", "sync.events"));
        CoreTool tool = new CoreTool() {
            private final ToolSpec spec = new ToolSpec("calendar_search_events", "connector", "Search", "connector:calendar",
                    ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(java.util.Map<String, Object> arguments, CancellationToken token) {
                return CoreToolResult.success("ok");
            }
        };
        CoreToolRegistry child = new CoreToolRegistry(Collections.singletonList(tool)).subset(Collections.singletonList("calendar_search_events"));
        assertEquals(Collections.singletonList("calendar_search_events"), child.names());
    }

    @Test public void chatUsesConnectorDisplayLabel() {
        ConnectorRegistry registry = ConnectorRegistry.Companion.createForTests(new ConnectorConnectionPreferences() {
            @Override public boolean isConnected(String id) { return true; }
            @Override public void setConnected(String id, boolean connected) { }
        }, permission -> true, com.jarvys.agent.connectors.ApprovalGate.INSTANCE);
        ConnectorRuntime runtime = new ConnectorRuntime() {
            @Override public void connect(java.util.Map<String, String> configuration, java.util.Map<String, String> secrets) { }
            @Override public void disconnect() { }
            @Override public com.jarvys.agent.connectors.ConnectorWritePreparation prepareWrite(
                    String operation, JSONObject arguments, CancellationToken token) { throw new UnsupportedOperationException(); }
            @Override public JSONObject invoke(String operation, JSONObject arguments, CancellationToken token) { return new JSONObject(); }
        };
        ConnectorDefinition definition = new ConnectorDefinition("calendar", "Calendar", "1", "Calendar",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), runtime,
                Collections.emptyList(), Collections.emptyList(), "Calendar",
                ConnectorConnectionFlow.PERMISSIONS, null, null);
        ConnectorOperation operation = new ConnectorOperation("search_events", "Search events", new JSONObject(), false,
                "Search Calendar Events", Collections.emptyMap(), Collections.emptyList());
        CoreTool tool = new CoreConnectorTool(registry, definition, operation);
        CoreToolRegistry tools = new CoreToolRegistry(Collections.singletonList(tool));
        assertEquals("calendar_search_events", tool.declaration().name);
        assertEquals("Search Calendar Events", tools.displayName("calendar_search_events"));
    }
}
