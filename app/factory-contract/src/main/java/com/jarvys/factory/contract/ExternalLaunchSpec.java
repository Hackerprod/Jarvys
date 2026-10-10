package com.jarvys.factory.contract;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Closed typed external action values. No caller-supplied URI, component, flag or intent extra. */
public final class ExternalLaunchSpec {
    public static final int MAX_QUERY_CODE_POINTS = 256;
    public static final int MAX_QUERY_BYTES = 1024;
    public static final int MAX_PHONE_DIGITS = 15;
    public static final int MAX_EMAIL_ADDRESS = 254;
    public static final int MAX_SUBJECT_CODE_POINTS = 256;
    public static final int MAX_SUBJECT_BYTES = 1024;
    public static final int MAX_BODY_BYTES = 4096;
    public enum Kind { MAPS_COORDINATES, MAPS_QUERY, PHONE_DIAL, EMAIL_COMPOSE, SMS_COMPOSE }
    public final Kind kind;
    public final String uri, display, capability;
    public final String recipient, subject, body;

    private ExternalLaunchSpec(Kind kind, String uri, String display, String capability) {
        this(kind, uri, display, capability, null, null, null);
    }
    private ExternalLaunchSpec(Kind kind, String uri, String display, String capability, String recipient, String subject, String body) {
        this.kind = kind; this.uri = uri; this.display = display; this.capability = capability;
        this.recipient = recipient; this.subject = subject; this.body = body;
    }
    public static ExternalLaunchSpec coordinates(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude) || latitude < -90 || latitude > 90
                || Double.isNaN(longitude) || Double.isInfinite(longitude) || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Coordinates must be finite and in range");
        String coordinates = decimal(latitude) + "," + decimal(longitude);
        return new ExternalLaunchSpec(Kind.MAPS_COORDINATES, "geo:" + coordinates, coordinates, "maps");
    }
    private static String decimal(double value) {
        return value == 0 ? "0" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    public static ExternalLaunchSpec query(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_QUERY_CODE_POINTS * 2)
            throw new IllegalArgumentException("Invalid map query length");
        int count = 0; boolean nonblank = false;
        for (int i = 0; i < value.length();) {
            char first = value.charAt(i);
            if (Character.isLowSurrogate(first) || (Character.isHighSurrogate(first)
                    && (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))))
                throw new IllegalArgumentException("Map query must be valid Unicode");
            int point = value.codePointAt(i), type = Character.getType(point);
            if (++count > MAX_QUERY_CODE_POINTS || Character.isISOControl(point) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR)
                throw new IllegalArgumentException("Map query contains forbidden characters");
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) nonblank = true;
            i += Character.charCount(point);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (!nonblank || bytes.length > MAX_QUERY_BYTES) throw new IllegalArgumentException("Invalid map query");
        return new ExternalLaunchSpec(Kind.MAPS_QUERY, "geo:0,0?q=" + encode(bytes), value, "maps");
    }
    /** RFC 3986 unreserved bytes only; never decode caller text before escaping once. */
    private static String encode(byte[] bytes) {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int b = raw & 255;
            if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
                    || b == '-' || b == '.' || b == '_' || b == '~') encoded.append((char) b);
            else encoded.append('%').append(hex[b >>> 4]).append(hex[b & 15]);
        }
        return encoded.toString();
    }
    public static ExternalLaunchSpec dial(String number) {
        if (number == null || !number.matches("\\+?[0-9]{1,15}"))
            throw new IllegalArgumentException("Use an optional plus followed by 1 to 15 ASCII digits");
        return new ExternalLaunchSpec(Kind.PHONE_DIAL, "tel:" + number, number, "phone");
    }
    /** Conservative single-address subset, not a mailbox existence or universal syntax check. */
    public static ExternalLaunchSpec email(String to, String subject, String body) {
        if (to == null || to.length() > MAX_EMAIL_ADDRESS) throw new IllegalArgumentException("Invalid email address length");
        int at = to.indexOf('@');
        if (at < 1 || at > 64 || at != to.lastIndexOf('@')) throw new IllegalArgumentException("One email recipient required");
        String local = to.substring(0, at), domain = to.substring(at + 1);
        if (!local.matches("[A-Za-z0-9_+\\-]+(\\.[A-Za-z0-9_+\\-]+)*") || domain.length() > 253)
            throw new IllegalArgumentException("Unsupported email address");
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) throw new IllegalArgumentException("Email domain requires multiple labels");
        for (String label : labels) if (label.length() < 1 || label.length() > 63
                || !label.matches("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Unsupported email domain");
        editorText(subject, MAX_SUBJECT_CODE_POINTS, MAX_SUBJECT_BYTES, false);
        editorText(body, MAX_BODY_BYTES, MAX_BODY_BYTES, true);
        return new ExternalLaunchSpec(Kind.EMAIL_COMPOSE, "mailto:" + encode(local.getBytes(StandardCharsets.UTF_8)) + "@" + domain,
                to, "email", to, subject, body);
    }
    public static ExternalLaunchSpec sms(String number, String body) {
        dial(number); // Same conservative recipient grammar as v72; no inference or normalization.
        editorText(body, MAX_BODY_BYTES, MAX_BODY_BYTES, true);
        return new ExternalLaunchSpec(Kind.SMS_COMPOSE, "smsto:" + number, number, "sms", number, null, body);
    }
    private static void editorText(String value, int maxPoints, int maxBytes, boolean multiline) {
        if (value == null || value.length() > maxPoints * 2) throw new IllegalArgumentException("Invalid editor text length");
        int count = 0;
        for (int i = 0; i < value.length();) {
            char first = value.charAt(i);
            if (Character.isLowSurrogate(first) || (Character.isHighSurrogate(first)
                    && (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))))
                throw new IllegalArgumentException("Editor text must be valid Unicode");
            int point = value.codePointAt(i), type = Character.getType(point);
            boolean allowedControl = multiline && (point == '\n' || point == '\t');
            if (++count > maxPoints || (Character.isISOControl(point) && !allowedControl) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR)
                throw new IllegalArgumentException("Editor text contains forbidden characters");
            i += Character.charCount(point);
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IllegalArgumentException("Editor text exceeds byte limit");
    }

}
