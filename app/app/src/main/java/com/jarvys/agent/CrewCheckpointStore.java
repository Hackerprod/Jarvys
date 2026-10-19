package com.jarvys.agent;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.jarvys.agent.crew.CrewInputIncorporation;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CrewCheckpointStore {
    private static final long MAX_CHECKPOINT_BYTES = 2147483639;
    public static final int SCHEMA_VERSION = 1;
    private final ConversationMetadataAccess conversationMetadata;
    private final File filesDirectory;
    private final DirectorySyncFaultInjector directorySyncFaultInjector;
    private final Consumer<Throwable> databaseFailureReporter;

    /** Package-private failure seam; production always performs the real directory fsync. */
    interface DirectorySyncFaultInjector {
        void beforeSync(File directory) throws IOException;
    }

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
            for (CrewInputIncorporation proof : loop.incorporations.values()) {
                if (!conversationId.equals(proof.binding.executionSessionId)
                        || !botId.equals(proof.binding.botId)
                        || !required(metadata, "missionId").equals(proof.binding.missionId)) {
                    throw new IOException("Checkpoint incorporation belongs to a different execution scope");
                }
            }
        }
    }

    /**
     * Immutable evidence for this observed, durability-confirmed checkpoint. This does not
     * assert that its original save returned successfully, or that worker effects completed.
     */
    public static final class ConfirmedSnapshot {
        public final String conversationId;
        public final String botId;
        public final String scopeIdentity;
        public final long savedAtMs;
        public final String payloadSha256;
        public final CoreAgentLoop.Checkpoint loop;
        private final String serializedMetadata;
        private final String serializedArtifactOwnership;

        private ConfirmedSnapshot(ObservedCheckpoint observed) {
            Snapshot snapshot = observed.snapshot;
            conversationId = snapshot.conversationId;
            botId = snapshot.botId;
            scopeIdentity = snapshot.scopeIdentity;
            savedAtMs = snapshot.savedAtMs;
            payloadSha256 = observed.payloadSha256;
            loop = snapshot.loop;
            serializedMetadata = snapshot.metadata.toString();
            serializedArtifactOwnership = snapshot.artifactOwnership.toString();
        }

        public JSONObject metadataJson() {
            return defensiveJson(serializedMetadata);
        }

        public JSONObject artifactOwnershipJson() {
            return defensiveJson(serializedArtifactOwnership);
        }

        private static JSONObject defensiveJson(String serialized) {
            try {
                return new JSONObject(serialized);
            } catch (Exception impossible) {
                throw new IllegalStateException("Validated checkpoint JSON could not be copied", impossible);
            }
        }
    }

    private static final class ObservedCheckpoint {
        final Snapshot snapshot;
        final String payloadSha256;

        ObservedCheckpoint(Snapshot snapshot, String payloadSha256) {
            this.snapshot = snapshot;
            this.payloadSha256 = payloadSha256;
        }
    }

    public CrewCheckpointStore(File filesDirectory) {
        this(filesDirectory, directory -> { }, null);
    }

    CrewCheckpointStore(File filesDirectory, DirectorySyncFaultInjector directorySyncFaultInjector) {
        this(filesDirectory, directorySyncFaultInjector, null);
    }

    CrewCheckpointStore(File filesDirectory, DirectorySyncFaultInjector directorySyncFaultInjector,
            Consumer<Throwable> databaseFailureReporter) {
        if (directorySyncFaultInjector == null) throw new IllegalArgumentException("Directory sync hook is required");
        try {
            this.filesDirectory = filesDirectory.getCanonicalFile();
            verify(this.filesDirectory);
            this.conversationMetadata = new ConversationMetadataAccess(this.filesDirectory);
            this.directorySyncFaultInjector = directorySyncFaultInjector;
            this.databaseFailureReporter = databaseFailureReporter;
        } catch (IOException failure) {
            throw new IllegalStateException("Unsafe Crew checkpoint directory", failure);
        }
    }

    public void save(String conversationId, String botId, JSONObject metadata, String scopeIdentity, CoreAgentLoop.Checkpoint loop, JSONObject artifactOwnership) {
        synchronized (ConversationMetadataAccess.lockForSession(conversationId)) {
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
                for (CrewInputIncorporation incorporation : loop.incorporations.values()) {
                    estimated += estimatedSize(incorporation.toJson(), budget - estimated);
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
                this.conversationMetadata.update(conversationId, "privateCode", true, databaseFailureReporter);
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
                atomicReplace(pending, target);
                syncDirectoryChain(directory);
            } catch (StackOverflowError invalidDepth) {
                throw new IllegalStateException("Checkpoint structure is too deeply nested; evidence preserved", invalidDepth);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                throw new IllegalStateException("Could not save Crew checkpoint; original evidence is preserved", failure);
            }
        }
    }

    public Snapshot load(String conversationId, String botId) {
        synchronized (ConversationMetadataAccess.lockForSession(conversationId)) {
            requireActive(conversationId);
            try {
                File target = file(conversationId, botId);
                if (target.exists()) {
                    return readLocked(conversationId, botId, target);
                }
                rejectIncompleteOnly(target.getParentFile());
                return null;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                throw new IllegalStateException("Crew checkpoint is unavailable; original evidence is preserved", failure);
            } catch (StackOverflowError invalidDepth) {
                throw new IllegalStateException("Checkpoint structure is too deeply nested; evidence preserved", invalidDepth);
            }
        }
    }

    /**
     * Reconcile only persisted evidence. A readable envelope alone is insufficient: a prior
     * save may have renamed it before failing directory sync. No file or receipt is created.
     */
    public ConfirmedSnapshot confirmDurable(String conversationId, String botId, String expectedScopeIdentity) {
        synchronized (ConversationMetadataAccess.lockForSession(conversationId)) {
            requireActive(conversationId);
            try {
                if (expectedScopeIdentity == null || expectedScopeIdentity.isEmpty()) {
                    throw new IllegalArgumentException("Expected checkpoint scope is required");
                }
                File target = file(conversationId, botId);
                if (!target.exists()) {
                    rejectIncompleteOnly(target.getParentFile());
                    return null;
                }
                ObservedCheckpoint observed = readAndSync(target, conversationId, botId, expectedScopeIdentity);
                syncDirectoryChain(target.getParentFile());
                // Directory sync deliberately preserves interruption. Clear that preexisting
                // flag while making the final blocking DB read, then restore it to the caller.
                boolean interrupted = Thread.interrupted();
                try { requireActive(conversationId); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
                return new ConfirmedSnapshot(observed);
            } catch (StackOverflowError invalidDepth) {
                throw new IllegalStateException("Checkpoint structure is too deeply nested; evidence preserved", invalidDepth);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                throw new IllegalStateException("Crew checkpoint durability is unconfirmed; original evidence is preserved", failure);
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
        synchronized (ConversationMetadataAccess.lockForSession(conversationId)) {
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

    private void syncDirectoryChain(File directory) throws IOException {
        for (File parent = directory; parent != null; parent = parent.getParentFile()) {
            verify(parent);
            syncDirectory(parent);
            if (parent.equals(this.filesDirectory)) return;
        }
        throw new IOException("Checkpoint directory is outside its private storage root");
    }

    private void syncDirectory(File directory) throws IOException {
        verify(directory);
        if (!directory.isDirectory()) throw new IOException("Checkpoint sync target is not a directory");
        directorySyncFaultInjector.beforeSync(directory);
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

    private static void atomicReplace(File source, File target) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            Nio.atomicReplace(source, target);
            return;
        }
        try {
            Os.rename(source.getPath(), target.getPath());
        } catch (ErrnoException failure) {
            throw new IOException("Could not atomically commit Crew checkpoint", failure);
        }
    }

    @androidx.annotation.RequiresApi(26)
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
                    } catch (java.nio.file.AccessDeniedException unsupportedDirectoryHandle) {
                        // The production Android filesystem supports directory fsync. Windows JVM
                        // hosts do not expose directory handles through FileChannel.
                        if (File.separatorChar != '\\') throw unsupportedDirectoryHandle;
                        return;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        static void atomicReplace(File source, File target) throws IOException {
            java.nio.file.Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        static ObservedCheckpoint readAndSync(File file, String conversationId, String botId,
                String scopeIdentity) throws Exception {
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    verify(file);
                    if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Invalid checkpoint file");
                    }
                    try (FileChannel channel = FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        if (channel.size() > materializationBudget()) throw new IOException("Invalid checkpoint file");
                        ObservedCheckpoint observed = decodeEnvelope(read(Channels.newInputStream(channel)));
                        validateObserved(observed, conversationId, botId, scopeIdentity);
                        verify(file);
                        // Force the same descriptor whose exact envelope was read and verified.
                        channel.force(true);
                        return observed;
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

    private static ObservedCheckpoint readAndSync(File file, String conversationId, String botId,
            String scopeIdentity) throws Exception {
        verify(file);
        if (!file.isFile() || file.length() > materializationBudget()) throw new IOException("Invalid checkpoint file");
        if (Build.VERSION.SDK_INT >= 26) return Nio.readAndSync(file, conversationId, botId, scopeIdentity);
        FileDescriptor descriptor = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) throw new IOException("Invalid checkpoint file");
            try (FileInputStream input = new FileInputStream(descriptor)) {
                // The stream now owns the descriptor, including on validation/sync failure.
                descriptor = null;
                ObservedCheckpoint observed = decodeEnvelope(read(input));
                validateObserved(observed, conversationId, botId, scopeIdentity);
                verify(file);
                Os.fsync(input.getFD());
                return observed;
            }
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                }
            }
        }
    }

    private static void validateObserved(ObservedCheckpoint observed, String conversationId, String botId,
            String scopeIdentity) throws IOException {
        Snapshot snapshot = observed.snapshot;
        if (!conversationId.equals(snapshot.conversationId) || !botId.equals(snapshot.botId)) {
            throw new IOException("Crew checkpoint belongs to a different conversation or bot");
        }
        if (!scopeIdentity.equals(snapshot.scopeIdentity)) throw new IOException("Crew checkpoint scope has changed");
        if (snapshot.savedAtMs <= 0L) throw new IOException("Invalid checkpoint saved timestamp");
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
        return decodeEnvelope(read(file)).snapshot;
    }

    private static ObservedCheckpoint decodeEnvelope(String serializedEnvelope) throws Exception {
        JSONObject envelope = new JSONObject(serializedEnvelope);
        if (!(envelope.get("schemaVersion") instanceof Number) || ((Number)envelope.get("schemaVersion")).doubleValue() != 1.0) {
            throw new IOException("Unsupported Crew checkpoint schema version");
        }
        String payload = required(envelope, "payload");
        String payloadSha256 = digest(payload.getBytes(StandardCharsets.UTF_8));
        if (!payloadSha256.equals(required(envelope, "sha256"))) {
            throw new IOException("Crew checkpoint digest does not match");
        }
        JSONObject decoded = new JSONObject(payload);
        Object savedAt = decoded.get("savedAtMs");
        if (!(savedAt instanceof Integer) && !(savedAt instanceof Long)) {
            throw new IOException("Invalid checkpoint saved timestamp");
        }
        Snapshot result = new Snapshot(decoded);
        result.loop.reconciled();
        return new ObservedCheckpoint(result, payloadSha256);
    }

    private File file(String conversationId, String botId) throws IOException {
        ConversationMetadataAccess.validateSessionId(conversationId);
        if (botId == null || botId.trim().isEmpty()) {
            throw new IllegalArgumentException("Bot identity is required");
        }
        File result = new File(this.filesDirectory, "jarvys/crew-context/" + digest(conversationId.getBytes(StandardCharsets.UTF_8)) + "/" + digest(botId.getBytes(StandardCharsets.UTF_8)) + "/checkpoint.json");
        verify(result);
        return result;
    }

    private void requireActive(String conversationId) {
        if (this.conversationMetadata.read(conversationId, databaseFailureReporter).deleted) {
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
        if (file.length() > materializationBudget()) {
            throw new IOException("Checkpoint cannot be safely materialized with current memory; evidence preserved");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            return read(input);
        }
    }

    private static String read(InputStream input) throws IOException {
        long budget = materializationBudget();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (((long) output.size()) + count > budget) {
                    throw new IOException("Checkpoint grew beyond its storage limit");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
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

    static JSONObject encodeLoop(CoreAgentLoop.Checkpoint checkpoint) throws Exception {
        JSONObject jSONObject = new JSONObject();
        JSONArray jSONArray = new JSONArray();
        for (ConversationTurn turn : checkpoint.transcript) {
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("kind", turn.kind.name());
            jSONObject2.put("role", turn.role);
            jSONObject2.put("content", sanitizeText(turn.content));
            jSONObject2.put("originalMessageIndex", turn.originalMessageIndex);
            jSONObject2.put("summarizedMessageCount", turn.summarizedMessageCount);
            if (turn.incomingMetadata != null) {
                ConversationTurn.IncomingMetadata provenance = turn.incomingMetadata;
                JSONObject encoded = new JSONObject().put("id", sanitizeText(provenance.id))
                        .put("source", sanitizeText(provenance.source))
                        .put("sender", sanitizeText(provenance.sender))
                        .put("type", sanitizeText(provenance.type))
                        .put("references", new JSONArray(provenance.references))
                        .put("untrusted", provenance.untrusted)
                        .put("origin", provenance.origin.name());
                if (provenance.provenance != null) encoded.put("provenance", provenance.provenance.toJson());
                jSONObject2.put("incomingMetadata", encoded);
            }
            if (!turn.providerOutputItems.isEmpty())
                jSONObject2.put("providerOutputItems", ProviderOutputItemsCodec.encode(turn.providerOutputItems));
            if (turn.kind == ConversationTurn.Kind.MODEL_ERROR)
                jSONObject2.put("modelErrorCode", turn.modelErrorCode);
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
        JSONArray incorporations = new JSONArray();
        for (Map.Entry<String, CrewInputIncorporation> entry : checkpoint.incorporations.entrySet()) {
            CrewInputIncorporation incorporation = entry.getValue();
            if (incorporation == null || !entry.getKey().equals(incorporation.binding.mailboxMessageId)
                    || !checkpoint.appliedIncomingIds.contains(entry.getKey())) {
                throw new IOException("Invalid checkpoint incorporation identity");
            }
            incorporations.put(incorporation.toJson());
        }
        jSONObject.put("incorporations", incorporations);
        return jSONObject;
    }

    static CoreAgentLoop.Checkpoint decodeLoop(JSONObject value) throws Exception {
        List<ConversationTurn> transcript = new ArrayList<>();
        JSONArray rows = value.getJSONArray("transcript");
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            ConversationTurn.Kind kind = ConversationTurn.Kind.valueOf(required(row, "kind"));
            String content = row.getString("content");
            if (kind == ConversationTurn.Kind.MESSAGE) {
                String role = required(row, "role");
                if ("assistant".equals(role) && row.has("providerOutputItems"))
                    transcript.add(ConversationTurn.assistantReply(content,
                            row.getInt("originalMessageIndex"),
                            ProviderOutputItemsCodec.decode(row.optJSONArray("providerOutputItems"))));
                else {
                    JSONObject incoming = row.optJSONObject("incomingMetadata");
                    ConversationTurn.IncomingMetadata metadata = decodeIncomingMetadata(incoming, role);
                    String providerRole = metadata != null && metadata.origin != ConversationInput.Origin.USER
                            ? "assistant" : role;
                    transcript.add(new ConversationTurn(providerRole, content,
                            row.getInt("originalMessageIndex"), metadata));
                }
            } else if (kind == ConversationTurn.Kind.COMPACTION_SUMMARY) {
                transcript.add(ConversationTurn.restoredCompactionSummary(content, row.getInt("summarizedMessageCount")));
            } else if (kind == ConversationTurn.Kind.TOOL_RESULT) {
                transcript.add(ConversationTurn.toolResult(required(row, "toolCallId"), required(row, "toolName"), content));
            } else if (kind == ConversationTurn.Kind.MODEL_ERROR) {
                transcript.add(ConversationTurn.modelError(required(row, "modelErrorCode")));
            } else {
                List<ModelReply.Call> calls = new ArrayList<>();
                JSONArray items = row.getJSONArray("calls");
                for (int callIndex = 0; callIndex < items.length(); callIndex++) {
                    JSONObject item = items.getJSONObject(callIndex);
                    calls.add(new ModelReply.Call(required(item, "id"), required(item, "name"), decodeMap(item.getJSONObject("arguments"))));
                }
                transcript.add(ConversationTurn.toolCalls(content, calls,
                        ProviderOutputItemsCodec.decode(row.optJSONArray("providerOutputItems"))));
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
        Map<String, CrewInputIncorporation> incorporations = new LinkedHashMap<>();
        if (value.has("incorporations")) {
            JSONArray proofs = value.getJSONArray("incorporations");
            for (int index = 0; index < proofs.length(); index++) {
                CrewInputIncorporation proof = CrewInputIncorporation.fromJsonStrict(proofs.getJSONObject(index));
                String mailboxMessageId = proof.binding.mailboxMessageId;
                if (!incoming.contains(mailboxMessageId) || incorporations.put(mailboxMessageId, proof) != null) {
                    throw new IOException("Invalid or repeated checkpoint incorporation proof");
                }
            }
        }
        return new CoreAgentLoop.Checkpoint(transcript, incoming, states, value.getInt("genuineUserIndex"), incorporations);
    }

    private static ConversationTurn.IncomingMetadata decodeIncomingMetadata(JSONObject row, String storedRole) throws Exception {
        if (row == null) return null;
        JSONArray references = row.getJSONArray("references");
        List<String> decoded = new ArrayList<>();
        for (int index = 0; index < references.length(); index++) decoded.add(references.getString(index));
        String storedOrigin = row.optString("origin", "");
        ConversationInput.Origin origin = storedOrigin.isEmpty()
                ? ("user".equals(storedRole) && "crew".equals(row.optString("source")) && row.optBoolean("untrusted")
                    ? ConversationInput.Origin.AGENT : ConversationInput.Origin.USER)
                : ConversationInput.parseOrigin(storedOrigin);
        if (origin == null) throw new IOException("Unknown conversation input origin");
        ConversationInput.Provenance typed = row.optJSONObject("provenance") == null ? null
                : ConversationInput.Provenance.fromJson(row.getJSONObject("provenance"));
        return new ConversationTurn.IncomingMetadata(origin, required(row, "id"), required(row, "source"),
                required(row, "sender"), required(row, "type"), decoded, row.getBoolean("untrusted"), typed);
    }

    private static Map<String, Object> decodeMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, decodeValue(object.get(key)));
        }
        return Collections.unmodifiableMap(result);
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
            return Collections.unmodifiableList(result);
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
