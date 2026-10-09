package com.jarvys.agent.crew;

import com.jarvys.agent.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Thread-safe conversation append log plus destructive per-recipient mailboxes.
 */
public final class CrewMessageBus {
    private final String conversationId;
    private final Object monitor = new Object();
    private final List<CrewMessage> log = new ArrayList<>();
    private final Map<String, List<CrewMessage>> mailboxes = new LinkedHashMap<>();

    public CrewMessageBus(String conversationId) {
        this.conversationId = conversationId;
    }

    public CrewMessage send(String from, String to, CrewMessage.Type type, String text, List<String> refs) {
        CrewMessage message = new CrewMessage(conversationId, from, to, type, text, refs, System.currentTimeMillis());
        synchronized (monitor) {
            log.add(message);
            mailboxes.computeIfAbsent(to, (ignored)->new ArrayList<>()).add(message);
            monitor.notifyAll();
        }
        return message;
    }

    /**
     * Record presentation-only activity without creating pending captain work.
     */
    public CrewMessage record(String from, String to, CrewMessage.Type type, String text, List<String> refs) {
        CrewMessage message = new CrewMessage(conversationId, from, to, type, text, refs, System.currentTimeMillis());
        synchronized (monitor) {
            log.add(message);
        }
        return message;
    }

    public CrewMessage recordActivity(String from, com.jarvys.agent.ToolActivity activity) {
        synchronized(monitor) {
            for(CrewMessage existing:log) if(existing.from.equals(from) && existing.activity!=null
                    && existing.activity.eventId.equals(activity.eventId)) return existing;
            CrewMessage message=new CrewMessage(activity.eventId,conversationId,from,from,CrewMessage.Type.STATUS,
                    activity.displayName,Collections.emptyList(),activity.timestampMillis,activity);
            log.add(message); return message;
        }
    }

    public List<CrewMessage> pending(String recipient) {
        synchronized (monitor) {
            return Collections.unmodifiableList(new ArrayList<>(mailboxes.getOrDefault(recipient, Collections.emptyList())));
        }
    }

    public void restore(List<CrewMessage> messages, List<CrewMessage> pending) {
        synchronized (monitor) {
            java.util.Set<String> known = new java.util.HashSet<>();
            for (CrewMessage message : log) known.add(message.id);
            for (CrewMessage message : messages) {
                if (!conversationId.equals(message.conversationId)) throw new IllegalArgumentException("Wrong Crew message scope");
                if (known.add(message.id)) log.add(message);
            }
            for (CrewMessage message : pending) {
                if (!conversationId.equals(message.conversationId)) throw new IllegalArgumentException("Wrong Crew inbox scope");
                List<CrewMessage> queue = mailboxes.computeIfAbsent(message.to, (ignored)->new ArrayList<>());
                boolean queued = false;
                for (CrewMessage existing : queue) if (existing.id.equals(message.id)) {
                    queued = true;
                    break;
                }
                if (!queued) queue.add(message);
            }
        }
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
            queue.removeIf((message)->{
                if (predicate.test(message)) {
                    matches.add(message);
                    return true;
                }
                return false;
            });
            if (queue.isEmpty()) mailboxes.remove(recipient);
            return Collections.unmodifiableList(matches);
        }
    }

    public List<CrewMessage> snapshot() {
        synchronized (monitor) {
            return Collections.unmodifiableList(new ArrayList<>(log));
        }
    }

    public boolean hasMessages(String recipient) {
        synchronized (monitor) {
            return !mailboxes.getOrDefault(recipient, Collections.emptyList()).isEmpty();
        }
    }

    public boolean hasMatching(String recipient, Predicate<CrewMessage> predicate) {
        synchronized (monitor) {
            return hasMatchingLocked(recipient, predicate);
        }
    }

    public void await(String recipient, BooleanSupplier completed, CancellationToken token) {
        await(recipient, (ignored)->true, completed, token);
    }

    public void await(String recipient, Predicate<CrewMessage> predicate, BooleanSupplier completed, CancellationToken token) {
        Runnable wake = ()->{
            synchronized (monitor) {
                monitor.notifyAll();
            }
        };
        Runnable unregister = token.registerCancelAction(wake);
        try {
            while (!token.isCancelled()) {
                synchronized (monitor) {
                    if (hasMatchingLocked(recipient, predicate) || completed.getAsBoolean()) break;
                    try {
                        monitor.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        token.throwIfCancelled();
                    }
                }
            }
            token.throwIfCancelled();
        } finally {
            unregister.run();
        }
    }

    public void signalWaiters() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private boolean hasMessagesLocked(String recipient) {
        List<CrewMessage> queue = mailboxes.get(recipient);
        return queue != null && !queue.isEmpty();
    }

    private boolean hasMatchingLocked(String recipient, Predicate<CrewMessage> predicate) {
        List<CrewMessage> queue = mailboxes.get(recipient);
        return queue != null && queue.stream().anyMatch(predicate);
    }
}
