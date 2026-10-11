package com.jarvys.factory.runtime;

/** Small canonical RFC 4648 codec usable on API 24 without Android API 26+ Base64 APIs or JNI. */
final class BleBytes {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    static String encode(byte[] input) {
        char[] output = new char[((input.length + 2) / 3) * 4]; int at = 0;
        for (int i = 0; i < input.length; i += 3) {
            int a = input[i] & 255, b = i + 1 < input.length ? input[i + 1] & 255 : 0, c = i + 2 < input.length ? input[i + 2] & 255 : 0;
            output[at++] = ALPHABET[a >>> 2]; output[at++] = ALPHABET[(a & 3) << 4 | b >>> 4];
            output[at++] = i + 1 < input.length ? ALPHABET[(b & 15) << 2 | c >>> 6] : '=';
            output[at++] = i + 2 < input.length ? ALPHABET[c & 63] : '=';
        }
        return new String(output);
    }
    static byte[] decode(String s) {
        if (s.length() % 4 != 0 || !s.matches("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")) throw new IllegalArgumentException("Invalid base64");
        int padding = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
        byte[] out = new byte[s.length() / 4 * 3 - padding]; int at = 0;
        for (int i = 0; i < s.length(); i += 4) {
            int a = digit(s.charAt(i)), b = digit(s.charAt(i + 1)), c = digit(s.charAt(i + 2)), d = digit(s.charAt(i + 3));
            if (at < out.length) out[at++] = (byte)(a << 2 | b >>> 4);
            if (at < out.length) out[at++] = (byte)(b << 4 | c >>> 2);
            if (at < out.length) out[at++] = (byte)(c << 6 | d);
        }
        if (!encode(out).equals(s)) throw new IllegalArgumentException("Noncanonical base64");
        return out;
    }
    private static int digit(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A'; if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52; if (c == '+') return 62; if (c == '/') return 63; return 0;
    }
    private BleBytes() { }
}
