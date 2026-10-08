package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.crew.BotDefinition;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Main-chat-only generated icon assignment; never delegated and never grants bot capabilities. */
public final class BotIconGenerationTool implements CoreTool {
    public static final String NAME = "generate_bot_icon";
    private final BotIconService service;
    private final ToolSpec declaration;

    public BotIconGenerationTool(Context context, String sessionId, ProviderSettings settings) {
        this(new BotIconService(context, sessionId, settings));
    }

    BotIconGenerationTool(BotIconService service) {
        this.service = service;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bot_id", property("string", "Exact stable ID of an existing custom enabled bot, obtained from the bot catalog. Never guess."));
        Map<String, Object> revision = property("integer", "Exact current bot definition revision from the catalog; stale revisions fail without replacing its icon.");
        revision.put("minimum", 1);
        properties.put("expected_revision", revision);
        Map<String, Object> prompt = property("string", "Freeform visual description explicitly requested by the user for this bot icon. Only this text is sent; no attachments, references, URLs or file paths are resolved.");
        prompt.put("minLength", 1);
        prompt.put("maxLength", BotIconService.MAX_PROMPT_CHARS);
        properties.put("prompt", prompt);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("bot_id", "expected_revision", "prompt"));
        schema.put("additionalProperties", false);
        declaration = new ToolSpec(NAME, "jarvys/bots",
                "Generate and assign a new icon to one existing enabled custom bot only when the user explicitly asks for image/icon generation. "
                        + "Uses the existing signed-in ChatGPT Codex image backend and its quota; no paid API fallback. "
                        + "Use a freeform thematic prompt, never a fixed topic map. Do not generate proactively or reuse private conversation images. "
                        + "First read the bot catalog for bot_id and expected_revision. Built-in Coding and Android-use bots are immutable. "
                        + "This changes only icon metadata, never runtime instructions, tools, skills or permissions. Main chat only.",
                "bots", ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(),
                Arrays.asList("bot_id", "expected_revision", "prompt"), schema);
    }

    public static boolean isAvailable(Context context, ProviderSettings settings, int depth, String sessionId) {
        return CodexImageGenerationTool.isAvailable(context, settings, depth, sessionId);
    }

    @Override public boolean canDelegate() { return false; }
    @Override public ToolSpec declaration() { return declaration; }
    @Override public String auditDetail(Map<String, Object> arguments) { return "Explicit custom bot icon generation"; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        token.throwIfCancelled();
        if (token.isCrewRun() || !service.isAvailable()) {
            return CoreToolResult.failure("Bot icon generation is available only in the main chat with ChatGPT Codex image access.");
        }
        if (arguments == null || arguments.size() != 3
                || !arguments.keySet().containsAll(Arrays.asList("bot_id", "expected_revision", "prompt"))
                || !(arguments.get("bot_id") instanceof String) || !(arguments.get("prompt") instanceof String)) {
            return CoreToolResult.failure("Provide only bot_id, expected_revision, and a freeform prompt. Image references, attachments, URLs and paths are not inputs.");
        }
        Integer revision = exactRevision(arguments.get("expected_revision"));
        if (revision == null) return CoreToolResult.failure("expected_revision must be the current positive integer from the bot catalog.");
        try {
            BotDefinition saved = service.generateAndAssign((String) arguments.get("bot_id"), revision,
                    (String) arguments.get("prompt"), token);
            return CoreToolResult.success("Bot icon generated and assigned. bot_id: " + saved.id + "; revision: " + saved.revision + ".");
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (BotIconService.Failure failure) { return CoreToolResult.failure(failure.getMessage()); }
    }

    private static Integer exactRevision(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            int exact = new java.math.BigDecimal(value.toString()).intValueExact();
            return exact > 0 ? exact : null;
        } catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("description", description);
        return value;
    }
}
