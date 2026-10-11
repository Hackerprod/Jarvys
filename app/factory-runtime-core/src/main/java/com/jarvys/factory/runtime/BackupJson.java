package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Separate archive grammar; deliberately does not weaken the bridge's StrictJson token limit. */
final class BackupJson {
    static final int MAX_DEPTH = 8, MAX_TOKENS = 262144;
    private final String source;
    private int at, tokens;
    private BackupJson(String source) { this.source = source; }

    static JSONObject object(byte[] bytes) throws FactoryException {
        if (bytes == null || bytes.length == 0) throw DatabaseRequest.invalid();
        if (bytes.length > DatabaseBackupFormat.MAX_BYTES) throw tooLarge();
        try {
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            BackupJson parser = new BackupJson(source);
            Object value = parser.value(0); parser.space();
            if (!(value instanceof JSONObject) || parser.at != source.length()) throw DatabaseRequest.invalid();
            return (JSONObject) value;
        } catch (CharacterCodingException | JSONException | IllegalArgumentException e) { throw DatabaseRequest.invalid(); }
    }
    private void space() { while (at < source.length() && " \r\n\t".indexOf(source.charAt(at)) >= 0) at++; }
    private char next() { if (at >= source.length()) throw new IllegalArgumentException(); return source.charAt(at++); }
    private boolean consume(char c) { if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false; }
    private void expect(char c) { if (next() != c) throw new IllegalArgumentException(); }
    private void token() { if (++tokens > MAX_TOKENS) throw new IllegalArgumentException(); }
    private Object value(int depth) throws JSONException, FactoryException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException();
        token(); space(); if (at == source.length()) throw new IllegalArgumentException();
        char c = source.charAt(at);
        if (c == '{') {
            at++; space(); JSONObject object = new JSONObject(); Set<String> keys = new HashSet<>();
            if (consume('}')) return object;
            while (true) {
                space(); token(); String key = string();
                if (!keys.add(key)) throw new IllegalArgumentException();
                space(); expect(':'); object.put(key, value(depth + 1)); space();
                if (consume('}')) return object; expect(',');
            }
        }
        if (c == '[') {
            at++; space(); JSONArray array = new JSONArray(); if (consume(']')) return array;
            while (true) { array.put(value(depth + 1)); space(); if (consume(']')) return array; expect(','); }
        }
        if (c == '"') return string();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return JSONObject.NULL; }
        return number();
    }
    private String string() throws FactoryException {
        expect('"'); StringBuilder result = new StringBuilder();
        while (true) {
            char c = next(); if (c == '"') break;
            if (c < 0x20) throw new IllegalArgumentException();
            if (c == '\\') {
                c = next();
                switch (c) {
                    case '"': case '\\': case '/': break;
                    case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                    case 'u':
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = next(); int digit = hex >= '0' && hex <= '9' ? hex - '0' :
                                    hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw new IllegalArgumentException(); code = code * 16 + digit;
                        }
                        c = (char) code; break;
                    default: throw new IllegalArgumentException();
                }
            }
            result.append(c);
        }
        String value = result.toString(); unicode(value); return value;
    }
    private void literal(String word) { if (!source.startsWith(word, at)) throw new IllegalArgumentException(); at += word.length(); }
    private Number number() {
        int start = at; consume('-');
        if (!consume('0')) {
            if (at == source.length() || source.charAt(at) < '1' || source.charAt(at) > '9') throw new IllegalArgumentException();
            digits();
        }
        boolean real = false;
        if (consume('.')) { real = true; int before = at; digits(); if (before == at) throw new IllegalArgumentException(); }
        if (consume('e') || consume('E')) {
            real = true; if (!consume('+')) consume('-'); int before = at; digits(); if (before == at) throw new IllegalArgumentException();
        }
        String raw = source.substring(start, at);
        // Integer lexemes never pass through double: neither overflow nor rounded integers can be accepted.
        if (!real) return Long.valueOf(raw);
        double result = Double.parseDouble(raw);
        if (Double.isNaN(result) || Double.isInfinite(result)) throw new IllegalArgumentException();
        // Keep the lexeme until it has matched its round-trip encoding. This also rejects underflow and precision loss.
        if (!raw.equals(numberText(result))) throw new IllegalArgumentException();
        return result;
    }
    private void digits() { while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') at++; }

    static byte[] canonical(Object value) throws FactoryException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private int count;
            @Override public void write(int value) throws IOException {
                if (++count > DatabaseBackupFormat.MAX_BYTES) throw new Limit(); bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                if (length > DatabaseBackupFormat.MAX_BYTES - count) throw new Limit();
                count += length; bytes.write(value, offset, length);
            }
        };
        try { write(value, bounded, 0, new int[1]); return bytes.toByteArray(); }
        catch (Limit e) { throw tooLarge(); }
        catch (IOException | JSONException e) { throw DatabaseRequest.invalid(); }
    }
    private static final class Limit extends IOException { }
    private static void write(Object value, OutputStream out, int depth, int[] tokens) throws IOException, JSONException, FactoryException {
        if (depth > MAX_DEPTH || ++tokens[0] > MAX_TOKENS) throw DatabaseRequest.invalid();
        if (value == JSONObject.NULL) ascii(out, "null");
        else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; out.write('{'); boolean first = true;
            for (String key : keys(object)) {
                if (++tokens[0] > MAX_TOKENS) throw DatabaseRequest.invalid();
                if (!first) out.write(','); first = false; quote(out, key); out.write(':'); write(object.get(key), out, depth + 1, tokens);
            }
            out.write('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; out.write('[');
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.write(','); write(array.get(i), out, depth + 1, tokens); }
            out.write(']');
        } else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean) ascii(out, value.toString());
        else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            ascii(out, Long.toString(((Number) value).longValue()));
        else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) throw DatabaseRequest.invalid();
            ascii(out, numberText(number));
        } else throw DatabaseRequest.invalid();
    }
    private static String numberText(double value) { return Double.toString(value == 0.0 ? 0.0 : value); }
    static void unicode(String value) throws FactoryException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw DatabaseRequest.invalid(); }
            else if (Character.isLowSurrogate(c)) throw DatabaseRequest.invalid();
        }
    }
    private static void quote(OutputStream out, String value) throws IOException, FactoryException {
        unicode(value); out.write('"'); StringBuilder part = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') part.append('\\').append(c);
            else if (Character.isHighSurrogate(c) && Character.getType(Character.toCodePoint(c,value.charAt(i+1))) == Character.FORMAT) {
                escape(part,c); escape(part,value.charAt(++i));
            } else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == '\u2028' || c == '\u2029') {
                escape(part,c);
            } else part.append(c);
        }
        out.write(part.toString().getBytes(StandardCharsets.UTF_8)); out.write('"');
    }
    private static void escape(StringBuilder out,char value) {
        out.append("\\u"); String hex=Integer.toHexString(value);
        for(int pad=hex.length();pad<4;pad++) out.append('0'); out.append(hex);
    }
    private static void ascii(OutputStream out, String value) throws IOException { out.write(value.getBytes(StandardCharsets.US_ASCII)); }
    static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>(); for (Iterator<String> it = object.keys(); it.hasNext();) result.add(it.next()); Collections.sort(result); return result;
    }
    static Object copy(Object value) {
        try {
            if (value instanceof JSONObject) { JSONObject result = new JSONObject(); for (String key : keys((JSONObject) value)) result.put(key, copy(((JSONObject) value).get(key))); return result; }
            if (value instanceof JSONArray) { JSONArray result = new JSONArray(); for (int i = 0; i < ((JSONArray) value).length(); i++) result.put(copy(((JSONArray) value).get(i))); return result; }
            return value;
        } catch (JSONException impossible) { throw new IllegalStateException("Validated backup could not be copied."); }
    }
    static FactoryException tooLarge() { return new FactoryException("TOO_LARGE", "Database backup exceeds the 1 MiB limit."); }
}
