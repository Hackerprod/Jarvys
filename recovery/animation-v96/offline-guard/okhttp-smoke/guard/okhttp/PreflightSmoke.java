package guard.okhttp;

import guard.bootstrap.OfflineGuard;

/** Application marker must never execute after failed required target verification. */
public final class PreflightSmoke {
    public static void main(String[] args) {
        OfflineGuard.verifyOkHttp(PreflightSmoke.class.getClassLoader());
        System.out.println("OKHTTP_PREFLIGHT_APPLICATION_REACHED");
    }
}
