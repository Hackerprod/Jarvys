package com.jarvys.agent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.ClassRule;
import org.junit.Test;

/** No network is needed to prove that Java rejects each operation before transport. */
public class JvmNetworkDeniedRuleTest {
    @ClassRule public static final JvmNetworkDeniedRule NETWORK = new JvmNetworkDeniedRule();

    @Test public void rejectsDnsBeforeResolution() throws Exception {
        NETWORK.assertBlocked(() -> InetAddress.getByName("offline-check.invalid"));
    }

    @Test public void rejectsSocketBeforeConnecting() throws Exception {
        NETWORK.assertBlocked(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 9));
            }
        });
    }

    @Test public void rejectsListeningBeforeBinding() throws Exception {
        NETWORK.assertBlocked(() -> { try (ServerSocket socket = new ServerSocket(0)) { } });
    }

    @SuppressWarnings("removal")
    @Test public void rejectsContextConnectAndAcceptChecks() throws Exception {
        NETWORK.assertBlocked(() -> System.getSecurityManager().checkConnect("offline-check.invalid", -1, null));
        NETWORK.assertBlocked(() -> System.getSecurityManager().checkAccept("127.0.0.1", 9));
    }

    @Test public void rejectsSubprocessBeforeLaunch() throws Exception {
        NETWORK.assertBlocked(() -> new ProcessBuilder("offline-guard-never-launch").start());
    }

    @Test public void permitsExecutablePermissionQueryWithoutLaunchingAnything() {
        new java.io.File("offline-guard-never-launch").canExecute();
    }
}
