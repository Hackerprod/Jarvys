package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.crew.BotDefinition;
import com.jarvys.agent.crew.CrewProfileRepository;
import java.util.concurrent.CancellationException;

/** Explicit text-only icon generation through the already signed-in Codex image backend. */
public final class BotIconService {
    public static final int MAX_PROMPT_CHARS = 16000;
    public enum Reason { UNAVAILABLE, INVALID_PROMPT, INVALID_TARGET, BUILT_IN, DISABLED, CONFLICT,
        SESSION, ACCESS, QUOTA, POLICY, NETWORK, INCOMPLETE, INVALID_IMAGE, API, STORAGE }

    /** Fixed local messages deliberately omit provider text, prompts, credentials and file paths. */
    public static final class Failure extends IllegalStateException {
        public final Reason reason;
        Failure(Reason reason, String message) { super(message); this.reason = reason; }
    }

    private final Context context;
    private final String sessionId;
    private final ProviderSettings settings;
    private final SecretStore secrets;
    private final CrewProfileRepository repository;
    private final BotIconStore icons;
    private final CodexImageGenerationClient client;

    /** UI entry point: its fresh text request never loads conversation attachments or history. */
    public BotIconService(Context context) {
        this(context, "bot-icon-editor", new ProviderSettings(context));
    }

    BotIconService(Context context, String sessionId, ProviderSettings settings) {
        this(context, sessionId, settings, SecretStore.get(context.getApplicationContext()));
    }

    private BotIconService(Context context, String sessionId, ProviderSettings settings, SecretStore secrets) {
        this(context, sessionId, settings, secrets, new CrewProfileRepository(context), new BotIconStore(context),
                new CodexImageGenerationClient(new CodexOAuthManager(secrets), settings));
    }

    BotIconService(Context context, String sessionId, ProviderSettings settings, SecretStore secrets,
                   CrewProfileRepository repository, BotIconStore icons, CodexImageGenerationClient client) {
        this.context = context.getApplicationContext();
        this.sessionId = sessionId;
        this.settings = settings;
        this.secrets = secrets;
        this.repository = repository;
        this.icons = icons;
        this.client = client;
    }

    public boolean isAvailable() {
        return CodexImageGenerationTool.isAvailable(context, settings, 0, sessionId, secrets);
    }

