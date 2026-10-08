package com.jarvys.agent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.util.Base64;

import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.mcp.McpConnectionManager;
import com.jarvys.agent.mcp.McpConnectionStatus;
import com.jarvys.agent.mcp.McpServerRepository;
import com.jarvys.agent.mcp.McpServerToolRegistry;
import com.jarvys.agent.mcp.McpToolDefinition;
import com.jarvys.agent.skills.SkillEntry;
import com.jarvys.agent.providers.ProviderClientRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Planner/Operator/Summarizer backed by the user-selected real provider. */
public final class ProviderAgentModel implements AgentModel {
    private final ProviderSettings settings;
    private final ModelProviderClient provider;
    private final List<ToolSpec> availableTools;
    private final Map<String, McpToolDefinition> dynamicMcpTools;
    private final Set<String> runAllowedTools;
    private final String sessionId;
    private final String conversationContext;
    private final McpConnectionManager mcpConnections;
    private final PlannerAgent planner;
    private final OperatorAgent operator;
    private final SummarizerAgent summarizer;
    private final MultimodalTargetResolver targetResolver;

    public ProviderAgentModel(Context context, ToolRegistry registry) {
        this(context, registry, Collections.emptyList());
    }

    public ProviderAgentModel(Context context, ToolRegistry registry, List<SkillEntry> selectedSkills) {
        this(context, registry, selectedSkills, "jarvys-session-" + java.util.UUID.randomUUID(), "");
    }

    public ProviderAgentModel(Context context, ToolRegistry registry, List<SkillEntry> selectedSkills,
                              String sessionId, String conversationContext) {
        settings = new ProviderSettings(context);
        this.sessionId = sessionId;
        this.conversationContext = conversationContext == null ? "" : conversationContext;
        SecretStore secrets = SecretStore.get(context);
        provider = ProviderClientRegistry.createClient(settings.getProvider(), secrets, settings);
        targetResolver = new MultimodalTargetResolver(this);
        McpServerRepository mcpRepository = McpServerRepository.Companion.get(context);
        mcpConnections = McpConnectionManager.Companion.get(context);
        List<McpToolDefinition> exposedMcpTools = new ArrayList<>();
        for (McpToolDefinition tool : new McpServerToolRegistry(mcpRepository).all()) {
            if (mcpConnections.state(tool.getServerId()).getStatus() == McpConnectionStatus.READY) exposedMcpTools.add(tool);
        }
        Map<String, McpToolDefinition> dynamicTools = new LinkedHashMap<>();
        List<ToolSpec> allTools = new ArrayList<>(registry.toolsForOperator());
        for (McpToolDefinition tool : exposedMcpTools) {
            if (dynamicTools.put(tool.getModelName(), tool) != null) {
                throw new IllegalStateException("Duplicate namespaced MCP tool: " + tool.getModelName());
            }
            allTools.add(McpAgentToolAdapter.INSTANCE.toToolSpec(tool));
        }
        dynamicMcpTools = Collections.unmodifiableMap(dynamicTools);
        Set<String> allowedIntersection = null;
        StringBuilder skillContext = new StringBuilder();
        for (SkillEntry skill : com.jarvys.agent.skills.SkillScopePolicy.forProfile(selectedSkills, null)) {
            if (skill.getValidationError() != null) {
                throw new IllegalArgumentException("Skill '" + skill.getMetadata().getId() + "' is invalid: " + skill.getValidationError());
            }
            if (!skill.getMetadata().getAllowedTools().isEmpty()) {
                Set<String> allowed = new LinkedHashSet<>(skill.getMetadata().getAllowedTools());
                if (allowedIntersection == null) allowedIntersection = allowed;
                else allowedIntersection.retainAll(allowed);
            }
            int remaining = MAX_SKILL_CONTEXT_CHARS - skillContext.length();
            if (remaining <= 0) break;
            String header = "\n--- BEGIN SKILL " + skill.getMetadata().getId() + " (" + skill.getMetadata().getName() + ") ---\n";
            String trailer = "\n--- END SKILL " + skill.getMetadata().getId() + " ---\n";
            skillContext.append(header);
            int bodyBudget = Math.max(0, Math.min(skill.getBody().length(), remaining - header.length() - trailer.length()));
            skillContext.append(skill.getBody(), 0, bodyBudget).append(trailer);
        }
        if (allowedIntersection != null) {
            Set<String> declared = new LinkedHashSet<>();
            for (ToolSpec tool : allTools) declared.add(tool.name);
            Set<String> unknown = new LinkedHashSet<>(allowedIntersection);
            unknown.removeAll(declared);
            if (!unknown.isEmpty()) throw new IllegalArgumentException("Selected skills allow unavailable tools: " + unknown);
            Set<String> finalAllowed = allowedIntersection;
            allTools.removeIf(tool -> !finalAllowed.contains(tool.name));
            runAllowedTools = Collections.unmodifiableSet(new LinkedHashSet<>(finalAllowed));
        } else {
            runAllowedTools = null;
        }
        availableTools = Collections.unmodifiableList(allTools);
        planner = new PlannerAgent(provider, settings.getModel());
        operator = new OperatorAgent(provider, availableTools, skillContext.toString());
        summarizer = new SummarizerAgent(provider);
    }

