package com.jarvys.agent;

import com.jarvys.agent.coding.ArtifactSnapshotIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Static, bounded dependency capture. It never lists a directory or executes generated code. */
final class HtmlPreviewCapture {
    static final int MAX_FILES = 128;
    static final long MAX_FILE_BYTES = 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    static final int MAX_REFERENCES = 2048;
    static final int MAX_DEPTH = 16;
    private static final Set<String> EXTENSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "html", "htm", "css", "js", "mjs", "svg", "png", "jpg", "jpeg", "gif", "webp", "ico", "avif", "bmp", "woff", "woff2", "ttf", "otf")));
    private static final Pattern ATTRIBUTE = Pattern.compile("(?i)\\b(src|href|poster|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s()]+))\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile("(?i)@import\\s+[\"']([^\"']+)[\"']");
    private static final Pattern JS_IMPORT = Pattern.compile("(?:\\b(?:import|export)\\s+(?:[^;\\n\"']{0,512}?\\s+from\\s*)?|\\bimport\\s*\\(\\s*)[\"']([^\"'\\r\\n]+)[\"']");
    private static final Pattern JS_URL = Pattern.compile("\\bnew\\s+URL\\s*\\(\\s*[\"']([^\"'\\r\\n]+)[\"']\\s*,\\s*import\\.meta\\.url\\s*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<(?:!doctype\\s+html|html\\b|head\\b|body\\b|title\\b|meta\\b|link\\b|script\\b|style\\b|div\\b|span\\b|main\\b|section\\b|p\\b|h[1-6]\\b|a\\b|img\\b|canvas\\b|button\\b|form\\b|table\\b|ul\\b|svg\\b)");

    static final class Asset {
        final String path, sha256;
        final long bytes;
        Asset(String path, long bytes, String sha256) { this.path = path; this.bytes = bytes; this.sha256 = sha256; }
        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("path", path).put("bytes", bytes).put("sha256", sha256);
        }
    }

    static final class Capture {
        final File directory;
        final String entryPath, provenance;
        final Map<String, Asset> assets = new TreeMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        long bytes;
        Capture(File directory, String entryPath, String provenance) {
            this.directory = directory; this.entryPath = entryPath; this.provenance = provenance;
        }
        String fingerprint() throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, "html-preview-v1"); update(digest, provenance); update(digest, entryPath);
                for (Asset asset : assets.values()) { update(digest, asset.path); update(digest, Long.toString(asset.bytes)); update(digest, asset.sha256); }
                for (String warning : warnings) update(digest, warning);
                return ArtifactSnapshotIO.hex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        JSONObject json() throws Exception {
            JSONArray entries = new JSONArray();
            for (Asset asset : assets.values()) entries.put(asset.json());
            return new JSONObject().put("schema", 1).put("entryPath", entryPath).put("provenance", provenance)
                    .put("fingerprint", fingerprint()).put("totalBytes", bytes).put("files", entries)
                    .put("warnings", new JSONArray(warnings));
        }
        private static void update(MessageDigest digest, String value) {
            digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
    }

    static boolean htmlName(String path) {
        String extension = extension(path);
        return extension.equals("html") || extension.equals("htm");
    }

    static Capture capture(WorkspaceStore.DeliverySource source, File entry, ArtifactSnapshotIO.Snapshot entryCopy,
            File directory, CancellationToken token) throws IOException {
        requireOrdinaryPath(source.relative);
        if (!htmlName(source.relative) || entryCopy.bytes > MAX_FILE_BYTES) return null;
        String html;
        try { html = text(entry); } catch (IOException binary) { return null; }
        if (!HTML_TAG.matcher(html).find()) return null;
        Capture result = new Capture(directory, source.relative, source.provenance);
        File entryTarget = destination(directory, source.relative);
        copyPrivate(entry, entryTarget, token);
        result.assets.put(source.relative, new Asset(source.relative, entryCopy.bytes, entryCopy.sha256));
        result.bytes = entryCopy.bytes;
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(source.relative, 0, html));
        Set<String> seen = new LinkedHashSet<>(); seen.add(source.relative);
        int references = 0;
        while (!pending.isEmpty()) {
            token.throwIfCancelled();
            Pending current = pending.removeFirst();
            List<String> found = references(current.path, current.text, result.warnings, token);
            for (String raw : found) {
                if (++references > MAX_REFERENCES) { result.warnings.add("Dependency discovery reached its reference limit; some assets were not captured."); return result; }
                String relative;
                try { relative = resolveReference(current.path, raw); }
                catch (IOException unsafe) { result.warnings.add("Unsafe or unsupported asset paths were excluded from the preview."); continue; }
                if (relative == null) {
                    if (raw.matches("(?is)^(?:https?:)?//.*")) result.warnings.add("External resources were excluded; external URL resource loads are blocked.");
                    continue;
                }
                if (!seen.add(relative)) continue;
                if (!EXTENSIONS.contains(extension(relative))) { result.warnings.add("Unsupported asset types were excluded from the preview."); continue; }
                if (current.depth >= MAX_DEPTH) { result.warnings.add("Dependency discovery reached its depth limit; some assets were not captured."); continue; }
                if (result.assets.size() >= MAX_FILES || result.bytes >= MAX_TOTAL_BYTES) {
                    result.warnings.add("Preview capture reached its file or total-byte limit; some assets were not captured."); continue;
                }
                File stagedAsset = File.createTempFile(".asset-", ".tmp", directory);
                try {
                    ArtifactSnapshotIO.Snapshot copied = source.copy(relative, stagedAsset,
                            Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - result.bytes), token);
                    String content = null;
                    if (isText(relative)) content = text(stagedAsset);
                    // Missing references never create directory trees; only admitted assets get their path.
                    File target = destination(directory, relative);
                    if (target.exists() || !stagedAsset.renameTo(target)) throw new IOException("Could not retain preview asset");
                    result.assets.put(relative, new Asset(relative, copied.bytes, copied.sha256)); result.bytes += copied.bytes;
                    if (content != null) pending.addLast(new Pending(relative, current.depth + 1, content));
                } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                catch (IOException | IllegalArgumentException unavailable) {
                    result.warnings.add("Some referenced assets were missing, unsafe, changed during capture, binary text, or over the capture limits.");
                } finally {
                    if (stagedAsset.exists() && !stagedAsset.delete()) throw new IOException("Could not discard unavailable preview asset");
                }
            }
        }
        return result;
    }

    private static final class Pending {
        final String path, text; final int depth;
        Pending(String path, int depth, String text) { this.path = path; this.depth = depth; this.text = text; }
    }

    private static List<String> references(String path, String content, Set<String> warnings, CancellationToken token) {
        List<String> values = new ArrayList<>();
        String ext = extension(path);
        if (htmlName(path) || ext.equals("svg")) {
            Matcher attributes = ATTRIBUTE.matcher(content);
            while (attributes.find() && values.size() <= MAX_REFERENCES) {
                token.throwIfCancelled();
                String raw = first(attributes, 2, 3, 4);
                if (attributes.group(1).equalsIgnoreCase("srcset")) {
                    if (raw.trim().startsWith("data:")) continue;
                    for (String candidate : raw.split(",", MAX_REFERENCES + 1)) {
                        String value = candidate.trim().split("\\s+", 2)[0]; if (!value.isEmpty()) values.add(value);
                        if (values.size() > MAX_REFERENCES) break;
                    }
                } else values.add(raw);
            }
            if (Pattern.compile("(?i)<base\\b").matcher(content).find()) warnings.add("HTML base elements are blocked; assets use the captured project root.");
        }
        if (htmlName(path) || ext.equals("css") || ext.equals("svg")) {
            collect(CSS_URL, content, values, true, token); collect(CSS_IMPORT, content, values, false, token);
        }
        if (htmlName(path) || ext.equals("js") || ext.equals("mjs")) {
            collect(JS_IMPORT, content, values, false, token); collect(JS_URL, content, values, false, token);
            if (Pattern.compile("\\b(?:fetch|XMLHttpRequest|WebSocket|Worker)\\b|\\bimport\\s*\\(\\s*[^\"'\\s]").matcher(content).find())
                warnings.add("Dynamic or network-loaded assets cannot be captured; only static local references are available.");
        }
        return values;
    }

    private static void collect(Pattern pattern, String text, List<String> values, boolean alternatives, CancellationToken token) {
        Matcher matcher = pattern.matcher(text);
        while (values.size() <= MAX_REFERENCES && matcher.find()) {
            token.throwIfCancelled();
            values.add(alternatives ? first(matcher, 1, 2, 3) : matcher.group(1));
        }
        token.throwIfCancelled();
    }
    private static String first(Matcher matcher, int... groups) {
        for (int group : groups) if (matcher.group(group) != null) return matcher.group(group);
        return "";
    }

    /** URL references may use in-root ../ paths; encoded separators/dots and above-root paths never pass. */
    static String resolveReference(String from, String raw) throws IOException {
        if (raw == null || raw.length() > 2048) throw new IOException("Asset URL is invalid");
        String value = raw.trim().replace("&amp;", "&");
        if (value.isEmpty() || value.startsWith("#")) return null;
        if (value.startsWith("//") || value.matches("(?is)^[a-z][a-z0-9+.-]*:.*")) return null;
        int query = value.indexOf('?'), fragment = value.indexOf('#');
        int end = query < 0 ? value.length() : query; if (fragment >= 0) end = Math.min(end, fragment);
        value = value.substring(0, end);
        if (value.isEmpty()) return null;
        if (value.matches("(?is).*%(?:2e|2f|5c|00|25).*")) throw new IOException("Encoded path escapes are unavailable");
        try { value = java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid URL encoding", invalid); }
        if (value.indexOf('%') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.contains("&#")) throw new IOException("Asset path is invalid");
        List<String> parts = new ArrayList<>();
        if (!value.startsWith("/")) {
            int slash = from.lastIndexOf('/');
            if (slash >= 0) parts.addAll(Arrays.asList(from.substring(0, slash).split("/")));
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) throw new IOException("Asset escaped project root"); parts.remove(parts.size() - 1); }
            else parts.add(part);
        }
        String relative = String.join("/", parts);
        if (value.endsWith("/")) relative += (relative.isEmpty() ? "" : "/") + "index.html";
        requireOrdinaryPath(relative);
        return relative;
    }

    static void requireOrdinaryPath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 1024 || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf('%') >= 0 || path.indexOf(':') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0)
            throw new IOException("A bounded ordinary project path is required");
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_DEPTH) throw new IOException("Asset path is too deep");
        for (String part : parts) {
            if (part.isEmpty() || part.startsWith(".") || part.length() > 255) throw new IOException("Hidden or relative path segments are unavailable");
            for (int i = 0; i < part.length(); i++) if (Character.isISOControl(part.charAt(i))) throw new IOException("Control characters are unavailable in asset paths");
        }
        String first = parts[0].toLowerCase(Locale.ROOT);
        if (Arrays.asList("memory", "skills", "attachments", "delivered", "coding-projects", "workspaces").contains(first))
            throw new IOException("Private workspace zones are unavailable to previews");
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    static boolean isText(String path) {
        return Arrays.asList("html", "htm", "css", "js", "mjs", "svg").contains(extension(path));
    }
    static boolean isHtmlFile(File file) {
        try { return file.length() <= MAX_FILE_BYTES && HTML_TAG.matcher(text(file)).find(); }
        catch (IOException invalid) { return false; }
    }

    private static String text(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) throw new IOException("Preview text exceeds the limit");
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) { if (out.size() + count > MAX_FILE_BYTES) throw new IOException("Preview text exceeds the limit"); out.write(buffer, 0, count); }
            bytes = out.toByteArray();
        }
        String value;
        try { value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IOException("Preview text is not UTF-8", invalid); }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') throw new IOException("Binary preview text is unavailable");
        }
        return value;
    }
    private static File destination(File directory, String relative) throws IOException {
        requireOrdinaryPath(relative);
        File target = new File(directory, relative), parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create preview asset directory");
        if (!target.getCanonicalPath().equals(new File(directory.getCanonicalFile(), relative).getPath()))
            throw new IOException("Preview destination changed");
        return target;
    }
    private static void copyPrivate(File source, File target, CancellationToken token) throws IOException {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) { token.throwIfCancelled(); total += count; if (total > MAX_FILE_BYTES) throw new IOException("Preview entry exceeds the limit"); output.write(buffer, 0, count); }
            output.getFD().sync();
        }
    }
}