    /** Blocking; invoke from the UI's I/O scope with a standalone cancellable token. */
    public BotDefinition generateAndAssign(String botId, int expectedRevision, String prompt, CancellationToken token) {
        if (token == null) throw new IllegalArgumentException("Cancellation token is required");
        token.throwIfCancelled();
        if (token.isCrewRun() || !isAvailable()) throw unavailable();
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new Failure(Reason.INVALID_PROMPT, "Describe the bot icon you want to generate.");
        }
        if (prompt.length() > MAX_PROMPT_CHARS || prompt.indexOf('\0') >= 0) {
            throw new Failure(Reason.INVALID_PROMPT, "Use an icon description of at most 16000 characters without null characters.");
        }
        if (botId == null || !botId.matches("[a-z][a-z0-9._-]*") || expectedRevision < 1) {
            throw new Failure(Reason.INVALID_TARGET, "Choose an existing custom bot and its current revision.");
        }
        BotDefinition original = current(botId);
        requireEditable(original, expectedRevision);
        String stagedRef = null;
        boolean committed = false;
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForAttachments();
             CommitGate commitGate = new CommitGate(token)) {
            // Forward the complete freeform prompt verbatim. No topic map, role instructions,
            // implicit references, files, chat messages, or previous images are sent.
            CodexImageGenerationClient.GeneratedImage generated = client.generate(sessionId, prompt, "1024x1024", token);
            token.throwIfCancelled();
            if (generated == null || !"image/png".equals(generated.mimeType)) {
                throw new Failure(Reason.INVALID_IMAGE, "The image service did not return a valid PNG icon.");
            }
            stagedRef = icons.save(botId, generated.bytes, token);
            token.throwIfCancelled();
            if (!isAvailable()) throw unavailable();
            final String newRef = stagedRef;
            final BotDefinition[] saved = new BotDefinition[1];
            commitGate.commit(() -> {
                requireEditable(current(botId), expectedRevision);
                // Repository CAS is serialized with other edits/disable/icon operations. It only
                // changes iconRef/revision, preserving the active CrewProfile runtime snapshot.
                saved[0] = repository.setIcon(botId, expectedRevision, newRef);
            });
            committed = true;
            if (!original.iconRef.isEmpty()) {
                try { icons.delete(botId, original.iconRef); } catch (RuntimeException ignoredCleanup) { }
            }
            return saved[0];
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (Failure failure) { throw failure; }
        catch (CodexImageGenerationException failure) {
            Reason reason = Reason.valueOf(failure.kind.name());
            throw new Failure(reason, "Bot icon generation failed (" + reason.name() + "). The existing icon was kept.");
        } catch (IllegalArgumentException failure) {
            // A concurrent metadata update can make the final CAS fail after the first read.
            BotDefinition latest = current(botId);
            requireEditable(latest, expectedRevision);
            throw new Failure(Reason.INVALID_IMAGE, "The generated bot icon could not be validated. The existing icon was kept.");
        } catch (RuntimeException failure) {
            token.throwIfCancelled();
            throw new Failure(Reason.STORAGE, "Could not finish saving the bot icon. Reopen the bot before retrying.");
        } finally {
            if (!committed && stagedRef != null) cleanupUnassigned(botId, stagedRef);
        }
    }

    private BotDefinition current(String botId) {
        try { return repository.definition(botId); }
        catch (RuntimeException failure) {
            throw new Failure(Reason.INVALID_TARGET, "The bot is unavailable. Reopen Bots before retrying.");
        }
    }

    private static void requireEditable(BotDefinition bot, int expectedRevision) {
        if (bot.builtIn) throw new Failure(Reason.BUILT_IN, "Runtime bot icons cannot be changed.");
        if (!bot.enabled) throw new Failure(Reason.DISABLED, "Enable this bot before generating its icon.");
        if (bot.revision != expectedRevision) {
            throw new Failure(Reason.CONFLICT, "The bot changed while editing. Reopen it before generating another icon.");
        }
    }

    private void cleanupUnassigned(String botId, String stagedRef) {
        try {
            // A storage verification exception may follow a successful atomic metadata write.
            // Never remove bytes that the repository actually references, or when its state is unknown.
            if (!stagedRef.equals(repository.definition(botId).iconRef)) icons.delete(botId, stagedRef);
        } catch (RuntimeException unknownStorageState) { }
    }

    private static Failure unavailable() {
        return new Failure(Reason.UNAVAILABLE, "Bot icon generation requires the existing ChatGPT Codex sign-in and image backend.");
    }

    /**
     * Standalone UI tokens do not themselves serialize cancel() with runIfActive(). This operation's
     * cancellation callback and metadata commit share a lock: cancellation before this gate wins;
     * cancellation after the commit gate wins is a late cancellation of an already committed action.
     * The controller-backed runIfActive gate is retained for the main-chat STOP latch as well.
     */
    static final class CommitGate implements AutoCloseable {
        private final CancellationToken token;
        private final Runnable unregister;
        private boolean cancelled;

        CommitGate(CancellationToken token) {
            this.token = token;
            unregister = token.registerCancelAction(this::cancel);
        }

        private synchronized void cancel() { cancelled = true; }

        synchronized void commit(Runnable action) {
            if (cancelled) throw new CancellationException("Bot icon generation cancelled");
            token.throwIfCancelled();
            if (!token.runIfActive(action)) throw new CancellationException("Bot icon generation cancelled");
        }

        @Override public void close() { unregister.run(); }
    }
}
