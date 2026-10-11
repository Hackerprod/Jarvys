package com.jarvys.agent.apkfactory

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import org.json.JSONObject

/** One user-controlled policy for Factory operations. Authentication stays with each broker.
 * Preferences identify an app certificate (or a new-key project), never an untrusted Intent label.
 * A grant additionally binds one immutable request and cannot survive a relevant policy revision.
 */
internal class FactoryOperationPolicy internal constructor(private val store: Store) {
    enum class Mode { ALLOW_ALWAYS, ASK, DENY }
    enum class Operation(val id: String) {
        DOCUMENT_OPEN("documents.open"), DOCUMENT_CREATE("documents.create"),
        PHOTO_PICK("photos.pick"), PHOTO_CAPTURE("photos.capture"), AUDIO_PLAY("audio.play"),
        BROWSER_OPEN("browser.open"), CONTACT_PICK("contacts.pick"), FILE_SHARE("share.file"),
        MAPS_OPEN("maps.open"), PHONE_DIAL("phone.dial"), EMAIL_COMPOSE("email.compose"),
        SMS_COMPOSE("sms.compose"), CALENDAR_INSERT("calendar.insert"),
        DATABASE_BACKUP_EXPORT("database.backupExport"), DATABASE_BACKUP_IMPORT("database.backupImport"),
        STORAGE_BACKUP_EXPORT("storage.backupExport"), STORAGE_BACKUP_IMPORT("storage.backupImport"),
        SIGN("factory.sign"), INSTALL("factory.install"), CLIPBOARD_WRITE("clipboard.write"),
        SHARE_TEXT("share.text"), EXPORT_TEXT("export.text"),
        NETWORK_REQUEST("network.request"), NETWORK_PROBE("network.probe"), NETWORK_PING("network.ping"),
        NOTIFICATIONS_CHANNEL("notifications.channel"), NOTIFICATIONS_PERMISSION("notifications.requestPermission"),
        NOTIFICATIONS_SHOW("notifications.show"), NOTIFICATIONS_CANCEL("notifications.cancel"),
        SPEECH_VOICES("speech.voices"), SPEECH_SPEAK("speech.speak"), SPEECH_STOP("speech.stop"),
        VOICE_RECOGNITION_RECOGNIZE("voiceRecognition.recognize"), LOCATION_CURRENT("location.current"), LOCATION_REQUEST_PERMISSION("location.requestPermission")
    }
    data class Subject private constructor(val kind: String, val appId: String, val identity: String) {
        val key: String get() = binding(kind, appId, identity)
        val certificate: String? get() = identity.takeIf { kind == APP }
        companion object {
            private const val APP = "app_certificate"
            private const val BOOTSTRAP = "new_identity_project"
            fun installed(proof: FactoryDocumentCoordinator.Proof) = signedArtifact(proof.appId, proof.certificate)
            fun signedArtifact(appId: String, certificate: String) = restored(APP, appId, certificate)
            fun bootstrap(appId: String, projectIdentitySha: String) = restored(BOOTSTRAP, appId, projectIdentitySha)
            internal fun restored(kind: String, appId: String, identity: String): Subject {
                require(kind == APP || kind == BOOTSTRAP)
                FactoryIdentityBackup.validateAppId(appId)
                require(identity.matches(Regex("[a-f0-9]{64}"))) { "Factory policy requires an exact identity" }
                return Subject(kind, appId, identity)
            }
        }
    }
    private data class Dependency(val subject: Subject, val subjectRevision: Long, val entryRevision: Long, val defaultRevision: Long, val mode: Mode)
    internal data class Entry(val mode: Mode?, val revision: Long)
    internal data class Observed(val subject: Subject, val revision: Long = 0)
    internal data class BootstrapOwner(val subjectKey: String, val certificate: String? = null)
    internal data class State(
        val defaultMode: Mode = Mode.ALLOW_ALWAYS,
        val defaultRevision: Long = 0,
        val subjects: Map<String, Observed> = emptyMap(),
        val entries: Map<String, Entry> = emptyMap(),
        val bootstrapOwners: Map<String, BootstrapOwner> = emptyMap(),
    )
    internal interface Store { fun read(): State; fun write(state: State) }
    private val lock = Any()
    private var state: State = store.read()
    private var healthy = true
    private val active = linkedSetOf<Grant>()

