package com.jarvys.agent;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Best-effort asynchronous step summary; provider model runs off the main graph thread. */
public final class AsyncHistorySummarizer {
    private static final String TAG = "JarvysHistorySummary";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JarvysHistorySummarizer");
        thread.setDaemon(true);
        return thread;
    });

    public void dispatch(String runId, StepRecord step, LocalRunStore store,
                         AgentModel model, CancellationToken token) {
        dispatch(runId, step, store, model, runId, token);
    }

    public void dispatch(String runId, StepRecord step, LocalRunStore store,
                         AgentModel model, String sessionId, CancellationToken token) {
        EXECUTOR.execute(() -> {
            try {
                token.throwIfCancelled();
                String summary = model.summarizeStep(step, sessionId, token);
                step.summary = summary;
                store.recordSummary(runId, step.number, summary);
            } catch (RuntimeException failure) {
                if (!token.isCancelled()) Log.e(TAG, "Could not produce/persist step summary", failure);
            }
        });
    }
}
