package com.jarvys.factory.runtime;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** A small JSON grammar guard: Android's JSONObject alone also accepts non-JSON input. */
final class StrictJson {
    private final String source;
    private int at;
    private int tokens;
    private final int maxDepth, maxTokens;
    private StrictJson(String source, int maxDepth, int maxTokens) { this.source = source; this.maxDepth = maxDepth; this.maxTokens = maxTokens; }

    static JSONObject object(String source, int maxBytes) throws FactoryException {
        return object(source, maxBytes, 8, 1024);
    }
    /** Explicit bounded transport envelope; original callers retain depth 8 / 1024 tokens. */
    static JSONObject object(String source, int maxBytes, int maxDepth, int maxTokens) throws FactoryException {
        if (maxDepth < 1 || maxDepth > 32 || maxTokens < 1 || maxTokens > 32768) throw new IllegalArgumentException("Invalid JSON envelope bounds");
        if (source == null || source.length() > maxBytes || source.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new FactoryException("TOO_LARGE", "JSON exceeds the size limit.");
        try {
            StrictJson parser = new StrictJson(source, maxDepth, maxTokens);
            parser.space();
            if (parser.at == source.length() || source.charAt(parser.at) != '{') throw new IllegalArgumentException();
            parser.value(0);
            parser.space();
            if (parser.at != source.length()) throw new IllegalArgumentException();
            return new JSONObject(source);
        } catch (JSONException | IllegalArgumentException e) {
            throw new FactoryException("INVALID_REQUEST", "Expected a bounded, valid JSON object.");
        }
    }

    private void space() {
        while (at < source.length() && " \r\n\t".indexOf(source.charAt(at)) >= 0) at++;
    }
    private char next() {
        if (at == source.length()) throw new IllegalArgumentException();
        return source.charAt(at++);
    }
    private void expect(char c) { if (next() != c) throw new IllegalArgumentException(); }
    private boolean consume(char c) {
        if (at < source.length() && source.charAt(at) == c) { at++; return true; }
        return false;
    }
    private void value(int depth) throws JSONException {
        if (depth > maxDepth || ++tokens > maxTokens) throw new IllegalArgumentException();
        space();
        if (at == source.length()) throw new IllegalArgumentException();
        char c = source.charAt(at);
        if (c == '{') {
            at++; space(); Set<String> keys = new HashSet<>();
            if (consume('}')) return;
            do {
                space(); String key = string();
                if (!keys.add(key)) throw new IllegalArgumentException();
                space(); expect(':'); value(depth + 1); space();
                if (consume('}')) return;
                expect(',');
            } while (true);
        } else if (c == '[') {
            at++; space(); if (consume(']')) return;
            do {
                value(depth + 1); space();
                if (consume(']')) return;
                expect(',');
            } while (true);
        } else if (c == '"') { string(); }
        else if (c == 't') { literal("true"); }
        else if (c == 'f') { literal("false"); }
        else if (c == 'n') { literal("null"); }
        else { number(); }
    }
    private String string() throws JSONException {
        int start = at; expect('"');
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c < 0x20) throw new IllegalArgumentException();
            if (c == '\\') {
                char esc = next();
                if (esc == 'u') {
                    for (int i = 0; i < 4; i++) {
                        char hex = next();
                        if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') ||
                                (hex >= 'A' && hex <= 'F'))) throw new IllegalArgumentException();
                    }
                } else if ("\"\\/bfnrt".indexOf(esc) < 0) throw new IllegalArgumentException();
            }
        }
        return new JSONObject("{\"key\":" + source.substring(start, at) + "}").getString("key");
    }
    private void literal(String word) {
        if (!source.startsWith(word, at)) throw new IllegalArgumentException();
        at += word.length();
    }
    private void number() {
        consume('-');
        if (!consume('0')) {
            if (at == source.length() || source.charAt(at) < '1' || source.charAt(at) > '9') throw new IllegalArgumentException();
            digits();
        }
        if (consume('.')) { int start = at; digits(); if (at == start) throw new IllegalArgumentException(); }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-');
            int start = at; digits(); if (at == start) throw new IllegalArgumentException();
        }
    }
    private void digits() {
        while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') at++;
    }
}
