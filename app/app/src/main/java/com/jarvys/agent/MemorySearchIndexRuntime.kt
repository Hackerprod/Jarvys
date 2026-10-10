package com.jarvys.agent

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Coordinates private persistence, source mutation events, and off-thread reconstruction. */
class MemorySearchIndexCoordinator internal constructor(
    private val index: MemorySearchIndex,
    private val memoryAllowed: Boolean,
) {
    private val generation = AtomicLong(0L)
    private val rebuilding = AtomicBoolean(false)
    private val needsRebuild = AtomicBoolean(true)
    private val rebuildLock = Any()
    @Volatile private var lastWorkspace: WorkspaceStore? = null
    @Volatile private var lastRebuildThread: String? = null
    @Volatile private var lastRebuildFinished = false

    init {
        if (memoryAllowed) registerMemoryIndex(this)
    }

    fun search(workspace: WorkspaceStore, query: String, zones: Set<String>, limit: Int?): List<SearchHit> {
        lastWorkspace = workspace
        val nonMemoryZones = zones - "memory"
        val localDocuments = if (!index.ready || needsRebuild.get()) {
            scheduleRebuild(workspace, availableZones(workspace) - "memory")
            workspace.searchDocuments(nonMemoryZones)
        } else index.snapshot().filter { it.zone in nonMemoryZones }
        // Memory permissions are checked against current source bytes on EVERY query. A persisted
        // index, in-flight rebuild or late callback can never restore a revoked or foreign grant.
        val memoryDocuments = if ("memory" in zones && memoryAllowed && workspace.memoryEnabled())
            workspace.searchDocuments(setOf("memory")) else emptyList()
        return Bm25SearchRanker().rank(query, localDocuments + memoryDocuments, limit)
    }

    fun refreshZone(workspace: WorkspaceStore, zone: String) {
        if (zone == "memory") return // Memory is always read from the current scoped source.
        generation.incrementAndGet()
        index.replaceZone(zone, workspace.searchDocuments(setOf(zone)))
    }

    fun refreshDocument(workspace: WorkspaceStore, suppliedPath: String) {
        val normalized = suppliedPath.trim().removeSuffix("/")
        val zone: String
        val path: String
        when {
            normalized.startsWith("/memory/") -> { zone = "memory"; path = normalized.removePrefix("/memory/") }
            normalized.startsWith("/skills/") -> { zone = "skills"; path = normalized.removePrefix("/skills/") }
            else -> { zone = "workspace"; path = normalized }
        }
        if (zone == "memory") {
            onMemoryCleared()
            return
        }
        generation.incrementAndGet()
        val document = runCatching { workspace.searchDocumentForPath(suppliedPath) }.getOrNull()
        if (document == null) index.remove(zone, path) else index.upsert(document)
    }

    fun snapshot(): List<SearchDocument> = index.snapshot().filter { it.zone != "memory" }

    internal fun lastRebuildThreadForTests(): String? = lastRebuildThread
    internal fun rebuildFinishedForTests(): Boolean = lastRebuildFinished

    fun disposeForTests() {
        unregisterMemoryIndex(this)
        val deadline = System.currentTimeMillis() + 5_000L
        while (rebuilding.get() && System.currentTimeMillis() < deadline) Thread.yield()
    }

    internal fun onMemoryChanged(revision: MemoryStore.Revision) {
        // Events are invalidation only. Never broadcast revision payloads into other chats.
        synchronized(rebuildLock) {
            generation.incrementAndGet()
            index.clearZone("memory")
        }
    }

    internal fun onMemoryCleared() {
        synchronized(rebuildLock) {
            generation.incrementAndGet()
            index.clearZone("memory")
        }
    }

    private fun scheduleRebuild(workspace: WorkspaceStore, zones: Set<String>) {
        if (!rebuilding.compareAndSet(false, true)) return
        lastRebuildFinished = false
        val requestedGeneration = generation.get()
        rebuildExecutor.execute {
            lastRebuildThread = Thread.currentThread().name
            var retry = false
            try {
                val source = workspace.searchDocuments(zones)
                synchronized(rebuildLock) {
                    if (generation.get() == requestedGeneration) {
                        index.replaceAll(source)
                        needsRebuild.set(false)
                    }
                    else retry = true
                }
            } catch (_: RuntimeException) {
                // The next search retries; callers can still use a direct source scan meanwhile.
            } finally {
                rebuilding.set(false)
                lastRebuildFinished = true
            }
            if (retry) scheduleRebuild(workspace, zones - "memory")
        }
    }

    private fun availableZones(workspace: WorkspaceStore): Set<String> = buildSet {
        add("workspace")
        add("skills")
        if (memoryAllowed && workspace.memoryEnabled()) add("memory")
    }

    companion object {
        private val coordinators = ConcurrentHashMap<String, MemorySearchIndexCoordinator>()
        private val memoryIndexes = java.util.concurrent.CopyOnWriteArraySet<MemorySearchIndexCoordinator>()
        private val listenerRegistered = AtomicBoolean(false)
        private val memoryListener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) =
                memoryIndexes.forEach { it.onMemoryChanged(revision) }
            override fun onMemoryCleared() = memoryIndexes.forEach { it.onMemoryCleared() }
        }
        private val rebuildExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "JarvysSearchIndex").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        @JvmStatic
        fun forContext(context: Context, projectId: String, memoryAllowed: Boolean): MemorySearchIndexCoordinator {
            val app = context.applicationContext
            val filesRoot = File(app.filesDir, "jarvys")
            val key = filesRoot.absolutePath + "/" + projectId + if (memoryAllowed) "-memory" else "-no-memory"
            return coordinators.computeIfAbsent(key) {
                val indexFile = File(File(filesRoot, "index"), "workspace-scope-v64-$projectId-${if (memoryAllowed) "m" else "n"}.bin")
                MemorySearchIndexCoordinator(FileMemorySearchIndex(indexFile), memoryAllowed)
            }
        }

        @JvmStatic
        fun notifySkillsChanged(context: Context) {
            val root = File(context.applicationContext.filesDir, "jarvys").absolutePath + "/"
            coordinators.filterKeys { it.startsWith(root) }.values.forEach { coordinator ->
                coordinator.lastWorkspace?.let { workspace -> coordinator.refreshZone(workspace, "skills") }
            }
        }

        private fun registerMemoryIndex(coordinator: MemorySearchIndexCoordinator) {
            memoryIndexes.add(coordinator)
            if (listenerRegistered.compareAndSet(false, true)) MemoryStore.addGlobalSearchListener(memoryListener)
        }

        private fun unregisterMemoryIndex(coordinator: MemorySearchIndexCoordinator) {
            memoryIndexes.remove(coordinator)
            if (memoryIndexes.isEmpty() && listenerRegistered.compareAndSet(true, false)) {
                MemoryStore.removeGlobalSearchListener(memoryListener)
            }
        }
    }
}
