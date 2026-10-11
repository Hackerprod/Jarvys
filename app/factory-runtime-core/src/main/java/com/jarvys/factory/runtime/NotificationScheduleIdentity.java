package com.jarvys.factory.runtime;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Domain-separated length-framed identity; alarm/action identity never depends on String.hashCode. */
final class NotificationScheduleIdentity {
    static String digest(String... fields) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String field : fields) { byte[] bytes = field.getBytes(StandardCharsets.UTF_8); sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); sha.update(bytes); }
            StringBuilder result = new StringBuilder(64);
            for (byte b : sha.digest()) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private NotificationScheduleIdentity() { }
}
