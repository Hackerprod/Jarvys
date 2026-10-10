package com.jarvys.factory.contract;

import java.nio.charset.StandardCharsets;

/** Minimal selected contact data. Validation preserves text and grants no action authority. */
public final class ContactPickSpec {
    public static final int MAX_VALUE_CODE_POINTS = 256;
    public static final int MAX_VALUE_BYTES = 1024;
    private ContactPickSpec() { }
    public static String kind(String kind) {
        if (!"phone".equals(kind) && !"email".equals(kind))
            throw new IllegalArgumentException("Select exactly phone or email");
        return kind;
    }
    /** No trimming, normalization, phone/email syntax check or contact identity claim. */
    public static String value(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_CODE_POINTS * 2)
            throw new IllegalArgumentException("Invalid contact value length");
        int count = 0; boolean nonblank = false;
        for (int i = 0; i < value.length();) {
            char first = value.charAt(i);
            if (Character.isLowSurrogate(first) || (Character.isHighSurrogate(first)
                    && (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))))
                throw new IllegalArgumentException("Contact value must be valid Unicode");
            int point = value.codePointAt(i), type = Character.getType(point);
            if (++count > MAX_VALUE_CODE_POINTS || Character.isISOControl(point) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR)
                throw new IllegalArgumentException("Contact value contains forbidden characters");
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) nonblank = true;
            i += Character.charCount(point);
        }
        if (!nonblank || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES)
            throw new IllegalArgumentException("Invalid contact value");
        return value;
    }
}
