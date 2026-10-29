package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.coding.ProjectScope;
import com.jarvys.agent.coding.ProjectScopeStore;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import com.jarvys.agent.skills.SkillRepository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Routes per-conversation project files and app-wide installed skills. */
public final class WorkspaceStore {
    public static final int MAX_FILE_BYTES = 256 * 1024;
    private static final long MAX_WORKSPACE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_LIST_ENTRIES = 200;
    private static final String SKILLS_ZONE = "skills";
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final java.util.concurrent.ConcurrentMap<String, Object> PROJECT_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final String projectId;
    private final File root;
    private final File appFiles;
    private final String canonicalRoot;
    private final Object projectLock;
    private final File skillsRoot;
    private final String canonicalSkillsRoot;
    private final SkillWorkspaceObserver skillWorkspaceObserver;
    private final String conversationId;
    private final boolean skillsOnly;
    private final boolean attachmentsAllowed;
    private final boolean delegatedPrivateZonesDenied;
    private final String expectedAttachmentWorkspaceRoot;

    public interface SkillWorkspaceObserver {
        /** Serializes the file write with skill validation, enabled-state initialization, and rescan. */
        void commitSkillWorkspaceWrite(String skillId, String skillMarkdown, Runnable writeFile);
    }

    public static String projectIdForSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) throw new IllegalArgumentException("Session id is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 12; i++) id.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            return id.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Could not create workspace id", error);
        }
    }

    public WorkspaceStore(Context context, String projectId) {
        this(context, projectId, projectId);
    }

    public WorkspaceStore(Context context, String projectId, String conversationId) {
        this(new StorageGeneration(context), workspaceRoot(context), projectId,
                new File(context.getApplicationContext().getFilesDir(), "skills"),
                SkillRepository.Companion.get(context.getApplicationContext()), conversationId, false);
    }

    public static WorkspaceStore forScheduledTask(Context context, String sessionId) {
        Context app = context.getApplicationContext();
        StorageGeneration storage = new StorageGeneration(app);
        return new WorkspaceStore(storage, storage.directory("workspaces"), projectIdForSession(sessionId),
                new File(app.getFilesDir(), "skills"), null, sessionId, true);
    }

    WorkspaceStore(File workspacesRoot, String projectId) {
        this(workspacesRoot, projectId, null, null);
    }

    WorkspaceStore(File workspacesRoot, String projectId, File skillsRoot,
                   SkillWorkspaceObserver observer) {
        this(workspacesRoot, projectId, skillsRoot, observer, projectId, false);
    }

    WorkspaceStore(File workspacesRoot, String projectId, File skillsRoot,
                   SkillWorkspaceObserver observer, String conversationId, boolean skillsOnly) {
        this(null, workspacesRoot, projectId, skillsRoot, observer, conversationId, skillsOnly);
    }

    WorkspaceStore(StorageGeneration storage, String projectId) {
        this(storage, projectId, null, null, projectId);
    }

    WorkspaceStore(StorageGeneration storage, String projectId, File skillsRoot,
                   SkillWorkspaceObserver observer, String conversationId) {
        this(storage, storage.directory("workspaces"), projectId, skillsRoot, observer, conversationId, false);
    }

    private WorkspaceStore(StorageGeneration storage, File workspacesRoot, String projectId, File skillsRoot,
                   SkillWorkspaceObserver skillWorkspaceObserver, String conversationId, boolean skillsOnly) {
        this.appFiles = storage == null ? null : storage.filesDir();
        if (projectId == null || !projectId.matches("[a-f0-9]{24}")) {
            throw new IllegalArgumentException("Workspace id is invalid");
        }
        this.projectId = projectId;
        root = new File(workspacesRoot, projectId);
        this.skillsRoot = skillsRoot;
        this.skillWorkspaceObserver = skillWorkspaceObserver;
        this.conversationId = conversationId;
        this.skillsOnly = skillsOnly;
        this.attachmentsAllowed = !skillsOnly;
        this.delegatedPrivateZonesDenied = false;
        try {
            canonicalRoot = root.getCanonicalPath();
            File absoluteWorkspaces = workspacesRoot.getAbsoluteFile();
            File parent = absoluteWorkspaces.getParentFile();
            if (parent == null) throw new IllegalArgumentException("Workspace root needs a parent");
            expectedAttachmentWorkspaceRoot = new File(
                    new File(parent.getCanonicalFile(), absoluteWorkspaces.getName()), projectId).getPath();
            canonicalSkillsRoot = skillsRoot == null ? null : skillsRoot.getCanonicalPath();
        } catch (Exception error) {
            throw new IllegalStateException("Could not resolve workspace directory", error);
        }
        projectLock = PROJECT_LOCKS.computeIfAbsent(canonicalRoot, ignored -> new Object());
    }

    private WorkspaceStore(WorkspaceStore original, boolean attachmentsAllowed, boolean privateZonesDenied) {
        projectId = original.projectId;
        appFiles = original.appFiles;
        root = original.root;
        canonicalRoot = original.canonicalRoot;
        expectedAttachmentWorkspaceRoot = original.expectedAttachmentWorkspaceRoot;
        projectLock = original.projectLock;
        skillsRoot = privateZonesDenied ? null : original.skillsRoot;
        canonicalSkillsRoot = privateZonesDenied ? null : original.canonicalSkillsRoot;
        skillWorkspaceObserver = privateZonesDenied ? null : original.skillWorkspaceObserver;
        conversationId = original.conversationId;
        skillsOnly = original.skillsOnly;
        this.attachmentsAllowed = attachmentsAllowed && !skillsOnly;
        delegatedPrivateZonesDenied = privateZonesDenied || original.delegatedPrivateZonesDenied;
        ensureDelegatedRoot();
    }

    public WorkspaceStore withoutAttachments() { return new WorkspaceStore(this, false, false); }
    public WorkspaceStore forDelegatedAgent() { return new WorkspaceStore(this, false, true); }

    public static WorkspaceStore forCrewBoard(Context context, String sessionId) {
        Context app = context.getApplicationContext();
        com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(app);
        return new WorkspaceStore(new StorageGeneration(app), new StorageGeneration(app).directory("workspaces"),
                projectIdForSession(sessionId), null, null, sessionId, false).withoutAttachments();
    }

    public boolean isCodingProjectPath(String path) {
        return path != null && (path.equals("/project") || path.startsWith("/project/"));
    }

    public ProjectScope codingProjectScope() throws IOException {
        if (delegatedPrivateZonesDenied || skillsOnly || conversationId == null) {
            throw new IllegalArgumentException("The Coding project namespace is unavailable in this workspace scope");
        }
        if (appFiles == null) {
            throw new IllegalArgumentException("A conversation app-files anchor is required for the Coding project");
        }
        return new ProjectScopeStore(appFiles).open(conversationId);
    }

    private static File workspaceRoot(Context context) {
        Context app = context.getApplicationContext();
        // Register the lazy per-filesDir database bridge for tools that retain only this store's
        // app-files anchor. The bridge opens SQLite only when a background DB method is used.
        com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(app);
        return new StorageGeneration(app).directory("workspaces");
    }

    /** Pins the same ordinary source boundaries used by delivery and bounded HTML capture. */
    static final class DeliverySource {
        final File root;
        final String relative;
        final String provenance;
        DeliverySource(File root, String relative, String provenance) {
            this.root = root; this.relative = relative; this.provenance = provenance;
        }
        com.jarvys.agent.coding.ArtifactSnapshotIO.Snapshot copy(String relative, File target,
                long limit, CancellationToken token) throws IOException {
            HtmlPreviewCapture.requireOrdinaryPath(relative);
            return com.jarvys.agent.coding.ArtifactSnapshotIO.copy(root, relative, target, limit, token);
        }
    }

    DeliverySource deliverySource(String path) throws IOException {
        if (delegatedPrivateZonesDenied || skillsOnly || !attachmentsAllowed)
            throw new IOException("File delivery is available only in the main chat");
        if (isCodingProjectPath(path)) {
            ProjectScope scope = codingProjectScope();
            String relative = scope.normalizePath(path.startsWith("/project/") ? path.substring(9) : "");
            scope.resolve(relative);
            return new DeliverySource(scope.rootDirectory(), relative, "coding:" + scope.durableIdentity());
        }
        synchronized (projectLock) {
            String relative = java.util.Arrays.stream(normalizeRelativePath(path).split("/"))
                    .filter(part -> !part.isEmpty() && !part.equals(".")).collect(java.util.stream.Collectors.joining("/"));
            if (relative.equals("memory") || relative.startsWith("memory/") || relative.equals("skills")
                    || relative.startsWith("skills/") || relative.equals("attachments") || relative.startsWith("attachments/"))
                throw new IOException("Only ordinary workspace files can be delivered");
            if (!root.getCanonicalPath().equals(expectedAttachmentWorkspaceRoot))
                throw new IOException("Workspace root changed");
            return new DeliverySource(new File(expectedAttachmentWorkspaceRoot), relative, "workspace:" + projectId);
        }
    }

    /** Only ordinary files in this conversation can become chat artifacts; no memory/skill mount. */
    com.jarvys.agent.coding.ArtifactSnapshotIO.Snapshot snapshotForDelivery(String path, File target,
            long limit, CancellationToken token) throws IOException {
        DeliverySource source = deliverySource(path);
        return com.jarvys.agent.coding.ArtifactSnapshotIO.copy(source.root, source.relative, target, limit, token);
    }

    File appFilesForDelivery() throws IOException {
        if (delegatedPrivateZonesDenied || skillsOnly || !attachmentsAllowed || conversationId == null)
            throw new IOException("Explicit HTML previews require an owning main conversation");
        if (appFiles == null) throw new IOException("Conversation storage is unavailable");
        return appFiles;
    }

    String deliveryConversationId() { return conversationId; }

    public String codingProjectOwner() {
        if (conversationId == null) throw new IllegalArgumentException("A conversation owner is required");
        return "captain:" + conversationId;
    }

    /** Streams immutable attachment originals into a dedicated zone, without the editable-text size cap. */
    public String importAttachment(ChatAttachment attachment, InputStream input) throws IOException {
        if (input == null) throw new IOException("Attachment input is missing");
        synchronized (projectLock) {
            File temporary = null;
            try {
                String relative;
                File target;
                try (InputStream source = input) {
                    requireFileOperations();
                    if (attachment == null || attachment.kind != ChatAttachment.Kind.FILE) {
                        throw new IllegalArgumentException("Only file attachments belong in the workspace");
                    }
                    relative = "attachments/" + attachment.relativePath;
                    File directory = new File(root, "attachments");
                    target = new File(directory, attachment.relativePath);
                    requireAttachmentImportPath(directory);
                    requireAttachmentImportPath(target);
                    if (target.isFile()) return relative;
                    if (target.exists()) throw new IllegalArgumentException("Attachment destination is not a file");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create workspace attachment directory");
                    requireAttachmentImportPath(directory);
                    temporary = File.createTempFile(".attachment-", ".tmp", directory);
                    requireAttachmentImportPath(temporary);
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        AttachmentStore.copyStream(source, output);
                        output.flush();
                        output.getFD().sync();
                    }
                }
                requireAttachmentImportPath(target);
                if (target.isFile()) return relative;
                if (target.exists() || !temporary.renameTo(target)) throw new IOException("Could not finish workspace attachment import");
                return relative;
            } finally {
                if (temporary != null) {
                    try { requireAttachmentImportPath(temporary); temporary.delete(); }
                    catch (RuntimeException ignored) { }
                }
            }
        }
    }

    public boolean deleteImportedAttachments() {
        synchronized (projectLock) { return deleteImportedAttachmentTree(new File(root, "attachments")); }
    }

    private boolean deleteImportedAttachmentTree(File file) {
        try {
            requireAttachmentImportPath(file);
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) return false;
                boolean allDeleted = true;
                for (File child : children) allDeleted &= deleteImportedAttachmentTree(child);
                if (!allDeleted) return false;
            }
            return !file.exists() || file.delete();
        } catch (RuntimeException ignored) { return false; }
    }

    private void requireAttachmentImportPath(File file) {
        if (!attachmentsAllowed) throw new IllegalArgumentException("Chat attachments are unavailable in this agent scope");
        try {
            String logicalRoot = root.getAbsolutePath();
            String logical = file.getAbsolutePath();
            if (!logical.startsWith(logicalRoot + File.separator + "attachments" + File.separator)
                    && !logical.equals(logicalRoot + File.separator + "attachments")) {
                throw new IllegalArgumentException("Attachment import must stay in workspace attachments/");
            }
            if (!root.getCanonicalPath().equals(expectedAttachmentWorkspaceRoot)
                    || !file.getCanonicalPath().equals(new File(expectedAttachmentWorkspaceRoot,
                    logical.substring(logicalRoot.length() + 1)).getPath())) {
                throw new IllegalArgumentException("Workspace attachment paths cannot use symbolic links");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not resolve workspace attachment path", error);
        }
    }

    public static final class AttachmentTextPage {
        public final boolean hasMore;
        public final int nextOffset;
        public final int offset;
        public final String text;
        AttachmentTextPage(String text, int offset, boolean hasMore) {
            this.text = text;
            this.offset = offset;
            nextOffset = text.length() + offset;
            this.hasMore = hasMore;
        }
    }

    public AttachmentTextPage readAttachmentPage(String path, int offset, int maxChars) {
        return readAttachmentPage(path, offset, maxChars, CancellationToken.uncancellable());
    }

    public AttachmentTextPage readAttachmentPage(String path, int offset, int maxChars, CancellationToken token) {
        if (offset < 0 || maxChars <= 0) throw new IllegalArgumentException("Invalid attachment text page");
        token.throwIfCancelled();
        synchronized (projectLock) {
            token.throwIfCancelled();
            String normalized = normalizePath(path);
            if (skillRelativePath(normalized) != null) return null;
            requireFileOperations();
            File file = resolveExisting(normalized);
            String attachmentRoot = new File(canonicalRoot, "attachments").getPath();
            if (!file.getPath().startsWith(attachmentRoot + File.separator)) return null;
            if (!file.isFile()) throw new IllegalArgumentException("Workspace path is not a file");
            if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw unsupportedAttachmentText();
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                input.mark(5);
                byte[] prefix = new byte[5];
                int prefixSize = 0;
                int value;
                while (prefixSize < prefix.length && (value = input.read()) >= 0) prefix[prefixSize++] = (byte) value;
                input.reset();
                if (prefixSize == 5 && prefix[0] == '%' && prefix[1] == 'P' && prefix[2] == 'D'
                        && prefix[3] == 'F' && prefix[4] == '-') throw unsupportedAttachmentText();
                try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                    return readAttachmentTextPage(reader, offset, maxChars, token);
                }
            } catch (CharacterCodingException unsupported) {
                token.throwIfCancelled();
                throw unsupportedAttachmentText();
            } catch (IOException error) {
                token.throwIfCancelled();
                throw new IllegalStateException("Could not stream attachment text", error);
            }
        }
    }

    static AttachmentTextPage readAttachmentTextPage(Reader reader, int offset, int maxChars,
                                                     CancellationToken token) throws IOException {
        int previous = -1;
        char[] buffer = new char[8192];
        int skipped = 0;
        while (skipped < offset) {
            token.throwIfCancelled();
            int count = reader.read(buffer, 0, Math.min(buffer.length, offset - skipped));
            if (count < 0) throw new IllegalArgumentException("offset is outside the file");
            if (count == 0) {
                previous = reader.read();
                if (previous < 0) throw new IllegalArgumentException("offset is outside the file");
                requireTextCharacter(previous);
                skipped++;
            } else {
                for (int i = 0; i < count; i++) requireTextCharacter(buffer[i]);
                previous = buffer[count - 1];
                skipped += count;
            }
        }
        token.throwIfCancelled();
        int start = offset;
        int current = reader.read();
        requireTextCharacter(current);
        if (current >= 0 && Character.isLowSurrogate((char) current)
                && previous >= 0 && Character.isHighSurrogate((char) previous)) {
            start++;
            current = reader.read();
            requireTextCharacter(current);
        }
        StringBuilder text = new StringBuilder();
        while (current >= 0 && text.length() < maxChars) {
            token.throwIfCancelled();
            text.append((char) current);
            current = reader.read();
            requireTextCharacter(current);
        }
        boolean more = current >= 0;
        if (more && text.length() > 0 && Character.isHighSurrogate(text.charAt(text.length() - 1))
                && Character.isLowSurrogate((char) current)) {
            text.setLength(text.length() - 1);
            if (text.length() == 0) throw new IllegalArgumentException("max_chars must fit a complete Unicode character");
        }
        token.throwIfCancelled();
        return new AttachmentTextPage(text.toString(), start, more);
    }

    private static void requireTextCharacter(int value) {
        if (value >= 0 && ((value < 32 && value != '\n' && value != '\r' && value != '\t' && value != '\f') || value == 127)) {
            throw unsupportedAttachmentText();
        }
    }

    private static IllegalArgumentException unsupportedAttachmentText() {
        return new IllegalArgumentException("This attachment is not supported UTF-8 text. PDF and binary extraction are unavailable; use the local Linux tools if installed.");
    }

    private void requireAttachmentAccess(File file) {
        if (!canAccessAttachmentPath(file)) throw new IllegalArgumentException("Chat attachments are unavailable in this agent scope");
    }

    private boolean canAccessAttachmentPath(File file) {
        try {
            ensureDelegatedRoot();
            if (attachmentsAllowed) return true;
            String logical = file.getAbsoluteFile().toURI().normalize().getPath();
            String logicalAttachments = new File(root, "attachments").getAbsoluteFile().toURI().normalize().getPath();
            String canonical = file.getCanonicalPath();
            String expectedAttachments = new File(canonicalRoot, "attachments").getPath();
            String resolvedAttachments = new File(root, "attachments").getCanonicalPath();
            return !isAtOrUnder(logical, logicalAttachments) && !isAtOrUnder(canonical, expectedAttachments)
                    && !isAtOrUnder(canonical, resolvedAttachments);
        } catch (Exception failure) { return false; }
    }

    private void ensureDelegatedRoot() {
        if (!delegatedPrivateZonesDenied) return;
        try {
            if (!canonicalRoot.equals(expectedAttachmentWorkspaceRoot) || !root.getCanonicalPath().equals(expectedAttachmentWorkspaceRoot)) {
                throw new IllegalArgumentException("Delegated workspace root is aliased or has changed");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not verify delegated workspace root", failure);
        }
    }

    private static boolean isAtOrUnder(String path, String directory) {
        String boundary = directory.endsWith(File.separator) ? directory.substring(0, directory.length() - 1) : directory;
        return path.equals(boundary) || path.startsWith(boundary + File.separator);
    }

    public String projectId() { return projectId; }

    public boolean hasIndexHtml() {
        synchronized (projectLock) {
            try {
                return resolveExisting("index.html").isFile();
            } catch (IllegalArgumentException missing) {
                return false;
            }
        }
    }

    public List<String> list(String relativePath) {
        synchronized (projectLock) { return listLocked(relativePath); }
    }

    private List<String> listLocked(String relativePath) {
        ensureDelegatedRoot();
        boolean projectRoot = isProjectRoot(relativePath);
        String normalized = projectRoot ? "." : normalizePath(relativePath);
        requireFileOperations();
        if (!projectRoot && skillsRoot != null) {
            String skillRelative = skillRelativePath(normalized);
            if (skillRelative != null) return listSkills(skillRelative);
        }
        File directory = projectRoot ? root : resolveExisting(relativePath);
        if (!directory.exists() && projectRoot) directory.mkdirs();
        if (!directory.isDirectory()) throw new IllegalArgumentException("Workspace path is not a directory");
        File[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException("Could not list workspace directory");
        Arrays.sort(children, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        List<String> entries = new ArrayList<>();
        for (File child : children) {
            if (entries.size() >= MAX_LIST_ENTRIES) break;
            if (!isInsideRoot(child)) continue;
            entries.add((child.isDirectory() ? "DIR  " : "FILE ") + child.getName()
                    + (child.isFile() ? " (" + child.length() + " bytes)" : ""));
        }
        Collections.sort(entries, String.CASE_INSENSITIVE_ORDER);
        return entries;
    }

    public String read(String relativePath) {
        synchronized (projectLock) { return readLocked(relativePath); }
    }

    /** Enumerates only files in the requested zones using the same roots and boundaries as ls/read. */
    public List<SearchDocument> searchDocuments(Set<String> zones) {
        synchronized (projectLock) {
            List<SearchDocument> documents = new ArrayList<>();
            if (!java.util.Arrays.asList("workspace", "skills").containsAll(zones))
                throw new IllegalArgumentException("Search zones must be workspace or skills");
            if (skillsOnly && zones.contains("workspace"))
                throw new IllegalArgumentException("Scheduled tasks can search only installed skills");
            if (zones.contains("workspace")) collectSearchFiles(root, canonicalRoot, "workspace", "", documents, false);
            if (zones.contains("skills") && skillsRoot != null && skillsRoot.isDirectory()) {
                File[] skillDirectories = skillsRoot.listFiles();
                if (skillDirectories != null) {
                    Arrays.sort(skillDirectories, (left, right) -> left.getName().compareTo(right.getName()));
                    for (File skill : skillDirectories) {
                        if (!SKILL_ID_PATTERN.matcher(skill.getName()).matches() || !skill.isDirectory()
                                || !isExpectedSkillDirectory(skill.getName(), skill)) continue;
                        collectSearchFiles(skill, new File(canonicalSkillsRoot, skill.getName()).getPath(),
                                "skills", skill.getName() + "/", documents, true);
                    }
                }
            }
            documents.sort(java.util.Comparator.comparing(SearchDocument::getZone)
                    .thenComparing(SearchDocument::getPath));
            return Collections.unmodifiableList(documents);
        }
    }

    public SearchDocument searchDocumentForPath(String path) {
        synchronized (projectLock) {
            String normalized = normalizePath(path);
            String skillRelative = skillRelativePath(normalized);
            if (skillRelative != null) {
                if (skillRelative.isEmpty()) throw new IllegalArgumentException("Skill path is not a file");
                File file = resolveSkillPath(skillRelative, true);
                if (!file.isFile() || file.length() > MAX_FILE_BYTES) return null;
                String text = readSearchText(file);
                return text == null ? null : new SearchDocument("skills", skillRelative, text, file.lastModified(), null);
            }
            File file = resolveExisting(path);
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("Workspace path is not a searchable file");
            String text = readSearchText(file);
            return text == null ? null : new SearchDocument("workspace",
                    file.getPath().substring(canonicalRoot.length() + 1).replace(File.separatorChar, '/'),
                    text, file.lastModified(), null);
        }
    }

    private void collectSearchFiles(File start, String boundary, String zone, String prefix,
                                    List<SearchDocument> output, boolean skipHidden) {
        java.util.ArrayDeque<File> pending = new java.util.ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            File directory = pending.removeFirst();
            File[] children = directory.listFiles();
            if (children == null) continue;
            Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
            for (File child : children) {
                if (skipHidden && child.getName().startsWith(".")) continue;
                if (!canAccessAttachmentPath(child)) continue;
                String canonical;
                try { canonical = child.getCanonicalPath(); }
                catch (Exception ignored) { continue; }
                if (!canonical.startsWith(boundary + File.separator) || !visited.add(canonical)) continue;
                if (child.isDirectory()) {
                    pending.addLast(child);
                } else if (child.isFile() && child.length() <= MAX_FILE_BYTES) {
                    try {
                        String text = readSearchText(child);
                        if (text != null) output.add(new SearchDocument(zone,
                                prefix + canonical.substring(boundary.length() + 1).replace(File.separatorChar, '/'),
                                text, child.lastModified(), null));
                    } catch (RuntimeException ignored) { }
                }
            }
        }
    }

    private static String readSearchText(File file) {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_FILE_BYTES) return null;
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            for (byte value : bytes) if (value == 0) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) { return null; }
    }

    private String readLocked(String relativePath) {
        String normalized = normalizePath(relativePath);
        requireFileOperations();
        if (skillsRoot != null) {
            String skillRelative = skillRelativePath(normalized);
            if (skillRelative != null) return readSkillFile(skillRelative);
        }
        File file = resolveExisting(relativePath);
        if (!file.isFile()) throw new IllegalArgumentException("Workspace path is not a file");
        if (file.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Could not read workspace file", error);
        }
    }

    public void write(String relativePath, String content) {
        write(relativePath, content, null);
    }

    public void write(String relativePath, String content, CancellationToken token) {
        synchronized (projectLock) { writeLocked(relativePath, content, token); }
    }

    private void writeLocked(String relativePath, String content, CancellationToken token) {
        String normalized = normalizePath(relativePath);
        requireFileOperations();
        if (token != null) token.throwIfCancelled();
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB");
        if (skillsRoot != null) {
            String skillRelative = skillRelativePath(normalized);
            if (skillRelative != null) {
                writeSkillFile(skillRelative, bytes);
                return;
            }
        }
        File target = resolveForWrite(normalized);
        long existingLength = target.isFile() ? target.length() : 0L;
        if (workspaceBytes() - existingLength + bytes.length > MAX_WORKSPACE_BYTES) {
            throw new IllegalArgumentException("Workspace exceeds 16 MiB");
        }
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(bytes);
            output.flush();
        } catch (Exception error) {
            throw new IllegalStateException("Could not write workspace file", error);
        }
        return;
    }

    public String edit(String relativePath, String oldText, String newText) {
        return edit(relativePath, oldText, newText, null);
    }

    public String edit(String relativePath, String oldText, String newText, CancellationToken token) {
        synchronized (projectLock) { return editLocked(relativePath, oldText, newText, token); }
    }

    private String editLocked(String relativePath, String oldText, String newText, CancellationToken token) {
        if (oldText == null || oldText.isEmpty()) throw new IllegalArgumentException("old_text must not be empty");
        String normalizedPath = normalizePath(relativePath);
        requireFileOperations();
        if (token != null) token.throwIfCancelled();
        String current = read(relativePath);
        int first = current.indexOf(oldText);
        if (first < 0) throw new IllegalArgumentException("old_text was not found in the file");
        if (current.indexOf(oldText, first + oldText.length()) >= 0) {
            throw new IllegalArgumentException("old_text matches more than once; provide a unique edit");
        }
        String replacement = newText == null ? "" : newText;
        write(relativePath, current.substring(0, first) + replacement + current.substring(first + oldText.length()), token);
        String normalized = normalizePath(relativePath);
        return "Updated " + normalizeRelativePath(relativePath);
    }

    /** Resolves only an existing regular file/directory under this workspace for preview reads. */
    public File resolvePreviewPath(String relativePath) {
        File file = resolveExisting(relativePath);
        if (!file.isFile()) throw new IllegalArgumentException("Preview path is not a file");
        return file;
    }

    public static String mimeType(String relativePath) {
        String name = relativePath.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".avif")) return "image/avif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".otf")) return "font/otf";
        return "application/octet-stream";
    }

    private static boolean isProjectRoot(String path) {
        return path == null || path.trim().isEmpty() || ".".equals(path.trim());
    }

    /** Pure lexical rejection, also used before a tool admits any filesystem mutation. */
    static void rejectRemovedMemoryNamespace(String raw) {
        if (raw != null && (raw.trim().equals("/memory") || raw.trim().startsWith("/memory/")))
            throw new IllegalArgumentException("The /memory/ filesystem namespace is not available");
    }

    private String normalizePath(String raw) {
        rejectRemovedMemoryNamespace(raw);
        ensureDelegatedRoot();
        if (skillsRoot != null && raw != null) {
            String path = raw.trim();
            if (SKILLS_ZONE.equals(path)) return normalizeRelativePath(path);
            if (("/" + SKILLS_ZONE).equals(path)) return "/" + SKILLS_ZONE;
            if (path.startsWith("/" + SKILLS_ZONE + "/")) {
                return "/" + normalizeRelativePath(path.substring(1));
            }
        }
        return normalizeRelativePath(raw);
    }

    public boolean skillsOnly() { return skillsOnly; }
    public boolean skillsEnabled() { return skillsRoot != null; }
    public boolean attachmentsEnabled() { return attachmentsAllowed; }

    private void requireFileOperations() {
        if (skillsOnly) throw new IllegalArgumentException("Scheduled tasks can only search installed skills");
    }


    /** Returns null for ordinary workspace paths, or a validated path below the skills zone. */
    private String skillRelativePath(String normalized) {
        if (skillsRoot == null) return null;
        if (("/" + SKILLS_ZONE).equals(normalized) || ("/" + SKILLS_ZONE + "/").equals(normalized)) return "";
        if (!normalized.startsWith("/" + SKILLS_ZONE + "/")) return null;
        String relative = normalized.substring(SKILLS_ZONE.length() + 2);
        while (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
        if (relative.isEmpty()) return "";
        String[] segments = relative.split("/", -1);
        if (!SKILL_ID_PATTERN.matcher(segments[0]).matches()) {
            throw new IllegalArgumentException("Skill directory must use a valid skill id");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.indexOf('\0') >= 0 || segment.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("Skill paths must be safe relative POSIX paths");
            }
        }
        return relative;
    }

    private List<String> listSkills(String relative) {
        File directory = relative.isEmpty() ? skillsRoot : resolveSkillPath(relative, true);
        if (!directory.exists() && relative.isEmpty()) return Collections.emptyList();
        if (!directory.isDirectory()) throw new IllegalArgumentException("Skill path is not a directory");
        File[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException("Could not list skills directory");
        Arrays.sort(children, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        List<String> entries = new ArrayList<>();
        for (File child : children) {
            if (entries.size() >= MAX_LIST_ENTRIES || child.getName().startsWith(".") || !canAccessAttachmentPath(child)) continue;
            if (relative.isEmpty()) {
                if (!SKILL_ID_PATTERN.matcher(child.getName()).matches() || !child.isDirectory()) continue;
                if (!isExpectedSkillDirectory(child.getName(), child)) continue;
            } else if (!isInsideDirectory(directory, child)) {
                continue;
            }
            entries.add((child.isDirectory() ? "DIR  " : "FILE ") + child.getName()
                    + (child.isFile() ? " (" + child.length() + " bytes)" : ""));
        }
        return entries;
    }

    private String readSkillFile(String relative) {
        if (relative.isEmpty()) throw new IllegalArgumentException("Skill path is not a file");
        File file = resolveSkillPath(relative, true);
        if (!file.isFile()) throw new IllegalArgumentException("Skill path is not a file");
        if (file.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Could not read skill file", error);
        }
    }

    private void writeSkillFile(String relative, byte[] bytes) {
        int separator = relative.indexOf('/');
        if (separator <= 0 || separator == relative.length() - 1) {
            throw new IllegalArgumentException("Skill files must be inside skills/<id>/");
        }
        String skillId = relative.substring(0, separator);
        String skillFilePath = relative.substring(separator + 1);
        if (skillWorkspaceObserver == null) throw new IllegalStateException("Skill writer is unavailable");
        String markdown = "SKILL.md".equals(skillFilePath) ? new String(bytes, StandardCharsets.UTF_8) : null;
        skillWorkspaceObserver.commitSkillWorkspaceWrite(skillId, markdown, () -> {
            File target = resolveSkillPath(relative, false);
            File skillDirectory = new File(canonicalSkillsRoot, skillId);
            if (!skillsRoot.isDirectory() && !skillsRoot.mkdirs()) {
                throw new IllegalStateException("Could not create skills storage");
            }
            if (!skillDirectory.isDirectory() && !skillDirectory.mkdirs()) {
                throw new IllegalStateException("Could not create skill directory");
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create skill file directory");
            }
            target = resolveSkillPath(relative, false);
            if (target.exists() && !target.isFile()) throw new IllegalArgumentException("Skill path is not a regular file");
            long existingLength = target.isFile() ? target.length() : 0L;
            if (directoryBytes(skillsRoot, canonicalSkillsRoot) - existingLength + bytes.length > MAX_WORKSPACE_BYTES) {
                throw new IllegalArgumentException("Skills storage exceeds 16 MiB");
            }
            File temporary = null;
            try {
                temporary = File.createTempFile(".jvskill-", ".tmp", target.getParentFile());
                try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                    output.write(bytes);
                    output.flush();
                }
                if (!temporary.renameTo(target)) throw new IllegalStateException("Could not finish writing skill file");
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException("Could not write skill file", error);
            } finally {
                if (temporary != null) temporary.delete();
            }
        });
    }

    private File resolveSkillPath(String relative, boolean mustExist) {
        if (relative.isEmpty()) return skillsRoot;
        String[] segments = relative.split("/", -1);
        String skillId = segments[0];
        if (!SKILL_ID_PATTERN.matcher(skillId).matches()) throw new IllegalArgumentException("Skill id is invalid");
        File skillDirectory = new File(canonicalSkillsRoot, skillId);
        try {
            if (skillDirectory.exists() && !skillDirectory.getCanonicalPath().equals(skillDirectory.getPath())) {
                throw new IllegalArgumentException("Skill directory cannot be a symbolic link");
            }
            String suffix = relative.length() == skillId.length() ? "" : relative.substring(skillId.length() + 1);
            File target = new File(skillDirectory, suffix).getCanonicalFile();
            requireAttachmentAccess(target);
            if (!isInsideDirectory(skillDirectory, target)) {
                throw new IllegalArgumentException("Skill path escapes its skill directory");
            }
            if (mustExist && !target.exists()) throw new IllegalArgumentException("Skill path does not exist");
            return target;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Skill path is invalid", error);
        }
    }

    private boolean isExpectedSkillDirectory(String id, File directory) {
        try {
            return directory.getCanonicalPath().equals(new File(canonicalSkillsRoot, id).getPath());
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean isInsideDirectory(File directory, File candidate) {
        try {
            String rootPath = directory.getCanonicalPath();
            String candidatePath = candidate.getCanonicalPath();
            return candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + File.separator);
        } catch (Exception error) {
            return false;
        }
    }

    private static long directoryBytes(File directory, String canonicalBoundary) {
        return directoryBytes(directory, canonicalBoundary, new HashSet<>());
    }

    private static long directoryBytes(File directory, String canonicalBoundary, Set<String> visited) {
        try {
            String canonicalDirectory = directory.getCanonicalPath();
            if (!canonicalDirectory.startsWith(canonicalBoundary + File.separator)
                    && !canonicalDirectory.equals(canonicalBoundary)) return 0L;
            if (!visited.add(canonicalDirectory)) return 0L;
        } catch (Exception error) {
            return 0L;
        }
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            try {
                String path = child.getCanonicalPath();
                if (!path.startsWith(canonicalBoundary + File.separator)) continue;
                total += child.isDirectory() ? directoryBytes(child, canonicalBoundary, visited) : child.length();
                if (total > MAX_WORKSPACE_BYTES) return total;
            } catch (Exception ignored) { }
        }
        return total;
    }

    public String skillLinkForPath(String path) {
        if (skillsRoot == null) return null;
        String normalized = normalizePath(path);
        String relative = skillRelativePath(normalized);
        if (relative == null) return null;
        int separator = relative.indexOf('/');
        if (separator <= 0 || separator != relative.lastIndexOf('/') || !relative.endsWith("/SKILL.md")) return null;
        return "jarvys://skills/" + relative.replace("\\", "/").replace(" ", "%20");
    }

    private File resolveExisting(String relativePath) {
        requireFileOperations();
        String normalized = normalizeRelativePath(relativePath);
        requireAttachmentAccess(new File(root, normalized));
        File file;
        try {
            file = new File(root, normalized).getCanonicalFile();
        } catch (Exception error) {
            throw new IllegalArgumentException("Workspace path is invalid", error);
        }
        if (!isInsideRoot(file)) throw new IllegalArgumentException("Workspace path escapes the project root");
        if (!file.exists()) throw new IllegalArgumentException("Workspace path does not exist");
        return file;
    }

    private File resolveForWrite(String relativePath) {
        requireFileOperations();
        String normalized = normalizeRelativePath(relativePath);
        requireAttachmentAccess(new File(root, normalized));
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Could not create workspace directory");
        File file;
        try {
            file = new File(root, normalized).getCanonicalFile();
        } catch (Exception error) {
            throw new IllegalArgumentException("Workspace path is invalid", error);
        }
        if (!isInsideRoot(file)) throw new IllegalArgumentException("Workspace path escapes the project root");
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create workspace parent directory");
            }
            try {
                file = file.getCanonicalFile();
            } catch (Exception error) {
                throw new IllegalArgumentException("Workspace path is invalid", error);
            }
            if (!isInsideRoot(file)) throw new IllegalArgumentException("Workspace path escapes the project root");
        }
        return file;
    }

    private boolean isInsideRoot(File file) {
        if (!canAccessAttachmentPath(file)) return false;
        try {
            String path = file.getCanonicalPath();
            return path.startsWith(canonicalRoot + File.separator);
        } catch (Exception error) {
            return false;
        }
    }

    private static String normalizeRelativePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("A relative workspace path is required");
        String path = raw.trim();
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")
                || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Workspace paths must be relative POSIX paths");
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) throw new IllegalArgumentException("Parent path segments are not allowed");
        }
        return path;
    }

    private long workspaceBytes() {
        return workspaceBytes(root);
    }

    private long workspaceBytes(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            if (!isInsideRoot(child)) continue;
            total += child.isDirectory() ? workspaceBytes(child) : child.length();
            if (total > MAX_WORKSPACE_BYTES) return total;
        }
        return total;
    }
}
