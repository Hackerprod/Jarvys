package com.jarvys.agent;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CrewCheckpointStore {
    private static final long MAX_CHECKPOINT_BYTES = 2147483639;
    public static final int SCHEMA_VERSION = 1;
    private final ConversationMetadataStore conversationMetadata;
    private final File filesDirectory;

    public static final class Snapshot {
        public final JSONObject artifactOwnership;
        public final String botId;
        public final String conversationId;
        public final CoreAgentLoop.Checkpoint loop;
        public final JSONObject metadata;
        public final long savedAtMs;
        public final String scopeIdentity;

        Snapshot(JSONObject payload) throws Exception {
            this.conversationId = CrewCheckpointStore.required(payload, "conversationId");
            this.botId = CrewCheckpointStore.required(payload, "botId");
            this.scopeIdentity = CrewCheckpointStore.required(payload, "scopeIdentity");
            this.metadata = payload.getJSONObject("metadata");
            this.loop = CrewCheckpointStore.decodeLoop(payload.getJSONObject("loop"));
            this.artifactOwnership = payload.getJSONObject("artifactOwnership");
            this.savedAtMs = payload.getLong("savedAtMs");
        }
    }

    public CrewCheckpointStore(File filesDirectory) {
        try {
            this.filesDirectory = filesDirectory.getCanonicalFile();
            verify(this.filesDirectory);
            this.conversationMetadata = new ConversationMetadataStore(this.filesDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("Unsafe Crew checkpoint directory", failure);
        }
    }

    public void save(String conversationId, String botId, JSONObject metadata, String scopeIdentity, CoreAgentLoop.Checkpoint loop, JSONObject artifactOwnership) {
        synchronized (ConversationMetadataStore.LOCK) {
            requireActive(conversationId);
            try {
                if (scopeIdentity == null || scopeIdentity.isEmpty() || loop == null || metadata == null || artifactOwnership == null) {
                    throw new IllegalArgumentException("Complete checkpoint identity and state are required");
                }
                long budget = materializationBudget();
                long estimated = estimatedSize(metadata, budget) + estimatedSize(artifactOwnership, budget) + estimatedSize(loop.appliedIncomingIds, budget) + estimatedSize(loop.toolLifecycle, budget);
                if (estimated > budget) {
                    throw new IOException("Checkpoint exceeds current materialization headroom; evidence preserved");
                }
                for (ConversationTurn turn : loop.transcript) {
                    estimated += estimatedSize(turn.content, budget - estimated);
                    for (ModelReply.Call call : turn.toolCalls) {
                        estimated += estimatedSize(call.arguments, budget - estimated);
                    }
                    if (estimated > budget) {
                        throw new IOException("Checkpoint exceeds current materialization headroom; evidence preserved");
                    }
                }
                File target = file(conversationId, botId);
                if (target.exists()) readLocked(conversationId, botId, target); else rejectIncompleteOnly(target.getParentFile());
                JSONObject payload = new JSONObject();
                payload.put("conversationId", conversationId);
                payload.put("botId", botId);
                payload.put("scopeIdentity", scopeIdentity);
                payload.put("metadata", sanitizeJson(metadata));
                payload.put("loop", encodeLoop(loop));
                payload.put("artifactOwnership", sanitizeJson(artifactOwnership));
                payload.put("savedAtMs", System.currentTimeMillis());
                new Snapshot(payload).loop.reconciled();
                String serialized = payload.toString();
                byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > budget) throw new IOException("Crew checkpoint exceeds its storage limit");
                JSONObject envelope = new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("sha256", digest(bytes)).put("payload", serialized);
                File directory = target.getParentFile();
                verify(directory);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create checkpoint directory");
                }
                verify(directory);
                this.conversationMetadata.update(conversationId, "privateCode", true);
                File metadataDirectory = new File(this.filesDirectory, "jarvys/conversations");
                verify(metadataDirectory);
                syncDirectory(metadataDirectory);
                File pending = File.createTempFile("checkpoint-pending-", ".json", directory);
                verify(pending);
                try (FileOutputStream output = new FileOutputStream(pending);) {
                    output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.getFD().sync();
                }
                requireActive(conversationId);
                verify(directory);
                verify(target);
                verify(pending);
                if (!pending.renameTo(target)) throw new IOException("Could not atomically commit Crew checkpoint");
                for (File parent = directory; parent != null; parent = parent.getParentFile()) {
                    verify(parent);
                    syncDirectory(parent);
                    if (parent.equals(this.filesDirectory)) break;
                }
            } catch (StackOverflowError invalidDepth) {
                throw new IllegalStateException("Checkpoint structure is too deeply nested; evidence preserved", invalidDepth);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not save Crew checkpoint; original evidence is preserved", failure);
            }
        }
    }

    public Snapshot load(String conversationId, String botId) {
        synchronized (ConversationMetadataStore.LOCK) {
            requireActive(conversationId);
            try {
                File target = file(conversationId, botId);
                if (target.exists()) {
                    return readLocked(conversationId, botId, target);
                }
                rejectIncompleteOnly(target.getParentFile());
                return null;
            } catch (Exception failure) {
                throw new IllegalStateException("Crew checkpoint is unavailable; original evidence is preserved", failure);
            } catch (StackOverflowError invalidDepth) {
                throw new IllegalStateException("Checkpoint structure is too deeply nested; evidence preserved", invalidDepth);
            }
        }
    }

    public List<Snapshot> list(String conversationId) {
        return scan(conversationId).snapshots;
    }

    public List<String> listIssues(String conversationId) {
        return scan(conversationId).issues;
    }

    private static final class Listing {
        final List<String> issues;
        final List<Snapshot> snapshots;

        private Listing() {
            this.snapshots = new ArrayList<>();
            this.issues = new ArrayList<>();
        }
    }

    private Listing scan(String conversationId) {
        synchronized (ConversationMetadataStore.LOCK) {
            requireActive(conversationId);
            Listing result = new Listing();
            File directory = new File(this.filesDirectory, "jarvys/crew-context/" + digest(conversationId.getBytes(StandardCharsets.UTF_8)));
            try {
                verify(directory);
                if (!directory.exists()) {
                    return result;
                }
                File[] children = directory.listFiles();
                if (children == null) {
                    throw new IOException("Could not enumerate Crew checkpoints");
                }
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    String hash = child.getName();
                    if (!"coding-jobs".equals(hash)) {
                        if (!hash.matches("[0-9a-f]{64}")) {
                            result.issues.add("Unrecognized entry in private Crew checkpoint directory");
                        } else {
                            try {
                                verify(child);
                                if (!child.isDirectory()) {
                                    throw new IOException("Checkpoint scope is not a directory");
                                }
                                File checkpoint = new File(child, "checkpoint.json");
                                verify(checkpoint);
                                if (checkpoint.exists()) {
                                    Snapshot snapshot = readSnapshot(checkpoint);
                                    if (!conversationId.equals(snapshot.conversationId) || !hash.equals(digest(snapshot.botId.getBytes(StandardCharsets.UTF_8)))) {
                                        throw new IOException("Checkpoint identity does not match its private directory");
                                    }
                                    result.snapshots.add(snapshot);
                                } else {
                                    rejectIncompleteOnly(child);
                                }
                            } catch (Exception | StackOverflowError e) {
                                result.issues.add("Crew checkpoint " + hash + " is unavailable; original evidence is preserved");
                            }
                        }
                    }
                }
                return result;
            } catch (IOException failure) {
                throw new IllegalStateException("Could not enumerate private Crew checkpoints", failure);
            }
        }
    }

    public static boolean deleteConversation(File filesDirectory, String conversationId) {
        return CrewContextArtifacts.deleteConversation(filesDirectory, conversationId);
    }

    private static void rejectIncompleteOnly(File directory) throws IOException {
        verify(directory);
        if (directory.exists()) {
            File[] incomplete = directory.listFiles(new FilenameFilter(){

                @Override
                public final boolean accept(File file, String str) {
                    return str.startsWith("checkpoint-pending-");
                }
            });
            if (incomplete == null || incomplete.length > 0) {
                throw new IOException("An incomplete Crew checkpoint exists without a committed predecessor");
            }
        }
    }

    private static void syncDirectory(File directory) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            Nio.syncDirectory(directory);
            return;
        }
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw new IOException("Checkpoint sync target is not a directory");
            }
            Os.fsync(descriptor);
        } catch (ErrnoException failure) {
            throw new IOException("Could not sync Crew checkpoint directory", failure);
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                }
            }
        }
    }

    private static final class Nio {

        private Nio() {
        }

        static void syncDirectory(File directory) throws IOException {
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    try (FileChannel channel = FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS);) {
                        channel.force(true);
                        return;
                    } catch (ClosedByInterruptException cancelledDuringSync) {
                        interrupted = true;
                        Thread.interrupted();
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private Snapshot readLocked(String conversationId, String botId, File file) throws Exception {
        Snapshot result = readSnapshot(file);
        if (!conversationId.equals(result.conversationId) || !botId.equals(result.botId)) {
            throw new IOException("Crew checkpoint belongs to a different conversation or bot");
        }
        return result;
    }

    private Snapshot readSnapshot(File file) throws Exception {
        verify(file);
        if (!file.isFile() || file.length() > materializationBudget()) {
            throw new IOException("Invalid checkpoint file");
        }
        JSONObject envelope = new JSONObject(read(file));
        if (!(envelope.get("schemaVersion") instanceof Number) || ((Number)envelope.get("schemaVersion")).doubleValue() != 1.0) {
            throw new IOException("Unsupported Crew checkpoint schema version");
        }
        String payload = required(envelope, "payload");
        if (!digest(payload.getBytes(StandardCharsets.UTF_8)).equals(required(envelope, "sha256"))) {
            throw new IOException("Crew checkpoint digest does not match");
        }
        Snapshot result = new Snapshot(new JSONObject(payload));
        result.loop.reconciled();
        return result;
    }

    private File file(String conversationId, String botId) throws IOException {
        ConversationMetadataStore.validateSessionId(conversationId);
        if (botId == null || botId.trim().isEmpty()) {
            throw new IllegalArgumentException("Bot identity is required");
        }
        File result = new File(this.filesDirectory, "jarvys/crew-context/" + digest(conversationId.getBytes(StandardCharsets.UTF_8)) + "/" + digest(botId.getBytes(StandardCharsets.UTF_8)) + "/checkpoint.json");
        verify(result);
        return result;
    }

    private void requireActive(String conversationId) {
        if (this.conversationMetadata.read(conversationId).deleted) {
            throw new IllegalStateException("This chat has been deleted; Crew checkpoint access is disabled");
        }
    }

    static void verify(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) {
            throw new IOException("Crew checkpoint symlinks are not allowed");
        }
    }

    static long estimatedSize(Object value, long available) throws IOException {
        long size = value instanceof String ? 32 + (((long)((String)value).length()) * 2) : 32L;
        if (size > available) {
            throw new IOException("Checkpoint exceeds current materialization headroom; evidence preserved");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long size2 = size + estimatedSize(key, available - size);
                size = size2 + estimatedSize(object.opt(key), available - size2);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int index = 0; index < array.length(); index++) {
                size += estimatedSize(array.opt(index), available - size);
            }
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> item : ((Map<?, ?>)value).entrySet()) {
                long size3 = size + estimatedSize(item.getKey(), available - size);
                size = size3 + estimatedSize(item.getValue(), available - size3);
            }
        } else if (value instanceof Iterable) {
            Iterator it = ((Iterable)value).iterator();
            while (it.hasNext()) {
                size += estimatedSize(it.next(), available - size);
            }
        }
        return size;
    }

    public static long materializationBudget() {
        Runtime runtime = Runtime.getRuntime();
        long headroom = Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        return Math.min(MAX_CHECKPOINT_BYTES, headroom / 32);
    }

    private static String read(File file) throws IOException {
        long budget = materializationBudget();
        if (file.length() > budget) {
            throw new IOException("Checkpoint cannot be safely materialized with current memory; evidence preserved");
        }
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[8192];
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        String str = new String(output.toByteArray(), StandardCharsets.UTF_8);
                        output.close();
                        input.close();
                        return str;
                    }
                    if (((long)output.size()) + ((long)count) > budget) {
                        throw new IOException("Checkpoint grew beyond its storage limit");
                    }
                    output.write(buffer, 0, count);
                }
            } catch (Throwable th) {
                try {
                    output.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            try {
                input.close();
            } catch (Throwable th4) {
                th3.addSuppressed(th4);
            }
            throw th3;
        }
    }

    static String digest(byte[] bytes) {
        try {
            StringBuilder value = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                value.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(part & 255)));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JSONObject encodeLoop(CoreAgentLoop.Checkpoint checkpoint) throws Exception {
        JSONObject jSONObject = new JSONObject();
        JSONArray jSONArray = new JSONArray();
        for (ConversationTurn turn : checkpoint.transcript) {
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("kind", turn.kind.name());
            jSONObject2.put("role", turn.role);
            jSONObject2.put("content", sanitizeText(turn.content));
            jSONObject2.put("originalMessageIndex", turn.originalMessageIndex);
            jSONObject2.put("summarizedMessageCount", turn.summarizedMessageCount);
            if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
                JSONArray calls = new JSONArray();
                for (ModelReply.Call call : turn.toolCalls) {
                    JSONObject item = new JSONObject();
                    item.put("id", call.id);
                    item.put("name", call.name);
                    item.put("arguments", sanitizeJson(new JSONObject(call.arguments)));
                    calls.put(item);
                }
                jSONObject2.put("calls", calls);
            } else if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
                jSONObject2.put("toolCallId", turn.toolCallId);
                jSONObject2.put("toolName", turn.toolName);
            }
            jSONArray.put(jSONObject2);
        }
        jSONObject.put("transcript", jSONArray);
        jSONObject.put("appliedIncomingIds", new JSONArray((Collection)checkpoint.appliedIncomingIds));
        jSONObject.put("toolLifecycle", new JSONObject(checkpoint.toolLifecycle));
        jSONObject.put("genuineUserIndex", checkpoint.genuineUserIndex);
        return jSONObject;
    }

    private static CoreAgentLoop.Checkpoint decodeLoop(JSONObject value) throws Exception {
        List<ConversationTurn> transcript = new ArrayList<>();
        JSONArray rows = value.getJSONArray("transcript");
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            ConversationTurn.Kind kind = ConversationTurn.Kind.valueOf(required(row, "kind"));
            String content = row.getString("content");
            if (kind == ConversationTurn.Kind.MESSAGE) {
                transcript.add(new ConversationTurn(required(row, "role"), content, row.getInt("originalMessageIndex")));
            } else if (kind == ConversationTurn.Kind.COMPACTION_SUMMARY) {
                transcript.add(ConversationTurn.restoredCompactionSummary(content, row.getInt("summarizedMessageCount")));
            } else if (kind == ConversationTurn.Kind.TOOL_RESULT) {
                transcript.add(ConversationTurn.toolResult(required(row, "toolCallId"), required(row, "toolName"), content));
            } else {
                List<ModelReply.Call> calls = new ArrayList<>();
                JSONArray items = row.getJSONArray("calls");
                for (int callIndex = 0; callIndex < items.length(); callIndex++) {
                    JSONObject item = items.getJSONObject(callIndex);
                    calls.add(new ModelReply.Call(required(item, "id"), required(item, "name"), decodeMap(item.getJSONObject("arguments"))));
                }
                transcript.add(ConversationTurn.toolCalls(content, calls));
            }
        }
        Set<String> incoming = new LinkedHashSet<>();
        JSONArray ids = value.getJSONArray("appliedIncomingIds");
        for (int index2 = 0; index2 < ids.length(); index2++) {
            Object id = ids.get(index2);
            if (!(id instanceof String) || ((String)id).isEmpty() || !incoming.add((String)id)) {
                throw new IOException("Invalid or repeated durable incoming ID");
            }
        }
        Map<String, String> states = new LinkedHashMap<>();
        JSONObject lifecycle = value.getJSONObject("toolLifecycle");
        Iterator<String> keys = lifecycle.keys();
        while (keys.hasNext()) {
            String id2 = keys.next();
            String state = required(lifecycle, id2);
            if (id2.isEmpty() || (!"INTENT".equals(state) && !"STARTED".equals(state) && !"RESULT".equals(state) && !"NEVER_LAUNCHED".equals(state) && !"INTERRUPTED_UNCERTAIN".equals(state))) {
                throw new IOException("Unknown tool lifecycle state");
            }
            states.put(id2, state);
        }
        return new CoreAgentLoop.Checkpoint(transcript, incoming, states, value.getInt("genuineUserIndex"));
    }

    private static Map<String, Object> decodeMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, decodeValue(object.get(key)));
        }
        return result;
    }

    private static Object decodeValue(Object value) throws Exception {
        if (value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return decodeMap((JSONObject)value);
        }
        if (value instanceof JSONArray) {
            List<Object> result = new ArrayList<>();
            JSONArray array = (JSONArray)value;
            for (int index = 0; index < array.length(); index++) {
                result.add(decodeValue(array.get(index)));
            }
            return result;
        }
        return value;
    }

    static Object sanitizeJson(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject();
            JSONObject source = (JSONObject)value;
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String normalized = key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
                boolean secret = normalized.matches(".*(?:password|passwd|secret|credential|authorization|cookie|apikey|accesstoken|refreshtoken|bearertoken).*") || normalized.equals("token") || normalized.contains("grant") || normalized.equals("approvaltoken");
                result.put(key, secret ? "[REDACTED]" : sanitizeJson(source.get(key)));
            }
            return result;
        }
        if (!(value instanceof JSONArray)) {
            return value instanceof String ? sanitizeText((String)value) : value;
        }
        JSONArray source2 = (JSONArray)value;
        JSONArray result2 = new JSONArray();
        for (int index = 0; index < source2.length(); index++) {
            result2.put(sanitizeJson(source2.get(index)));
        }
        return result2;
    }

    static String sanitizeText(String text) {
        String trimmed = text.trim();
        try {
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return sanitizeJson(new JSONObject(trimmed)).toString();
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return sanitizeJson(new JSONArray(trimmed)).toString();
            }
        } catch (Exception ignored) {
        }
        return CrewContextArtifacts.redactProse(text);
    }

    private static String required(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String)value).isEmpty()) {
            throw new IOException("Missing checkpoint string: " + key);
        }
        return (String)value;
    }
}
