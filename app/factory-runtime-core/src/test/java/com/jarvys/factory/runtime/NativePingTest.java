package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Every process and stream is fake. These tests never execute a utility or send a packet. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NativePingTest {
    interface Checked { void run() throws Exception; }
    static final NativeOperationAuthorization.Access ALLOWED = () -> { };
    static final String REPLY = "64 bytes from 192.0.2.1: icmp_seq=1 ttl=55 time=12.3 ms\n";
    static final String SUMMARY = "4 packets transmitted, 3 received, 25% packet loss, time 3000ms\nrtt min/avg/max/mdev = 10.100/12.300/14.500/1.200 ms\n";
    static JSONObject args() throws Exception { return new JSONObject().put("host", "example.com"); }
    static void error(String code, Checked operation) throws Exception {
        try { operation.run(); fail("Expected " + code); }
        catch (FactoryException failure) { assertEquals(code, failure.code); }
    }
    static final class FakeProcess extends Process {
        final ByteArrayInputStream input;
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        int exitCode, destroyed, forced;
        Runnable onRead = () -> { };
        boolean running;
        FakeProcess(String output, int exitCode) {
            this.exitCode = exitCode;
            input = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)) {
                @Override public synchronized int read(byte[] value, int offset, int length) { onRead.run(); return super.read(value, offset, length); }
            };
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public int waitFor() { return exitCode; }
        @Override public int exitValue() { if (running) throw new IllegalThreadStateException(); return exitCode; }
        @Override public void destroy() { destroyed++; running = false; }
        @Override public Process destroyForcibly() { forced++; running = false; return this; }
    }
    static final class FakeProcesses implements NativePing.Processes {
        final Set<String> available = new HashSet<>(Arrays.asList(NativePing.PING));
        final List<List<String>> commands = new ArrayList<>();
        final FakeProcess process;
        int availabilityChecks;
        Runnable onAvailable = () -> { }, onStart = () -> { };
        IOException startFailure;
        boolean securityDenied;
        FakeProcesses(String output, int code) { process = new FakeProcess(output, code); }
        @Override public boolean available(String executable) { availabilityChecks++; onAvailable.run(); return available.contains(executable); }
        @Override public Process start(List<String> argv) throws IOException {
            commands.add(new ArrayList<>(argv)); onStart.run();
            if (securityDenied) throw new SecurityException();
            if (startFailure != null) throw startFailure;
            return process;
        }
    }
    static JSONObject execute(FakeProcesses processes, JSONObject args) throws Exception {
        return new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args), ALLOWED, new NativePing.Cancellation());
    }
    @Test public void exactArgvUsesFixedExecutableAndNoShellOrToyboxUnsupportedNumericFlag() throws Exception {
        FakeProcesses processes = new FakeProcesses(REPLY + SUMMARY, 0);
        JSONObject result = execute(processes, args().put("packetTimeoutMs", 1501).put("intervalMs", 200));
        assertEquals(Arrays.asList("/system/bin/ping", "-c", "4", "-W", "2", "-w", "10", "-i", "0.200", "-s", "56", "example.com"), processes.commands.get(0));
        assertFalse(processes.commands.get(0).contains("-n")); assertEquals(0, processes.process.stdin.size());
        assertEquals(2000, result.getInt("packetWaitMs")); assertEquals("icmp", result.getString("protocol"));
    }
    @Test public void parsesActualIputilsLossAndRttWithoutEquatingElapsedTimeToLatency() throws Exception {
        FakeProcesses processes = new FakeProcesses(REPLY + SUMMARY, 0);
        JSONObject result = execute(processes, args());
        assertEquals("ok", result.getString("status")); assertTrue(result.getBoolean("reachable"));
        assertTrue(result.getBoolean("summaryParsed")); assertEquals(4, result.getInt("transmitted")); assertEquals(3, result.getInt("received"));
        assertEquals(25d, result.getDouble("packetLossPercent"), 0d); assertEquals(12.3, result.getJSONObject("latencyMs").getDouble("avg"), 0d);
        assertEquals(1.2, result.getJSONObject("latencyMs").getDouble("deviation"), 0d);
        assertEquals("utility_summary", result.getJSONObject("latencyMs").getString("source"));
        assertTrue(processes.process.destroyed > 0);
    }
    @Test public void parsesToyboxAndBusyboxStyleSummaries() throws Exception {
        JSONObject toybox = execute(new FakeProcesses("3 packets transmitted, 3 received, 0% packet loss\nround-trip min/avg/max = 1/2/3 ms\n", 0), args());
        assertEquals(2d, toybox.getJSONObject("latencyMs").getDouble("avg"), 0d);
        JSONObject busybox = execute(new FakeProcesses("4 packets transmitted, 4 packets received, 0% packet loss\nround-trip min/avg/max = 0.1/0.2/0.3 ms\n", 0), args());
        assertEquals(4, busybox.getInt("received"));
    }
    @Test public void noReplyReportsActualLossWithoutFabricatedLatency() throws Exception {
        JSONObject result = execute(new FakeProcesses("4 packets transmitted, 0 received, 100% packet loss, time 3000ms\n", 1), args());
        assertEquals("no_reply", result.getString("status")); assertFalse(result.getBoolean("reachable"));
        assertEquals(100d, result.getDouble("packetLossPercent"), 0d); assertFalse(result.has("latencyMs"));
    }
    @Test public void unrecognizedOutputAndExitZeroNeverFabricateSuccessOrMeasurements() throws Exception {
        JSONObject result = execute(new FakeProcesses("vendor diagnostic succeeded\n", 0), args());
        assertEquals("unparsed", result.getString("status")); assertFalse(result.getBoolean("summaryParsed"));
        for (String field : new String[]{"reachable", "latencyMs", "packetLossPercent", "transmitted", "received"}) assertFalse(result.has(field));
    }
    @Test public void recognizedRepliesWithoutSummaryAreExplicitlyPartial() throws Exception {
        JSONObject result = execute(new FakeProcesses(REPLY, 1), args());
        assertEquals("partial", result.getString("status")); assertTrue(result.getBoolean("reachable"));
        assertEquals(1, result.getInt("replySamplesParsed")); assertFalse(result.has("packetLossPercent")); assertFalse(result.has("latencyMs"));
    }
    @Test public void textTruncationDoesNotLoseLaterRealStatistics() throws Exception {
        StringBuilder output = new StringBuilder(); for (int i = 0; i < 400; i++) output.append("bounded ordinary output line\n"); output.append(SUMMARY);
        JSONObject result = execute(new FakeProcesses(output.toString(), 0), args().put("maxOutputBytes", 1024));
        assertTrue(result.getBoolean("outputTruncated")); assertEquals(1024, result.getString("output").length());
        assertEquals(output.length(), result.getLong("outputBytes")); assertTrue(result.getBoolean("summaryParsed")); assertEquals(25d, result.getDouble("packetLossPercent"), 0d);
    }
    @Test public void oversizedLinesAreSkippedWithBoundedMemoryAndLaterSummaryStillParses() throws Exception {
        JSONObject result = execute(new FakeProcesses(NetworkRequestTest.repeat('x', 9000) + "\n" + SUMMARY, 0), args().put("maxOutputBytes", 1024));
        assertEquals(1, result.getInt("oversizedLinesSkipped")); assertTrue(result.getBoolean("summaryParsed"));
    }
    @Test public void malformedContradictoryStatisticsDoNotBecomeMeasurements() throws Exception {
        for (String summary : new String[]{"4 packets transmitted, 9 received, 0% packet loss", "9999 packets transmitted, 1 received, 99% packet loss", "4 packets transmitted, 0 received, 0% packet loss"}) {
            JSONObject result = execute(new FakeProcesses(summary + "\nrtt min/avg/max = 9/1/2 ms\n", 0), args());
            assertFalse(result.getBoolean("summaryParsed")); assertFalse(result.has("packetLossPercent")); assertFalse(result.has("latencyMs"));
        }
    }
    @Test public void ipv6UsesSystemPing6OrExplicitSystemPingFamilyFlag() throws Exception {
        FakeProcesses separate = new FakeProcesses(SUMMARY, 0); separate.available.add(NativePing.PING6);
        execute(separate, args().put("host", "2001:db8::1"));
        assertEquals(NativePing.PING6, separate.commands.get(0).get(0)); assertFalse(separate.commands.get(0).contains("-6"));
        FakeProcesses combined = new FakeProcesses(SUMMARY, 0); execute(combined, args().put("family", "ipv6"));
        assertEquals(NativePing.PING, combined.commands.get(0).get(0)); assertEquals("-6", combined.commands.get(0).get(1));
        FakeProcesses v4 = new FakeProcesses(SUMMARY, 0); execute(v4, args().put("family", "ipv4")); assertEquals("-4", v4.commands.get(0).get(1));
    }
    @Test public void missingUtilityIsUnsupportedWithoutProcessOrHttpFallback() throws Exception {
        FakeProcesses processes = new FakeProcesses("", 0); processes.available.clear();
        JSONObject result = execute(processes, args()); assertEquals("unsupported", result.getString("status"));
        assertEquals(0, processes.commands.size()); assertFalse(result.has("reachable")); assertEquals("icmp", result.getString("protocol"));
    }
    @Test public void actualPlatformDenialDnsAndUnsupportedFlagsAreDistinct() throws Exception {
        assertEquals("platform_denied", execute(new FakeProcesses("ping: socket: Operation not permitted\n", 2), args()).getString("status"));
        assertEquals("dns_error", execute(new FakeProcesses("ping: unknown host example.com\n", 2), args()).getString("status"));
        assertEquals("unsupported", execute(new FakeProcesses("ping: unknown option -4\n", 2), args().put("family", "ipv4")).getString("status"));
        assertEquals("unsupported", execute(new FakeProcesses("ping: -s 65507 out of range\n", 2), args().put("packetBytes", 65507)).getString("status"));
        FakeProcesses denied = new FakeProcesses("", 0); denied.securityDenied = true;
        assertEquals("platform_denied", execute(denied, args()).getString("status"));
    }
    @Test public void startupErrorsAreSafeAndNeverReturnRawNativeException() throws Exception {
        FakeProcesses denied = new FakeProcesses("", 0); denied.startFailure = new IOException("error=13, Permission denied private-native-details");
        JSONObject result = execute(denied, args()); assertEquals("platform_denied", result.getString("status")); assertFalse(result.toString().contains("private-native-details"));
        FakeProcesses absent = new FakeProcesses("", 0); absent.startFailure = new IOException("error=2, No such file or directory");
        assertEquals("unsupported", execute(absent, args()).getString("status"));
    }
    @Test public void authorizationDenialOccursBeforeAnyProcessAvailabilityOrStart() throws Exception {
        FakeProcesses processes = new FakeProcesses(SUMMARY, 0);
        try { new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args()), () -> { throw new SecurityException("revoked"); }, new NativePing.Cancellation()); fail(); }
        catch (SecurityException expected) { assertEquals("revoked", expected.getMessage()); }
        assertEquals(0, processes.availabilityChecks); assertTrue(processes.commands.isEmpty());
    }
    @Test public void authorizationRecheckedImmediatelyBeforeStartingProcess() throws Exception {
        AtomicInteger state = new AtomicInteger(); FakeProcesses processes = new FakeProcesses(SUMMARY, 0); processes.onAvailable = () -> state.set(1);
        try { new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args()), () -> { if (state.get() == 1) throw new SecurityException(); }, new NativePing.Cancellation()); fail(); }
        catch (SecurityException expected) { }
        assertTrue(processes.commands.isEmpty());
    }
    @Test public void authorizationLossAfterStartAndDuringReadDestroysAndSuppressesResult() throws Exception {
        for (boolean atStart : new boolean[]{true, false}) {
            AtomicInteger state = new AtomicInteger(); FakeProcesses processes = new FakeProcesses(SUMMARY, 0);
            if (atStart) processes.onStart = () -> state.set(1); else processes.process.onRead = () -> state.set(1);
            try { new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args()), () -> { if (state.get() == 1) throw new SecurityException(); }, new NativePing.Cancellation()); fail(); }
            catch (SecurityException expected) { }
            assertTrue(processes.process.destroyed > 0); assertTrue(processes.process.forced > 0);
        }
    }
    @Test public void explicitCancellationAndDeadlineDestroyTheActiveProcess() throws Exception {
        NativePing.Cancellation cancellation = new NativePing.Cancellation(); FakeProcesses processes = new FakeProcesses(SUMMARY, 0);
        processes.process.onRead = cancellation::cancel;
        error("CANCELLED", () -> new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args()), ALLOWED, cancellation));
        assertTrue(processes.process.destroyed > 0);
        AtomicLong now = new AtomicLong(); FakeProcesses deadline = new FakeProcesses(SUMMARY, 0); deadline.process.onRead = () -> now.set(120000000001L);
        error("TIMEOUT", () -> new NativePing(deadline, now::get).execute(NetworkPingRequest.parse(args().put("timeoutMs", 120000)), ALLOWED, new NativePing.Cancellation()));
        assertTrue(deadline.process.destroyed > 0);
    }
    @Test public void cancellationBeforeStartIsSingleUse() throws Exception {
        NativePing.Cancellation cancellation = new NativePing.Cancellation(); cancellation.cancel(); FakeProcesses processes = new FakeProcesses(SUMMARY, 0);
        error("CANCELLED", () -> new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args()), ALLOWED, cancellation));
        error("INVALID_ARGUMENT", () -> new NativePing(processes, System::nanoTime).execute(NetworkPingRequest.parse(args()), ALLOWED, cancellation));
        assertTrue(processes.commands.isEmpty());
    }
    @Test @Config(sdk = 24, manifest = Config.NONE) public void android24CleanupDoesNotCallNewerProcessApi() throws Exception {
        FakeProcesses processes = new FakeProcesses(SUMMARY, 0); execute(processes, args());
        assertTrue(processes.process.destroyed > 0); assertEquals(0, processes.process.forced);
    }
}
