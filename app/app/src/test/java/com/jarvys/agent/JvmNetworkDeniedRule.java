package com.jarvys.agent;

import java.security.Permission;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Test-only guard for standard JVM sockets/DNS and subprocess launch after the Gradle worker IPC handshake.
 * Not a sandbox for native code. Install as a ClassRule, before fixture setup.
 */
@SuppressWarnings("removal")
public final class JvmNetworkDeniedRule implements TestRule {
    private static final Object CLASS_LOCK = new Object();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger expectedAttempts = new AtomicInteger();

    public interface BlockedOperation { void run() throws Exception; }

    /** Only negative guard tests may consume an expected denial. No host or payload is logged. */
    public void assertBlocked(BlockedOperation operation) throws Exception {
        int before = attempts.get();
        try {
            operation.run();
            throw new AssertionError("Network operation was not blocked");
        } catch (SecurityException blocked) {
            if (attempts.get() != before + 1) throw new AssertionError("Guard did not reject the operation");
            expectedAttempts.incrementAndGet();
        }
    }

    @Override public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                synchronized (CLASS_LOCK) {
                    SecurityManager previous = System.getSecurityManager();
                    SecurityManager guard = new SecurityManager() {
                        private void deny() {
                            attempts.incrementAndGet();
                            throw new SecurityException("Test network disabled");
                        }
                        @Override public void checkConnect(String host, int port) { deny(); }
                        @Override public void checkConnect(String host, int port, Object context) { deny(); }
                        @Override public void checkAccept(String host, int port) { deny(); }
                        @Override public void checkListen(int port) { deny(); }
                        @Override public void checkExec(String command) {
                            // Conscrypt checks native-library executable permissions; this is not process launch.
                            StackWalker.StackFrame caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                                    .walk(frames -> frames.skip(1).findFirst().orElse(null));
                            if (caller != null && caller.getDeclaringClass() == java.io.File.class
                                    && "canExecute".equals(caller.getMethodName())) return;
                            deny();
                        }
                        @Override public void checkPermission(Permission permission) {
                            if (previous != null) previous.checkPermission(permission);
                        }
                        @Override public void checkPermission(Permission permission, Object context) {
                            if (previous != null) previous.checkPermission(permission, context);
                        }
                    };
                    attempts.set(0);
                    expectedAttempts.set(0);
                    System.setSecurityManager(guard); // Failure aborts the class, never silently falls back.
                    try {
                        if (System.getSecurityManager() != guard) throw new AssertionError("Network guard not installed");
                        base.evaluate();
                        if (System.getSecurityManager() != guard) throw new AssertionError("Network guard replaced");
                        if (attempts.get() != expectedAttempts.get())
                            throw new AssertionError("Unexpected network attempt in offline test");
                    } finally {
                        System.setSecurityManager(previous);
                    }
                }
            }
        };
    }
}
