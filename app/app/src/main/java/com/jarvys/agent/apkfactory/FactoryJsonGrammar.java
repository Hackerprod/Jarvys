package com.jarvys.agent.apkfactory;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** A small JSON grammar guard: Android's JSONObject alone also accepts non-JSON input. */
final class FactoryJsonGrammar {
    private final String source;
    private int at;
    private int tokens;
    private FactoryJsonGrammar(String source) { this.source = source; }

    static JSONObject parseObject(String source, int maxBytes) {
        if (source == null || source.length() > maxBytes || source.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("JSON exceeds the size limit.");
        try {
            FactoryJsonGrammar parser = new FactoryJsonGrammar(source);
            parser.space();
            if (parser.at == source.length() || source.charAt(parser.at) != '{') throw new IllegalArgumentException();
            parser.value(0);
            parser.space();
            if (parser.at != source.length()) throw new IllegalArgumentException();
            return new JSONObject(source);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Expected a bounded, strict JSON object.", e);
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
        if (depth > 8 || ++tokens > 8192) throw new IllegalArgumentException();
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