    inner class Grant internal constructor(
        val subject: Subject, val operation: Operation, val requestBinding: String,
        val mode: Mode, private val subjectRevision: Long, private val entryRevision: Long,
        private val defaultRevision: Long,
    ) : AutoCloseable {
        @Volatile private var approved = mode == Mode.ALLOW_ALWAYS
        @Volatile private var ended = false
        @Volatile private var revoked = false
        private val listeners = linkedSetOf<() -> Unit>()
        private val dependencies = linkedMapOf<String, Dependency>()
        private var dependencyRejected = false
        val requiresApproval: Boolean get() = mode == Mode.ASK && !approved && !ended
        /** This checks current authority without turning ASK into an approval. */
        fun requireCurrent() = synchronized(lock) { checkCurrent() }
        fun approveOnce(humanCheck: () -> Unit) = synchronized(lock) {
            checkCurrent()
            if (!approved) { humanCheck(); checkCurrent(); approved = true }
        }
        fun checkBeforeEffect() = synchronized(lock) {
            checkCurrent(); check(approved) { "Factory operation is waiting for its configured approval" }
        }
        fun onRevoked(callback: () -> Unit): AutoCloseable {
            val notify = synchronized(lock) {
                if (revoked) true else { if (!ended) listeners += callback; false }
            }
            if (notify) runCatching(callback)
            return AutoCloseable { synchronized(lock) { listeners -= callback } }
        }
        fun revoke() { notifyCallbacks(synchronized(lock) { end(true) }) }
        override fun close() { synchronized(lock) { end(false) } }
        private fun checkCurrent() {
            check(healthy) { "Factory permissions could not be persisted; no new effects are authorized" }
            check(!ended && !revoked && !dependencyRejected) { "Factory operation authority ended" }
            val observed = state.subjects[subject.key]
            val entry = state.entries[entryKey(subject, operation)]
            check(observed?.subject == subject && observed.revision == subjectRevision &&
                (entry?.revision ?: 0) == entryRevision &&
                (defaultRevision < 0 || state.defaultRevision == defaultRevision) &&
                effective(subject, operation) == mode) { "Factory permission changed; this request is no longer authorized" }
            dependencies.values.forEach { dependency ->
                val observedDependency = state.subjects[dependency.subject.key]
                val entryDependency = state.entries[entryKey(dependency.subject, operation)]
                check(observedDependency?.revision == dependency.subjectRevision &&
                    (entryDependency?.revision ?: 0) == dependency.entryRevision &&
                    (dependency.defaultRevision < 0 || state.defaultRevision == dependency.defaultRevision) &&
                    effective(dependency.subject, operation) == dependency.mode) { "Factory certificate policy changed" }
            }
        }
        internal fun bindDependency(subject: Subject) {
            val entry = state.entries[entryKey(subject, operation)]
            val effective = effective(subject, operation)
            if (effective == Mode.DENY || (effective == Mode.ASK && mode != Mode.ASK)) { dependencyRejected = true; return }
            dependencies[subject.key] = Dependency(subject, state.subjects.getValue(subject.key).revision,
                entry?.revision ?: 0, if (entry?.mode == null) state.defaultRevision else -1, effective)
        }
        private fun isCurrent(): Boolean = runCatching { checkCurrent(); true }.getOrDefault(false)
        private fun end(revoking: Boolean): List<() -> Unit> {
            if (ended) return emptyList()
            ended = true; revoked = revoking; active -= this
            return listeners.toList().also { listeners.clear() }.takeIf { revoking } ?: emptyList()
        }
        internal fun invalidateIfNeeded(): List<() -> Unit> = if (!isCurrent()) end(true) else emptyList()
    }

    fun defaultMode(): Mode = synchronized(lock) { state.defaultMode }
    fun subjects(): List<Subject> = synchronized(lock) { state.subjects.values.map { it.subject }.sortedWith(compareBy({ it.appId }, { it.kind }, { it.identity })) }
    fun mode(subject: Subject, operation: Operation): Mode = synchronized(lock) { effective(subject, operation) }
    fun overrideMode(subject: Subject, operation: Operation): Mode? = synchronized(lock) { state.entries[entryKey(subject, operation)]?.mode }
    fun isHealthy(): Boolean = synchronized(lock) { healthy }
    private fun effective(subject: Subject, operation: Operation) = state.entries[entryKey(subject, operation)]?.mode ?: state.defaultMode

