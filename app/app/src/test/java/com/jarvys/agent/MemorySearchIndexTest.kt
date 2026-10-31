package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.DataOutputStream
import java.util.Random
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemorySearchIndexTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun tokenizerFoldsAccentsAndHandlesUnicodeDigitsEmojiAndCjk() {
        assertEquals(listOf("cafe", "２０２６", "日本語"), SearchTokenizer.tokens("Café ２０２６ 🐈 日本語"))
        assertEquals(SearchTokenizer.tokens("ÁRBOL"), SearchTokenizer.tokens("arbol"))
        assertTrue(SearchTokenizer.tokens("🌱✨").isEmpty())
    }

    @Test
    fun bm25UsesRarityLengthNormalizationPhrasePrefixAndStablePathTies() {
        val ranker = Bm25SearchRanker()
        val corpus = buildList {
            add(SearchDocument("workspace", "rare.md", "the quasar appears once", 0L))
            repeat(20) { add(SearchDocument("workspace", "common-$it.md", "the common word", 0L)) }
        }
        assertEquals("rare.md", ranker.rank("the quasar", corpus).first().document.path)

        val short = SearchDocument("workspace", "a-short.md", "signal", 0L)
        val long = SearchDocument("workspace", "b-long.md", "signal " + "filler ".repeat(400), 0L)
        assertEquals("a-short.md", ranker.rank("signal", listOf(short, long)).first().document.path)

        val phrase = SearchDocument("workspace", "a-phrase.md", "the red fox crossed", 0L)
        val apart = SearchDocument("workspace", "b-apart.md", "the red quick fox crossed", 0L)
        assertEquals("a-phrase.md", ranker.rank("red fox", listOf(apart, phrase)).first().document.path)
        assertEquals(listOf("a-phrase.md"), ranker.rank("\"red fox\"", listOf(apart, phrase))
            .map { it.document.path })
        assertEquals("prefixed.md", ranker.rank("cafe symphon", listOf(
            SearchDocument("workspace", "prefixed.md", "café symphonic", 0L),
            SearchDocument("workspace", "missing.md", "café simple", 0L))).first().document.path)

        val ties = listOf("z.md", "b.md", "a.md").map {
            SearchDocument("workspace", it, "sameword", 0L)
        }
        assertEquals(listOf("a.md", "b.md", "z.md"), ranker.rank("sameword", ties).map { it.document.path })
    }

    @Test
    fun snippetsKeepActualLineNumbersAndSecretsNeverReturnExcerptText() {
        val ranker = Bm25SearchRanker()
        val normal = SearchDocument("workspace", "notes.md", "heading\ncontext\nrareterm here\nending", 0L)
        val hit = ranker.rank("rareterm", listOf(normal)).single()
        assertEquals(2, hit.fragments.single().firstLine)
        assertTrue(hit.fragments.single().text.contains("3: rareterm here"))

        val secretText = "ordinary header\napi_key=ABCD1234EFGH\nordinary footer"
        val secret = ranker.rank("ABCD1234EFGH", listOf(
            SearchDocument("workspace", "private.md", secretText, 0L))).single()
        assertTrue(secret.containsPossibleSecret)
        assertTrue(secret.fragments.isNotEmpty())
    }

    @Test
    fun persistedIndexRebuildsWhenMissingCorruptOrFormatVersionChanges() {
        val file = File(temporaryFolder.root, "index.bin")
        assertFalse(FileMemorySearchIndex(file).ready)
        FileMemorySearchIndex(file).replaceAll(listOf(SearchDocument("workspace", "one.md", "quartz", 1L)))
        assertTrue(FileMemorySearchIndex(file).ready)
        assertEquals("one.md", FileMemorySearchIndex(file).search("quartz", setOf("workspace")).single().document.path)

        DataOutputStream(FileOutputStream(file, false)).use { out ->
            out.writeInt(0x4A565349)
            out.writeInt(FileMemorySearchIndex.FORMAT_VERSION + 1)
            out.writeInt(0)
        }
        assertFalse(FileMemorySearchIndex(file).ready)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(FileMemorySearchIndex(file).ready)
        val rebuilt = FileMemorySearchIndex(file)
        rebuilt.replaceAll(listOf(SearchDocument("workspace", "rebuilt.md", "repaired", 2L)))
        assertEquals("rebuilt.md", FileMemorySearchIndex(file).search("repaired", setOf("workspace")).single().document.path)
    }

    @Test
    fun deterministicRandomIncrementalOperationsEqualFullRebuild() {
        val incremental = FileMemorySearchIndex(File(temporaryFolder.root, "incremental.bin"))
        val rebuilt = FileMemorySearchIndex(File(temporaryFolder.root, "rebuilt.bin"))
        val random = Random(0x5A17L)
        repeat(500) { step ->
            val path = "note-${random.nextInt(24)}.md"
            when (random.nextInt(5)) {
                0, 1, 2 -> incremental.upsert(SearchDocument("memory", path,
                    "stable evidence token${random.nextInt(9)} step$step", step.toLong(), step.toLong()))
                3 -> incremental.remove("memory", path)
                else -> incremental.replaceZone("memory", incremental.snapshot().filter { it.zone == "memory" })
            }
            rebuilt.replaceAll(incremental.snapshot())
            val left = incremental.search("evidence token", setOf("memory"))
            val right = rebuilt.search("evidence token", setOf("memory"))
            assertEquals(left.map { it.document.path to it.score }, right.map { it.document.path to it.score })
        }
    }

    @Test
    fun largeSyntheticCorpusIndexesAndQueriesCorrectlyWithoutAnyDatabaseExtension() {
        val docs = (0 until 2_000).map { index ->
            SearchDocument("workspace", "docs/$index.md", "record $index common evidence " +
                if (index == 1777) "needle-quartz" else "ordinary", index.toLong())
        }
        val index = FileMemorySearchIndex(File(temporaryFolder.root, "large-index.bin"))
        index.replaceAll(docs)
        assertEquals("docs/1777.md", index.search("needle-quartz", setOf("workspace")).single().document.path)
        assertEquals(2_000, index.snapshot().size)
    }

    @Test
    fun missingIndexRebuildRunsOnTheDedicatedBackgroundThread() {
        val workspacesRoot = temporaryFolder.newFolder("background-workspaces")
        val projectId = "b".repeat(24)
        val projectRoot = File(workspacesRoot, projectId).apply { mkdirs() }
        repeat(1_200) { number ->
            File(projectRoot, "docs/$number.md").apply {
                parentFile!!.mkdirs()
                writeText("record $number baseline corpus " + if (number == 917) "needle" else "ordinary")
            }
        }
        val workspace = WorkspaceStore(workspacesRoot, projectId)
        val coordinator = MemorySearchIndexCoordinator(
            FileMemorySearchIndex(File(temporaryFolder.root, "background-index.bin")))
        try {
            val hit = coordinator.search(workspace, "needle", setOf("workspace"), null).single()
            assertEquals("docs/917.md", hit.document.path)
            val deadline = System.currentTimeMillis() + 5_000L
            while (!coordinator.rebuildFinishedForTests() && System.currentTimeMillis() < deadline) Thread.yield()
            assertEquals("JarvysSearchIndex", coordinator.lastRebuildThreadForTests())
            assertTrue(coordinator.rebuildFinishedForTests())
        } finally { coordinator.disposeForTests() }
    }

    @Test
    fun obsoleteMemoryZoneCannotReadRetainedDerivedPayload() {
        val workspace = WorkspaceStore(temporaryFolder.newFolder("workspace"), "a".repeat(24))
        val index = FileMemorySearchIndex(File(temporaryFolder.root, "stale.bin"))
        index.replaceAll(listOf(SearchDocument("memory", "private.md", "foreignlegacytoken", 0L)))
        val coordinator = MemorySearchIndexCoordinator(index)
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                coordinator.search(workspace, "foreignlegacytoken", setOf("memory"), null)
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                coordinator.refreshDocument(workspace, "/memory/private.md")
            }
            assertFalse(coordinator.snapshot().any { it.zone == "memory" })
            assertTrue(coordinator.search(workspace, "foreignlegacytoken", setOf("workspace", "skills"), null).isEmpty())
        } finally { coordinator.disposeForTests() }
    }
    @Test
    fun currentWorkspaceRefreshAndSeparateIndexesNeverMixOwnership() {
        val roots = temporaryFolder.newFolder("owners")
        val a = WorkspaceStore(roots, WorkspaceStore.projectIdForSession("owner-a"))
        val b = WorkspaceStore(roots, WorkspaceStore.projectIdForSession("owner-b"))
        val indexA = FileMemorySearchIndex(File(temporaryFolder.root, "a.bin"))
        val indexB = FileMemorySearchIndex(File(temporaryFolder.root, "b.bin"))
        val ca = MemorySearchIndexCoordinator(indexA)
        val cb = MemorySearchIndexCoordinator(indexB)
        try {
            a.write("private.md", "firsttoken")
            b.write("private.md", "secondtoken")
            assertEquals("private.md", ca.search(a, "firsttoken", setOf("workspace"), null).single().document.path)
            assertEquals("private.md", cb.search(b, "secondtoken", setOf("workspace"), null).single().document.path)
            assertTrue(ca.search(a, "secondtoken", setOf("workspace"), null).isEmpty())
            assertTrue(cb.search(b, "firsttoken", setOf("workspace"), null).isEmpty())
            a.write("private.md", "updatedtoken")
            ca.refreshDocument(a, "private.md")
            assertTrue(ca.search(a, "firsttoken", setOf("workspace"), null).isEmpty())
            assertEquals("private.md", ca.search(a, "updatedtoken", setOf("workspace"), null).single().document.path)
            assertTrue(cb.search(b, "updatedtoken", setOf("workspace"), null).isEmpty())
            assertFalse(ca.snapshot().any { it.zone == "memory" })
            assertFalse(cb.snapshot().any { it.zone == "memory" })
        } finally { ca.disposeForTests(); cb.disposeForTests() }
    }

}