    @Override
    public ToolResult invokeDynamicTool(String name, Map<String, Object> arguments, CancellationToken token) {
        McpToolDefinition tool = dynamicMcpTools.get(name);
        if (tool == null) return null;
        try {
            token.throwIfCancelled();
            JSONObject response = mcpConnections.callTool(
                    tool.getServerId(), tool.getWireName(), new JSONObject(arguments), token);
            token.throwIfCancelled();
            JSONObject envelope = com.jarvys.agent.mcp.McpToolSecurity.INSTANCE
                    .boundedUntrustedResult(tool.getServerAlias(), tool.getWireName(), response);
            String output = envelope.toString();
            if (envelope.optBoolean("isError", false)) return ToolResult.failure(name, output);
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("text", output);
            mapped.put("images", Collections.emptyList());
            return ToolResult.success(name, mapped);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            return ToolResult.failure(name, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    @Override
    public boolean isToolAllowed(String name) {
        return runAllowedTools == null || runAllowedTools.contains(name);
    }

    private static void appendBounded(StringBuilder output, String value) {
        if (value == null || value.isEmpty() || output.length() >= MAX_MCP_RESULT_CHARS) return;
        if (output.length() > 0) output.append('\n');
        int remaining = MAX_MCP_RESULT_CHARS - output.length();
        output.append(value, 0, Math.min(value.length(), remaining));
        if (value.length() > remaining) output.append("…[truncated]");
    }

    private static Map<String, Object> decodeMcpImage(JSONObject block, String toolName) {
        String encoded = block.optString("data", "");
        if (encoded.isEmpty() || encoded.length() > MAX_MCP_IMAGE_BASE64_CHARS) return null;
        byte[] bytes;
        try { bytes = Base64.decode(encoded, Base64.DEFAULT); }
        catch (IllegalArgumentException invalid) { return null; }
        if (bytes.length > MAX_MCP_IMAGE_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while ((long) (bounds.outWidth / options.inSampleSize) * (bounds.outHeight / options.inSampleSize) > MAX_MCP_IMAGE_PIXELS) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) return null;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
            String jpeg = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("base64", jpeg);
            image.put("width", bitmap.getWidth());
            image.put("height", bitmap.getHeight());
            image.put("label", "MCP tool " + toolName + " image");
            return image;
        } catch (Exception error) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    @Override
    public List<String> createPlan(String goal, CancellationToken token) {
        return planner.plan(goal, sessionId, conversationContext, token);
    }

    @Override
    public List<String> createPlan(String goal, String sessionId, String conversationContext, CancellationToken token) {
        return planner.plan(goal, sessionId, conversationContext, token);
    }

    @Override
    public OperatorDecision decide(AgentState state, CancellationToken token) {
        return operator.decide(state, token);
    }

    @Override
    public String summarizeStep(StepRecord step, CancellationToken token) {
        return summarizer.summarize(step, sessionId, token);
    }

    @Override
    public String summarizeStep(StepRecord step, String sessionId, CancellationToken token) {
        return summarizer.summarize(step, sessionId, token);
    }

    @Override
    public String visionQuery(String instruction, ScreenData screen, CancellationToken token) {
        ModelReply reply = provider.complete(
                "You are Jarvys Visual Perception. Inspect only the attached current Android screenshot. "
                        + "Follow the requested output format exactly, use normalized 0-1000 pixel coordinates, "
                        + "and report uncertainty instead of guessing.",
                instruction, java.util.Collections.singletonList(screen), java.util.Collections.emptyList(),
                sessionId, token);
        if (reply.text.trim().isEmpty()) throw new IllegalStateException("Vision model returned no text");
        return reply.text.trim();
    }

    @Override
    public boolean verifyVisualTarget(String description, int[] normalizedPoint, ScreenData screen,
                                      CancellationToken token) {
        if (normalizedPoint == null || normalizedPoint.length != 2) throw new IllegalArgumentException("Visual check needs [x,y]");
        String prompt = "Return only JSON {\"visible\":boolean,\"confidence\":number,\"reason\":string}. "
                + "Is the described intended target visibly present at the marked normalized screen coordinate? "
                + "Coordinate [" + normalizedPoint[0] + "," + normalizedPoint[1] + "] on a 0-1000 scale. "
                + "Target description: " + description;
        String text = visionQuery(prompt, screen, token);
        try {
            int start = text.indexOf('{'), end = text.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalStateException("Vision validator did not return JSON");
            JSONObject verdict = new JSONObject(text.substring(start, end + 1));
            return verdict.optBoolean("visible", false) && verdict.optDouble("confidence", 0.0) >= 0.65;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Vision validator returned malformed JSON", e);
        }
    }

    @Override
    public String explore(String query, String feedback, AgentState state, CancellationToken token) {
        return targetResolver.explore(query, feedback, state, token);
    }

    @Override
    public String detectObjects(List<String> queries, AgentState state, CancellationToken token) {
        return targetResolver.detectObjects(queries, state, token);
    }

    @Override
    public String getOcrList(AgentState state, CancellationToken token) {
        return targetResolver.getOcrList(state, token);
    }

    @Override
    public String askPerception(String query, int nx, int ny, List<String> detectQueries,
                                AgentState state, CancellationToken token) {
        return targetResolver.askPerception(query, nx, ny, detectQueries, state, token);
    }

    @Override
    public String diagnose(String query, AgentState state, CancellationToken token) {
        if (state.observation == null) throw new IllegalStateException("Diagnoser has no current screen observation");
        StringBuilder evidence = new StringBuilder("Goal: ").append(state.initialGoal)
                .append("\nDiagnostic question: ").append(query)
                .append("\nRecent recorded steps:\n");
        int from = Math.max(0, state.steps.size() - 6);
        for (int i = from; i < state.steps.size(); i++) {
            StepRecord step = state.steps.get(i);
            evidence.append("step ").append(step.number).append(" ")
                    .append(step.decisions).append(" => ").append(step.result).append('\n');
        }
        ModelReply reply = provider.complete(
                "You are Jarvys Diagnoser. Diagnose only from the attached current screenshot, accessibility hierarchy, "
                        + "and recorded step results. You do not have Android Logcat or video access. State uncertainty clearly.",
                evidence.toString(), java.util.Collections.singletonList(state.observation),
                java.util.Collections.emptyList(), state.sessionId, token);
        if (reply.text.trim().isEmpty()) throw new IllegalStateException("Diagnoser returned an empty answer");
        return reply.text.trim();
    }

    private static final int MAX_SKILL_CONTEXT_CHARS = 48 * 1024;
    private static final int MAX_MCP_RESULT_CHARS = 64 * 1024;
    private static final int MAX_MCP_IMAGE_BASE64_CHARS = 8 * 1024 * 1024;
    private static final int MAX_MCP_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final long MAX_MCP_IMAGE_PIXELS = 4_000_000L;

}