    /** Call only after authenticating the subject. DENY never creates a grant or broker resource. */
    fun acquire(subject: Subject, operation: Operation, requestBinding: String): Grant {
        require(requestBinding.matches(Regex("[a-f0-9]{64}"))) { "An exact request binding is required" }
        var failure: Throwable? = null
        var result: Grant? = null
        val callbacks = synchronized(lock) {
            try {
                check(healthy) { "Factory permissions are unavailable" }
                if (subject.key !in state.subjects) persist(state.copy(subjects = state.subjects + (subject.key to Observed(subject))))
                val mode = effective(subject, operation)
                check(mode != Mode.DENY) { "Factory operation ${operation.id} is denied by your saved policy" }
                val entry = state.entries[entryKey(subject, operation)]
                result = Grant(subject, operation, requestBinding, mode, state.subjects.getValue(subject.key).revision,
                    entry?.revision ?: 0, if (entry?.mode == null) state.defaultRevision else -1).also { active += it }
            } catch (error: Throwable) { failure = error }
            active.toList().flatMap { it.invalidateIfNeeded() }
        }
        notifyCallbacks(callbacks)
        failure?.let { throw it }
        return checkNotNull(result)
    }
    fun setDefaultMode(mode: Mode) = update { previous ->
        previous.copy(defaultMode = mode, defaultRevision = Math.addExact(previous.defaultRevision, 1))
    }
    /** null means inherit; its revision is retained so removing an override still revokes stale grants. */
    fun setMode(subject: Subject, operation: Operation, mode: Mode?) = update { previous ->
        val key = entryKey(subject, operation)
        previous.copy(subjects = previous.subjects + (subject.key to (previous.subjects[subject.key] ?: Observed(subject))),
            entries = previous.entries + (key to Entry(mode, Math.addExact(previous.entries[key]?.revision ?: 0, 1))))
    }
    /** Revokes current requests without silently changing the policy for future requests. */
    fun revokeActive(subject: Subject? = null) = update { previous ->
        previous.copy(subjects = previous.subjects.mapValues { (_, observed) ->
            if (subject == null || observed.subject == subject) observed.copy(revision = Math.addExact(observed.revision, 1)) else observed
        })
    }
    /** Persist this association before creating a new key, so a crash cannot lose explicit choices. */
    fun prepareBootstrapBinding(bootstrap: Subject) = update { previous ->
        require(bootstrap.kind == "new_identity_project")
        check(previous.bootstrapOwners[bootstrap.appId]?.certificate == null) { "This signing identity is already bound to its certificate" }
        previous.copy(subjects = previous.subjects + (bootstrap.key to (previous.subjects[bootstrap.key] ?: Observed(bootstrap))),
            bootstrapOwners = previous.bootstrapOwners + (bootstrap.appId to BootstrapOwner(bootstrap.key)))
    }
    /** Preserve explicit new-key choices when its actual certificate first becomes known. */
    fun bindBootstrap(bootstrap: Subject, signed: Subject) = update(afterPersist = {
        active.toList().filter { it.subject.kind == "new_identity_project" && it.subject.appId == signed.appId }
            .forEach { it.bindDependency(signed) }
    }) { previous ->
        require(bootstrap.kind == "new_identity_project" && signed.certificate != null && bootstrap.appId == signed.appId)
        val recorded = previous.bootstrapOwners[signed.appId]
        val source = if (recorded != null && (recorded.certificate == null || recorded.certificate == signed.certificate))
            previous.subjects.getValue(recorded.subjectKey).subject else bootstrap.takeIf { recorded == null }
        val entries = previous.entries.toMutableMap()
        Operation.entries.forEach { operation ->
            val old = source?.let { entries[entryKey(it, operation)] }
            val target = entryKey(signed, operation)
            if (old?.mode != null && target !in entries) {
                entries[target] = Entry(old.mode, Math.addExact(entries[target]?.revision ?: 0, 1))
            }
        }
        previous.copy(subjects = previous.subjects + (signed.key to (previous.subjects[signed.key] ?: Observed(signed))), entries = entries,
            bootstrapOwners = if (recorded != null && recorded.certificate == null)
                previous.bootstrapOwners + (signed.appId to recorded.copy(certificate = signed.certificate)) else previous.bootstrapOwners)
    }
    private fun update(afterPersist: () -> Unit = {}, change: (State) -> State) {
        var failure: Throwable? = null
        val callbacks = synchronized(lock) {
            try { val next = change(state); if (next != state || !healthy) persist(next); afterPersist() } catch (error: Throwable) { failure = error }
            active.toList().flatMap { it.invalidateIfNeeded() }
        }
        notifyCallbacks(callbacks)
        failure?.let { throw it }
    }
    private fun persist(next: State) {
        try { store.write(next); state = next; healthy = true }
        catch (failure: Throwable) { healthy = false; throw failure }
    }
    private fun notifyCallbacks(callbacks: List<() -> Unit>) { callbacks.forEach { runCatching(it) } }
    private fun entryKey(subject: Subject, operation: Operation) = "${subject.key}:${operation.name}"

