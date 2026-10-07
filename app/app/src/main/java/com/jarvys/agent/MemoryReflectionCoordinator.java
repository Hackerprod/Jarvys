package com.jarvys.agent;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Applies trigger eligibility, privacy gates, budgets, checkpoints, and reflection-group persistence. */
public final class MemoryReflectionCoordinator {
    public static final String TRIGGER_MANUAL = "manual";
    public static final class Outcome {
        public final boolean started;
        public final boolean changed;
        public final boolean partial;
        public final String status;
        public final String reflectionId;
        public final String summary;
        public final List<Long> revisionIds;
        public final boolean successful;
        Outcome(boolean started, boolean changed, boolean partial, String status,
                String reflectionId, String summary, List<Long> revisionIds, boolean successful) {
            this.started = started;
            this.changed = changed;
            this.partial = partial;
            this.status = status;
            this.reflectionId = reflectionId;
            this.summary = summary == null ? "" : summary;
            this.revisionIds = java.util.Collections.unmodifiableList(new ArrayList<>(revisionIds));
            this.successful = successful;
        }
    }

    private MemoryReflectionCoordinator() { }

    /** Fast local-only post-turn check, queued off the chat worker before any provider request is considered. */
    public static boolean shouldScheduleAutomatic(Context context, String sessionId, String throughMessageId) {
        Context app = context.getApplicationContext();
        if (MemoryReflectionRuntime.isAnyRunning()) return false;
        MemoryReflectionPreferences preferences = new MemoryReflectionPreferences(app);
        LocalRunStore conversation = new LocalRunStore(app);
        MemoryStore memory = new MemoryStore(app);
        boolean chatWithoutMemory = app.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
                .getBoolean(MemoryUiLogic.INSTANCE.sessionMemoryDisabledKey(sessionId), false);
        if (!memory.isEnabled() || chatWithoutMemory || !preferences.enabled()) {
            String reason = !memory.isEnabled() ? "memory disabled"
                    : chatWithoutMemory ? "chat without memory" : "automatic reflection disabled";
            conversation.advanceReflectionCheckpoint(sessionId, reason, throughMessageId);
            String status = !memory.isEnabled() ? app.getString(R.string.reflection_skip_memory)
                    : chatWithoutMemory ? app.getString(R.string.reflection_skip_chat) : app.getString(R.string.reflection_auto_off);
            return skippedUi(preferences, sessionId, status);
        }
        if (!preferences.disclosureShown()) {
            return skippedUi(preferences, sessionId, app.getString(R.string.reflection_privacy_required));
        }
        LocalRunStore.ReflectionPayload payload = conversation.buildReflectionPayload(sessionId, throughMessageId);
        boolean compactionPending = conversation.hasPendingCompactionReflection(sessionId)
                && !payload.compactionIds.isEmpty();
        if (!MemoryReflectionPolicy.shouldTrigger(compactionPending, false)) return false;
        if (!MemoryReflectionPolicy.isUseful(payload.messageCount, payload.userCharacters)) {
            if (payload.endMessageId.isEmpty()) conversation.acknowledgePendingCompactionReflection(sessionId);
            else conversation.appendReflectionCheckpoint(sessionId, payload, "skipped", "trivial transcript");
            return skippedUi(preferences, sessionId, app.getString(R.string.reflection_status_no_changes));
        }
        if (!preferences.failureBackoffElapsed(sessionId, System.currentTimeMillis())) {
            return skippedUi(preferences, sessionId, app.getString(R.string.reflection_failed_backoff));
        }
        return true;
    }

    public static Outcome run(Context context, String sessionId, String trigger, boolean manual,
                              CancellationToken token) {
        return run(context, sessionId, trigger, manual, null, token);
    }

