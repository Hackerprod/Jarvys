package com.jarvys.factory.runtime;

import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual ICMP via the installed Android system utility. No shell, downloaded executable or HTTP fallback. */
public final class NativePing {
    static final String PING = "/system/bin/ping", PING6 = "/system/bin/ping6";
    public interface Processes {
        boolean available(String executable);
        Process start(List<String> argv) throws IOException;
    }
    public interface Clock { long nanoTime(); }
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final Processes processes;
    private final Clock clock;
    public NativePing() {
        this(new Processes() {
            @Override public boolean available(String path) { File file = new File(path); return file.isFile() && file.canExecute(); }
            @Override public Process start(List<String> argv) throws IOException {
                if (argv.isEmpty() || (!PING.equals(argv.get(0)) && !PING6.equals(argv.get(0)))) throw new SecurityException();
                ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
                builder.environment().put("LC_ALL", "C"); builder.environment().put("LANG", "C");
                return builder.start();
            }
        }, System::nanoTime);
    }
    public NativePing(Processes processes, Clock clock) {
        if (processes == null || clock == null) throw new IllegalArgumentException();
        this.processes = processes; this.clock = clock;
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor value = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "factory-ping-deadline"); thread.setDaemon(true); return thread;
        });
        value.setRemoveOnCancelPolicy(true); return value;
    }
    /** One operation. Host revocation must call cancel even when the process output read is blocked. */
    public static final class Cancellation implements AutoCloseable {
        private boolean started, complete;
        private String reason;
        private Process process;
        public void cancel() { cancel("CANCELLED"); }
        @Override public void close() { cancel(); }
        public synchronized boolean isCancelled() { return reason != null; }
        private void cancel(String why) {
            Process current;
            synchronized (this) { if (complete || reason != null) return; reason = why; current = process; }
            terminate(current);
        }
        private synchronized void begin() throws FactoryException {
            if (started) throw new FactoryException("INVALID_ARGUMENT", "Cancellation belongs to one ping operation.");
            started = true; check();
        }
        private void attach(Process value) throws FactoryException {
            synchronized (this) { if (reason == null) { process = value; return; } }
            terminate(value); check();
        }
        private synchronized void check() throws FactoryException {
            if (reason != null) throw new FactoryException(reason, "TIMEOUT".equals(reason) ? "ICMP deadline elapsed." : "ICMP operation cancelled.");
        }
        private synchronized void complete() throws FactoryException { check(); complete = true; process = null; }
        private synchronized void finish() { complete = true; process = null; }
    }
    public JSONObject execute(NetworkPingRequest request, NativeOperationAuthorization.Access authorization,
                              Cancellation cancellation) throws FactoryException {
        if (request == null || authorization == null || cancellation == null) throw new IllegalArgumentException();
        cancellation.begin();
        long started = clock.nanoTime(), deadline = started + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs);
        ScheduledFuture<?> timer = DEADLINES.schedule(() -> cancellation.cancel("TIMEOUT"), request.timeoutMs, TimeUnit.MILLISECONDS);
        Process process = null;
        try {
            check(authorization, cancellation, deadline);
            List<String> argv;
            try { argv = command(request); }
            catch (SecurityException denied) { return result(request, "platform_denied", null, null, started, authorization, cancellation, deadline); }
            if (argv == null) return result(request, "unsupported", null, null, started, authorization, cancellation, deadline);
            check(authorization, cancellation, deadline);
            try { process = processes.start(argv); }
            catch (SecurityException denied) { return result(request, "platform_denied", null, null, started, authorization, cancellation, deadline); }
            catch (IOException failure) { return result(request, startFailure(failure), null, null, started, authorization, cancellation, deadline); }
            if (process == null) return result(request, "failed", null, null, started, authorization, cancellation, deadline);
            cancellation.attach(process);
            check(authorization, cancellation, deadline);
            close(process.getOutputStream()); // Ping accepts no stdin from the app.
            Output output = new Output(request);
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    check(authorization, cancellation, deadline);
                    int count = stream.read(buffer);
                    check(authorization, cancellation, deadline);
                    if (count < 0) break;
                    if (count > 0) output.accept(buffer, count);
                }
            } catch (IOException failure) {
                check(authorization, cancellation, deadline);
                output.finish();
                return result(request, "failed", null, output, started, authorization, cancellation, deadline);
            }
            output.finish();
            Integer exitCode;
            while (true) {
                check(authorization, cancellation, deadline);
                try { exitCode = process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    try { Thread.sleep(10); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); cancellation.cancel(); cancellation.check(); }
                }
            }
            return result(request, output.status(exitCode), exitCode, output, started, authorization, cancellation, deadline);
        } finally {
            timer.cancel(false); terminate(process); cancellation.finish();
        }
    }
    private List<String> command(NetworkPingRequest request) {
        boolean ipv6 = "ipv6".equals(request.family) || request.host.indexOf(':') >= 0;
        List<String> argv = new ArrayList<>();
        if (ipv6 && processes.available(PING6)) argv.add(PING6);
        else if (processes.available(PING)) { argv.add(PING); if (ipv6) argv.add("-6"); else if ("ipv4".equals(request.family)) argv.add("-4"); }
        else if ("auto".equals(request.family) && !NetworkPingRequest.ipv4(request.host) && processes.available(PING6)) argv.add(PING6);
        else return null;
        Collections.addAll(argv, "-c", Integer.toString(request.count), "-W", Integer.toString((request.packetTimeoutMs + 999) / 1000),
                "-w", Integer.toString((request.timeoutMs + 999) / 1000), "-i", String.format(Locale.ROOT, "%.3f", request.intervalMs / 1000d),
                "-s", Integer.toString(request.packetBytes), request.host);
        return Collections.unmodifiableList(argv);
    }
    private JSONObject result(NetworkPingRequest request, String status, Integer exitCode, Output output, long started,
                              NativeOperationAuthorization.Access authorization, Cancellation cancellation, long deadline) throws FactoryException {
        check(authorization, cancellation, deadline);
        JSONObject result;
        try {
            result = new JSONObject().put("protocol", "icmp").put("host", request.host).put("family", request.family)
                    .put("status", status).put("elapsedMs", Math.max(0d, (clock.nanoTime() - started) / 1000000d))
                    .put("exitCode", exitCode == null ? JSONObject.NULL : exitCode)
                    .put("packetWaitMs", ((request.packetTimeoutMs + 999) / 1000) * 1000)
                    .put("summaryParsed", output != null && output.transmitted != null);
            if (output != null) {
                result.put("output", new String(output.captured.toByteArray(), StandardCharsets.UTF_8))
                        .put("outputBytes", output.bytes).put("outputTruncated", output.bytes > request.maxOutputBytes)
                        .put("replySamplesParsed", output.replies).put("oversizedLinesSkipped", output.skipped);
                if (output.transmitted != null) {
                    result.put("transmitted", output.transmitted).put("received", output.received).put("packetLossPercent", output.loss)
                            .put("reachable", output.received > 0);
                } else if (output.replies > 0) result.put("reachable", true);
                if (output.rtt != null && (output.replies > 0 || (output.received != null && output.received > 0))) result.put("latencyMs", output.rtt);
            }
            if (result.toString().getBytes(StandardCharsets.UTF_8).length > NetworkRequest.MAX_RESULT_BYTES)
                throw new FactoryException("RESPONSE_TOO_LARGE", "ICMP result exceeds the bridge envelope.");
        } catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw new FactoryException("UNAVAILABLE", "ICMP result unavailable."); }
        check(authorization, cancellation, deadline); cancellation.complete(); return result;
    }
    private void check(NativeOperationAuthorization.Access authorization, Cancellation cancellation, long deadline) throws FactoryException {
        if (Thread.currentThread().isInterrupted()) cancellation.cancel();
        cancellation.check();
        if (clock.nanoTime() >= deadline) { cancellation.cancel("TIMEOUT"); cancellation.check(); }
        try { authorization.check(); } catch (RuntimeException denied) { cancellation.cancel(); throw denied; }
        cancellation.check();
        if (clock.nanoTime() >= deadline) { cancellation.cancel("TIMEOUT"); cancellation.check(); }
    }
    private static String startFailure(IOException failure) {
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("permission denied") || message.contains("operation not permitted") || message.contains("error=13")) return "platform_denied";
        if (message.contains("no such file") || message.contains("error=2,")) return "unsupported";
        return "failed";
    }
    private static void terminate(Process process) {
        if (process == null) return;
        try { process.destroy(); } catch (RuntimeException ignored) { }
        if (Build.VERSION.SDK_INT >= 26) try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
        close(process.getInputStream()); close(process.getErrorStream()); close(process.getOutputStream());
    }
    private static void close(Closeable value) { if (value != null) try { value.close(); } catch (IOException | RuntimeException ignored) { } }

    /** Keeps bounded text/lines but continues parsing the stream after retained text truncates. */
    private static final class Output {
        private static final Pattern SUMMARY = Pattern.compile("^(\\d+) packets transmitted, (\\d+)(?: packets)? received, (?:.*?, )?([0-9]+(?:\\.[0-9]+)?)% packet loss(?:,.*)?$");
        private static final Pattern RTT = Pattern.compile("^(?:rtt|round-trip) min/avg/max(?:/(?:mdev|stddev))? = ([0-9]+(?:\\.[0-9]+)?)/([0-9]+(?:\\.[0-9]+)?)/([0-9]+(?:\\.[0-9]+)?)(?:/([0-9]+(?:\\.[0-9]+)?))? ms(?:.*)?$");
        private static final Pattern REPLY = Pattern.compile("^[0-9]+ bytes from .+icmp_seq[= ][0-9]+.*time[=<][0-9]+(?:\\.[0-9]+)? ?ms.*$");
        final NetworkPingRequest request;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream(), line = new ByteArrayOutputStream();
        long bytes;
        int replies, skipped;
        boolean oversized, denied, unsupported, dns;
        Integer transmitted, received;
        Double loss;
        JSONObject rtt;
        Output(NetworkPingRequest request) { this.request = request; }
        void accept(byte[] value, int count) {
            int retain = Math.min(count, request.maxOutputBytes - captured.size());
            if (retain > 0) captured.write(value, 0, retain);
            bytes += count;
            for (int i = 0; i < count; i++) {
                if (value[i] == '\n') { consumeLine(); continue; }
                if (oversized) continue;
                if (line.size() >= 8192) { line.reset(); oversized = true; skipped++; }
                else line.write(value[i]);
            }
        }
        void finish() { if (line.size() > 0 || oversized) consumeLine(); }
        void consumeLine() {
            if (!oversized) parse(new String(line.toByteArray(), StandardCharsets.UTF_8).trim());
            line.reset(); oversized = false;
        }
        void parse(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            denied |= lower.contains("operation not permitted") || lower.contains("permission denied") || lower.contains("need root");
            unsupported |= lower.contains("unknown option") || lower.contains("invalid option") || lower.contains("unrecognized option")
                    || lower.startsWith("usage:") || lower.contains("out of range") || lower.contains("bad packet size") || lower.contains("invalid packet size");
            dns |= lower.contains("unknown host") || lower.contains("bad address") || lower.contains("name or service not known")
                    || lower.contains("temporary failure in name resolution");
            if (REPLY.matcher(text).matches() && replies < request.count) replies++;
            Matcher summary = SUMMARY.matcher(text);
            if (summary.matches()) try {
                int sent = Integer.parseInt(summary.group(1)), got = Integer.parseInt(summary.group(2));
                double percent = Double.parseDouble(summary.group(3));
                if (sent >= 1 && sent <= request.count && got >= 0 && got <= sent && percent >= 0 && percent <= 100
                        && Math.abs(percent - (100d * (sent - got) / sent)) <= 1d) {
                    transmitted = sent; received = got; loss = percent;
                }
            } catch (RuntimeException ignored) { }
            Matcher latency = RTT.matcher(text);
            if (latency.matches()) try {
                double min = Double.parseDouble(latency.group(1)), avg = Double.parseDouble(latency.group(2)), max = Double.parseDouble(latency.group(3));
                if (min < 0 || min > avg || avg > max || max > 1e9) return;
                rtt = new JSONObject().put("min", min).put("avg", avg).put("max", max).put("source", "utility_summary");
                if (latency.group(4) != null) {
                    double deviation = Double.parseDouble(latency.group(4));
                    if (deviation >= 0 && deviation <= 1e9) rtt.put("deviation", deviation);
                }
            } catch (Exception ignored) { }
        }
        String status(int exit) {
            if (denied) return "platform_denied";
            if (unsupported) return "unsupported";
            if (dns) return "dns_error";
            if (transmitted != null) return received > 0 ? "ok" : "no_reply";
            if (replies > 0) return "partial";
            return exit == 0 ? "unparsed" : "failed";
        }
    }
}
