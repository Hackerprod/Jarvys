package com.jarvys.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ChatAttachment {
    private static final String ID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final int MAX_NAME_UTF8_BYTES = 218;
    public final String id;
    public final Kind kind;
    public final String mimeType;
    public final String name;
    public final String relativePath;
    public final long sizeBytes;

    public enum Kind {
        /*public static final*/ IMAGE /* = new Kind() */ /*enum*/ ,
        /*public static final*/ FILE /* = new Kind() */ /*enum*/ ;
    }

    public ChatAttachment(String id, String name, String mimeType, long sizeBytes, Kind kind, String relativePath) {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid attachment id");
        }
        if (name == null || !name.equals(sanitizeName(name))) {
            throw new IllegalArgumentException("Invalid attachment name");
        }
        if (sizeBytes < 0 || kind == null) {
            throw new IllegalArgumentException("Invalid attachment metadata");
        }
        if (!Objects.equals(id + "-" + name, relativePath)) {
            throw new IllegalArgumentException("Invalid attachment path");
        }
        this.id = id;
        this.name = name;
        this.mimeType = normalizeMimeType(mimeType);
        this.sizeBytes = sizeBytes;
        this.kind = kind;
        this.relativePath = relativePath;
    }

    public boolean isImage() {
        return this.kind == Kind.IMAGE;
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatAttachment)) {
            return false;
        }
        ChatAttachment value = (ChatAttachment)other;
        return this.sizeBytes == value.sizeBytes && this.id.equals(value.id) && this.name.equals(value.name) && this.mimeType.equals(value.mimeType) && this.kind == value.kind && this.relativePath.equals(value.relativePath);
    }

    public int hashCode() {
        return Objects.hash(this.id, this.name, this.mimeType, Long.valueOf(this.sizeBytes), this.kind, this.relativePath);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", this.id).put("name", this.name).put("mimeType", this.mimeType).put("sizeBytes", this.sizeBytes).put("kind", this.kind.name()).put("relativePath", this.relativePath);
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode attachment metadata", error);
        }
    }

    public static ChatAttachment fromJson(JSONObject value) {
        if (value == null) {
            return null;
        }
        try {
            return new ChatAttachment(value.getString("id"), value.getString("name"), value.getString("mimeType"), value.getLong("sizeBytes"), Kind.valueOf(value.getString("kind")), value.getString("relativePath"));
        } catch (Exception e) {
            return null;
        }
    }

    public static List<ChatAttachment> fromJsonArray(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<ChatAttachment> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            ChatAttachment attachment = fromJson(array.optJSONObject(index));
            if (attachment != null) {
                result.add(attachment);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static JSONArray toJsonArray(List<ChatAttachment> attachments) {
        JSONArray result = new JSONArray();
        if (attachments != null) {
            for (ChatAttachment attachment : attachments) {
                if (attachment == null) {
                    throw new IllegalArgumentException("Attachment must not be null");
                }
                result.put(attachment.toJson());
            }
        }
        return result;
    }

    public static String sanitizeName(String raw) {
        String value = raw == null ? "" : raw;
        StringBuilder clean = new StringBuilder();
        int bytes = 0;
        int offset = 0;
        while (offset < value.length()) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == 47 || cp == 92 || Character.isISOControl(cp) || Character.getType(cp) == 16 || (cp >= 55296 && cp <= 57343)) {
                cp = 95;
            }
            String part = new String(Character.toChars(cp));
            int width = part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > MAX_NAME_UTF8_BYTES) {
                break;
            }
            clean.append(part);
            bytes += width;
        }
        String name = clean.toString().trim();
        return (name.isEmpty() || name.equals(".") || name.equals("..")) ? "attachment" : name;
    }

    static String normalizeMimeType(String raw) {
        String mime = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") ? mime : "application/octet-stream";
    }
}
