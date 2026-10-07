package com.jarvys.agent.crew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable, in-memory Crew bus message. */
public final class CrewMessage {
    public enum Type { TASK, FINDING, CRITIQUE, QUESTION, ANSWER, RESULT, STATUS, USER }
    public final String id;
    public final String conversationId;
    public final String from;
    public final String to;
    public final Type type;
    public final String text;
    public final List<String> refs;
    public final long timestampMillis;

    public CrewMessage(String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis) {
        this(UUID.randomUUID().toString(), conversationId, from, to, type, text, refs, timestampMillis);
    }
    public CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis) {
        this.id = require(id, "id");
        this.conversationId = require(conversationId, "conversationId");
        this.from = require(from, "from");
        this.to = require(to, "to");
        this.type = java.util.Objects.requireNonNull(type, "type");
        this.text = text == null ? "" : text;
        this.refs = Collections.unmodifiableList(new ArrayList<>(refs == null ? Collections.emptyList() : refs));
        this.timestampMillis = timestampMillis;
    }
    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
