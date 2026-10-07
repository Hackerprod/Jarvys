package com.jarvys.agent.crew;

import com.jarvys.agent.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Thread-safe conversation append log plus destructive per-recipient mailboxes. */
public final class CrewMessageBus {
    private final String conversationId;
    private final Object monitor = new Object();
    private final List<CrewMessage> log = new ArrayList<>();
    private final Map<String, List<CrewMessage>> mailboxes = new LinkedHashMap<>();

    public CrewMessageBus(String conversationId) { this.conversationId = conversationId; }

    public CrewMessage send(String from, String to, CrewMessage.Type type, String text, List<String> refs) {
        CrewMessage message = new CrewMessage(conversationId, from, to, type, text, refs, System.currentTimeMillis());
        synchronized (monitor) {
            log.add(message);
            mailboxes.computeIfAbsent(to, ignored -> new ArrayList<>()).add(message);
            monitor.notifyAll();
        }
        return message;
    }

    public List<CrewMessage> drain(String recipient) {
        synchronized (monitor) {
            List<CrewMessage> queue = mailboxes.remove(recipient);
            return queue == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(queue));
        }
    }

    public List<CrewMessage> drainMatching(String recipient, Predicate<CrewMessage> predicate) {
        synchronized (monitor) {
            List<CrewMessage> queue = mailboxes.get(recipient);
            if (queue == null) return Collections.emptyList();
            List<CrewMessage> matches = new ArrayList<>();
            queue.removeIf(message -> { if (predicate.test(message)) { matches.add(message); return true; } return false; });
            if (queue.isEmpty()) mailboxes.remove(recipient);
            return Collections.unmodifiableList(matches);
        }
    }

    public List<CrewMessage> snapshot() {
        synchronized (monitor) { return Collections.unmodifiableList(new ArrayList<>(log)); }
    }

    public boolean hasMessages(String recipient) {
        synchronized (monitor) { return !mailboxes.getOrDefault(recipient, Collections.emptyList()).isEmpty(); }
    }

    public boolean hasMatching(String recipient, Predicate<CrewMessage> predicate) {
        synchronized (monitor) { return hasMatchingLocked(recipient, predicate); }
    }

    public void await(String recipient, BooleanSupplier completed, CancellationToken token) {
        await(recipient, ignored -> true, completed, token);
    }

    public void await(String recipient, Predicate<CrewMessage> predicate,
                      BooleanSupplier completed, CancellationToken token) {
        Runnable wake = () -> { synchronized (monitor) { monitor.notifyAll(); } };
        Runnable unregister = token.registerCancelAction(wake);
        try {
            while (!token.isCancelled()) {
                synchronized (monitor) {
                    if (hasMatchingLocked(recipient, predicate) || completed.getAsBoolean()) break;
                    try { monitor.wait(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); token.throwIfCancelled(); }
                }
            }
            token.throwIfCancelled();
        } finally { unregister.run(); }
    }

    public void signalWaiters() { synchronized (monitor) { monitor.notifyAll(); } }
    private boolean hasMessagesLocked(String recipient) {
        List<CrewMessage> queue = mailboxes.get(recipient);
        return queue != null && !queue.isEmpty();
    }
    private boolean hasMatchingLocked(String recipient, Predicate<CrewMessage> predicate) {
        List<CrewMessage> queue = mailboxes.get(recipient);
        return queue != null && queue.stream().anyMatch(predicate);
    }
}