    private class MemoryStore(initial: Mode) : Store {
        private var state = State(defaultMode = initial)
        override fun read() = state
        override fun write(state: State) { this.state = state }
    }
    private class FileStore(context: Context) : Store {
        private val file = AtomicFile(File(context.noBackupFilesDir, "factory-operation-policy/policy.json"))
        override fun read(): State {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return State()
            val bytes = file.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0 && output.size() + n <= MAX_POLICY_BYTES); output.write(buffer, 0, n) }
                output.toByteArray()
            }
            val root = FactoryJson.objectFrom(bytes, MAX_POLICY_BYTES)
            require(root.keys().asSequence().toSet() == setOf("schemaVersion", "defaultMode", "defaultRevision", "subjects", "entries", "bootstrapOwners"))
            require(root.get("schemaVersion") == 1)
            fun revision(value: JSONObject, name: String): Long {
                val number = value.get(name); require(number is Int || number is Long)
                return (number as Number).toLong().also { require(it >= 0) }
            }
            val subjects = linkedMapOf<String, Observed>()
            val savedSubjects = root.getJSONObject("subjects")
            for (key in savedSubjects.keys()) {
                val value = savedSubjects.getJSONObject(key)
                require(value.keys().asSequence().toSet() == setOf("kind", "appId", "identity", "revision"))
                val subject = Subject.restored(value.getString("kind"), value.getString("appId"), value.getString("identity"))
                require(subject.key == key); subjects[key] = Observed(subject, revision(value, "revision"))
            }
            val entries = linkedMapOf<String, Entry>()
            val savedEntries = root.getJSONObject("entries")
            for (key in savedEntries.keys()) {
                val parts = key.split(':'); require(parts.size == 2 && parts[0] in subjects)
                Operation.valueOf(parts[1])
                val value = savedEntries.getJSONObject(key)
                require(value.keys().asSequence().toSet() == setOf("mode", "revision"))
                entries[key] = Entry(if (value.isNull("mode")) null else Mode.valueOf(value.getString("mode")), revision(value, "revision"))
            }
            val owners = linkedMapOf<String, BootstrapOwner>()
            val savedOwners = root.getJSONObject("bootstrapOwners")
            for (appId in savedOwners.keys()) {
                val value = savedOwners.getJSONObject(appId)
                require(value.keys().asSequence().toSet() == setOf("subjectKey", "certificate"))
                val subjectKey = value.getString("subjectKey")
                val subject = subjects.getValue(subjectKey).subject
                require(subject.kind == "new_identity_project" && subject.appId == appId)
                val certificate = if (value.isNull("certificate")) null else value.getString("certificate")
                require(certificate == null || certificate.matches(Regex("[a-f0-9]{64}")))
                owners[appId] = BootstrapOwner(subjectKey, certificate)
            }
            return State(Mode.valueOf(root.getString("defaultMode")), revision(root, "defaultRevision"), subjects, entries, owners)
        }
        override fun write(state: State) {
            val subjects = JSONObject(); state.subjects.toSortedMap().forEach { (key, value) ->
                subjects.put(key, JSONObject().put("kind", value.subject.kind).put("appId", value.subject.appId)
                    .put("identity", value.subject.identity).put("revision", value.revision))
            }
            val entries = JSONObject(); state.entries.toSortedMap().forEach { (key, value) ->
                entries.put(key, JSONObject().put("mode", value.mode?.name ?: JSONObject.NULL).put("revision", value.revision))
            }
            val owners = JSONObject(); state.bootstrapOwners.toSortedMap().forEach { (appId, owner) ->
                owners.put(appId, JSONObject().put("subjectKey", owner.subjectKey).put("certificate", owner.certificate ?: JSONObject.NULL))
            }
            val bytes = JSONObject().put("schemaVersion", 1).put("defaultMode", state.defaultMode.name)
                .put("defaultRevision", state.defaultRevision).put("subjects", subjects).put("entries", entries).put("bootstrapOwners", owners).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_POLICY_BYTES) { "Factory permission metadata exceeds its storage budget" }
            check(file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val stream = file.startWrite()
            try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
            catch (failure: Throwable) { file.failWrite(stream); throw failure }
            check(file.readFully().contentEquals(bytes)) { "Factory permission persistence could not be verified" }
        }
    }
    companion object {
        private const val MAX_POLICY_BYTES = 1024 * 1024
        private val instances = WeakHashMap<Context, FactoryOperationPolicy>()
        fun get(context: Context): FactoryOperationPolicy = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) { FactoryOperationPolicy(FileStore(app)) }
        }
        fun inMemory(defaultMode: Mode = Mode.ALLOW_ALWAYS) = FactoryOperationPolicy(MemoryStore(defaultMode))
        internal fun persisted(context: Context) = FactoryOperationPolicy(FileStore(context.applicationContext))
        fun binding(vararg values: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (value in values) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