    public static Outcome run(Context context, String sessionId, String trigger, boolean manual,
                              String throughMessageId, CancellationToken token) {
        Context app = context.getApplicationContext();
        MemoryReflectionPreferences preferences = new MemoryReflectionPreferences(app);
        LocalRunStore conversation = new LocalRunStore(app);
        MemoryStore memory = new MemoryStore(app);
        token.throwIfCancelled();
        if (!memory.isEnabled()) {
            if (!manual) conversation.advanceReflectionCheckpoint(sessionId, "memory disabled", throughMessageId);
            return skipped(preferences, sessionId, app.getString(R.string.reflection_skip_memory));
        }
        boolean chatWithoutMemory = app.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
                .getBoolean(MemoryUiLogic.INSTANCE.sessionMemoryDisabledKey(sessionId), false);
        if (chatWithoutMemory) {
            if (!manual) conversation.advanceReflectionCheckpoint(sessionId, "chat without memory", throughMessageId);
            return skipped(preferences, sessionId, app.getString(R.string.reflection_skip_chat));
        }
        if (!preferences.disclosureShown()) {
            preferences.setStatus(sessionId, app.getString(R.string.reflection_privacy_required));
            return new Outcome(false, false, false, preferences.localizedStatus(preferences.status(sessionId)), "", "", java.util.Collections.emptyList(), false);
        }
        String actualTrigger = manual ? TRIGGER_MANUAL : "compaction-event";
        if (!manual && !preferences.enabled()) {
            conversation.advanceReflectionCheckpoint(sessionId, "automatic reflection disabled", throughMessageId);
            return skipped(preferences, sessionId, app.getString(R.string.reflection_auto_off));
        }
        if (!MemoryReflectionPolicy.canReflect(memory.isEnabled(), !chatWithoutMemory,
                preferences.enabled(), preferences.disclosureShown(), manual)) {
            return skipped(preferences, sessionId, app.getString(R.string.reflection_privacy_required));
        }

        // Evaluate after persisted transcript state and consume the compaction trigger only in a successful checkpoint.
        // This follows post-turn-reflection.ts:33-77 without Letta's host-specific reminder tracker.
        LocalRunStore.ReflectionPayload payload = conversation.buildReflectionPayload(sessionId, throughMessageId, manual);
        boolean compactionPending = conversation.hasPendingCompactionReflection(sessionId)
                && !payload.compactionIds.isEmpty();
        boolean triggerMatches = MemoryReflectionPolicy.shouldTrigger(compactionPending, manual);
        if (!triggerMatches) return new Outcome(false, false, false, preferences.localizedStatus(preferences.status(sessionId)), "", "", java.util.Collections.emptyList(), false);
        if (!MemoryReflectionPolicy.isUseful(payload.messageCount, payload.userCharacters)) {
            if (payload.endMessageId.isEmpty()) conversation.acknowledgePendingCompactionReflection(sessionId);
            else conversation.appendReflectionCheckpoint(sessionId, payload, "skipped", "trivial transcript");
            preferences.setStatus(sessionId, app.getString(R.string.reflection_status_no_changes));
            return new Outcome(false, false, false, preferences.localizedStatus(preferences.status(sessionId)), "", "", java.util.Collections.emptyList(), false);
        }

        long now = System.currentTimeMillis();
        if (!preferences.failureBackoffElapsed(sessionId, now)) {
            return skipped(preferences, sessionId, app.getString(R.string.reflection_failed_backoff));
        }
        preferences.markStarted(sessionId);

        token.throwIfCancelled();
        String reflectionId = UUID.randomUUID().toString();
        try {
            MemoryReflectionWorker worker = new MemoryReflectionWorker(app, sessionId, reflectionId,
                    new CoreAgentModel(app, sessionId + "-reflection"));
            MemoryReflectionWorker.Result result = worker.run(payload.text, token);
            token.throwIfCancelled();
            List<Long> revisionIds = ids(result.revisions);
            boolean changed = !revisionIds.isEmpty();
            String summary = changed ? result.summary : app.getString(R.string.reflection_status_no_changes);
            String status = result.partial
                    ? app.getString(R.string.reflection_status_partial)
                    : summary;
            conversation.appendReflectionCommit(sessionId, payload, reflectionId, summary,
                    revisionIds, result.partial ? "partial" : changed ? "completed" : "no_changes", actualTrigger);
            memory.finishReflectionGroup(reflectionId, result.partial ? "partial" : "completed");
            preferences.succeeded(sessionId, System.currentTimeMillis(), status);
            return new Outcome(true, changed, result.partial, status, reflectionId, summary, revisionIds, !result.partial);
        } catch (MemoryReflectionWorker.Failure failure) {
            List<Long> revisionIds = ids(failure.revisions);
            boolean partial = !revisionIds.isEmpty();
            String summary = app.getString(R.string.reflection_status_partial);
            if (partial) conversation.appendReflectionEvent(sessionId, reflectionId, summary, revisionIds, "partial");
            token.throwIfCancelled();
            String status = partial ? summary : app.getString(R.string.reflection_failed_backoff);
            preferences.failed(sessionId, System.currentTimeMillis(), status);
            return new Outcome(true, partial, partial, status, reflectionId, summary, revisionIds, false);
        } catch (RuntimeException failure) {
            if (token.isCancelled()) {
                List<MemoryStore.Revision> cancelledRevisions = memory.reflectionGroupRevisions(reflectionId);
                List<Long> cancelledIds = ids(cancelledRevisions);
                boolean cancelledPartial = !cancelledIds.isEmpty();
                String cancelledSummary = app.getString(R.string.reflection_status_partial);
                try { memory.finishReflectionGroup(reflectionId, cancelledPartial ? "partial" : "rolled_back"); }
                catch (RuntimeException ignored) { }
                finally { memory.releaseReflectionGroup(reflectionId); }
                if (cancelledPartial && !conversation.hasReflectionCommit(sessionId, reflectionId)) {
                    runCatchingAppendPartial(conversation, sessionId, reflectionId, cancelledSummary, cancelledIds);
                }
                throw failure;
            }
            List<MemoryStore.Revision> revisions = memory.reflectionGroupRevisions(reflectionId);
            List<Long> revisionIds = ids(revisions);
            boolean partial = !revisionIds.isEmpty();
            String summary = partial ? app.getString(R.string.reflection_status_partial)
                    : app.getString(R.string.reflection_failed_backoff);
            try { memory.finishReflectionGroup(reflectionId, partial ? "partial" : "rolled_back"); }
            catch (RuntimeException ignored) { }
            finally { memory.releaseReflectionGroup(reflectionId); }
            if (partial && !conversation.hasReflectionCommit(sessionId, reflectionId)) {
                runCatchingAppendPartial(conversation, sessionId, reflectionId, summary, revisionIds);
            }
            String status = app.getString(R.string.reflection_failed_backoff);
            preferences.failed(sessionId, System.currentTimeMillis(), status);
            return new Outcome(true, partial, partial, status, reflectionId, summary, revisionIds, false);
        }
    }

    private static void runCatchingFinishPartial(MemoryStore store, String reflectionId) {
        try { store.finishReflectionGroup(reflectionId, "partial"); } catch (RuntimeException ignored) { }
    }

    private static void runCatchingAppendPartial(LocalRunStore store, String sessionId, String reflectionId,
                                                String summary, List<Long> revisionIds) {
        try { store.appendReflectionEvent(sessionId, reflectionId, summary, revisionIds, "partial"); }
        catch (RuntimeException ignored) { }
    }

    private static Outcome skipped(MemoryReflectionPreferences preferences, String sessionId, String status) {
        preferences.setStatus(sessionId, status);
        return new Outcome(false, false, false, status, "", "", java.util.Collections.emptyList(), false);
    }

    private static boolean skippedUi(MemoryReflectionPreferences preferences, String sessionId, String status) {
        preferences.setStatus(sessionId, status);
        AgentRunUiState.reflectionCancelled(sessionId, status);
        return false;
    }

    private static List<Long> ids(List<MemoryStore.Revision> revisions) {
        List<Long> result = new ArrayList<>();
        for (MemoryStore.Revision revision : revisions) result.add(revision.id);
        return result;
    }
}
