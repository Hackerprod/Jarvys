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
) {
    private val generation = AtomicLong(0L)
    private val rebuilding = AtomicBoolean(false)
    private val needsRebuild = AtomicBoolean(true)
    private val rebuildLock = Any()
    @Volatile private var lastWorkspace: WorkspaceStore? = null
    @Volatile private var lastRebuildThread: String? = null
    @Volatile private var lastRebuildFinished = false

    fun search(workspace: WorkspaceStore, query: String, zones: Set<String>, limit: Int?): List<SearchHit> {
        lastWorkspace = workspace
        require(zones.all { it == "workspace" || it == "skills" }) { "Unknown search zone" }
        require(!workspace.skillsOnly() || "workspace" !in zones) { "Scheduled tasks can search only installed skills" }
        val available = availableZones(workspace)
        val effectiveZones = zones intersect available
        val documents = if (!index.ready || needsRebuild.get()) {
            scheduleRebuild(workspace, available)
            workspace.searchDocuments(effectiveZones)
        } else index.snapshot().filter { it.zone in effectiveZones }
        return Bm25SearchRanker().rank(query, documents, limit)
    }

    fun refreshZone(workspace: WorkspaceStore, zone: String) {
        require(zone == "workspace" || zone == "skills") { "Unknown search zone" }
        generation.incrementAndGet()
        index.replaceZone(zone, workspace.searchDocuments(setOf(zone)))
    }

    fun refreshDocument(workspace: WorkspaceStore, suppliedPath: String) {
        val normalized = suppliedPath.trim().removeSuffix("/")
        val zone: String
        val path: String
        when {
            normalized == "/memory" || normalized.startsWith("/memory/") -> throw IllegalArgumentException("The /memory/ filesystem namespace is not available")
            normalized.startsWith("/skills/") -> { zone = "skills"; path = normalized.removePrefix("/skills/") }
            else -> { zone = "workspace"; path = normalized }
        }
        generation.incrementAndGet()
        val document = runCatching { workspace.searchDocumentForPath(suppliedPath) }.getOrNull()
        if (document == null) index.remove(zone, path) else index.upsert(document)
    }

    fun snapshot(): List<SearchDocument> = index.snapshot().filter { it.zone == "workspace" || it.zone == "skills" }

    internal fun lastRebuildThreadForTests(): String? = lastRebuildThread
    internal fun rebuildFinishedForTests(): Boolean = lastRebuildFinished

    fun disposeForTests() {
        val deadline = System.currentTimeMillis() + 5_000L
        while (rebuilding.get() && System.currentTimeMillis() < deadline) Thread.yield()
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
            if (retry) scheduleRebuild(workspace, zones)
        }
    }

    private fun availableZones(workspace: WorkspaceStore): Set<String> = buildSet {
        if (!workspace.skillsOnly()) add("workspace")
        if (workspace.skillsEnabled()) add("skills")
    }

    companion object {
        private val coordinators = ConcurrentHashMap<String, MemorySearchIndexCoordinator>()
        private val rebuildExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "JarvysSearchIndex").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        @JvmStatic
        fun forContext(context: Context, projectId: String): MemorySearchIndexCoordinator {
            val app = context.applicationContext
            val filesRoot = StorageGeneration(app).chatRoot()
            val key = filesRoot.absolutePath + "/" + projectId
            return coordinators.computeIfAbsent(key) {
                val indexFile = File(File(filesRoot, "index"), "workspace-skills-$projectId.bin")
                MemorySearchIndexCoordinator(FileMemorySearchIndex(indexFile))
            }
        }

        @JvmStatic
        fun notifySkillsChanged(context: Context) {
            val root = StorageGeneration(context).chatRoot().absolutePath + "/"
            coordinators.filterKeys { it.startsWith(root) }.values.forEach { coordinator ->
                coordinator.lastWorkspace?.let { workspace -> coordinator.refreshZone(workspace, "skills") }
            }
        }

    }
}
