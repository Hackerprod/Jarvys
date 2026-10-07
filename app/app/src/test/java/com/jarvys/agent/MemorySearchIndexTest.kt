package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.DataOutputStream
import java.util.Random
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

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
            FileMemorySearchIndex(File(temporaryFolder.root, "background-index.bin")), false)
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
    fun memoryExportDoesNotIncludeThePrivateDerivedIndex() {
        val memoryRoot = File(temporaryFolder.root, "memory").apply { mkdirs() }
        val indexFile = File(temporaryFolder.root, "index/workspace-a-m.bin").apply {
            parentFile!!.mkdirs()
            writeText("private derived cache")
        }
        val store = MemoryStore(memoryRoot, true, testMemorySeedProvider()).also { it.ensureInitialized() }
        val zip = ByteArrayOutputStream().also { store.exportZip(it, true) }.toByteArray()
        val names = ArrayList<String>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue(names.any { it == "MEMORY.md" })
        assertFalse(names.any { it.contains("index") || it.endsWith(".bin") })
        assertTrue(indexFile.isFile)
    }

    @Test
    fun memoryRevisionsIncrementallyTrackWriteEditDeleteUndoRestoreClearAndReflectionUndo() {
        val root = temporaryFolder.newFolder("memory-source")
        val store = MemoryStore(root, true, testMemorySeedProvider())
        val persistedMemoryIndex = File(File(root.parentFile, "index"), "workspace-test-m.bin")
        val index = FileMemorySearchIndex(persistedMemoryIndex)
        val coordinator = MemorySearchIndexCoordinator(index, true)
        try {
            store.ensureInitialized()
            assertMemoryMatchesSource(store, coordinator)
            val created = store.write("dated.md", note("user preference on 2026-10-05: concise", "Evidence source: user said so in chat, 2026-10-05."),
                MemoryStore.Actor.USER, "memory-test")
            assertMemoryMatchesSource(store, coordinator)
            store.edit("dated.md", "concise", "brief", MemoryStore.Actor.USER, "memory-test")
            assertMemoryMatchesSource(store, coordinator)
            store.restoreRevision(created.id, MemoryStore.Actor.USER, "memory-test")
            assertMemoryMatchesSource(store, coordinator)
            store.delete("dated.md", MemoryStore.Actor.USER, "memory-test")
            assertMemoryMatchesSource(store, coordinator)
            store.undoLast(MemoryStore.Actor.USER, "memory-test")
            assertMemoryMatchesSource(store, coordinator)

            store.beginReflectionGroup("search-group", "memory-test")
            store.write("reflected.md", note("Preference", "Date 2026-10-05; source: said by user in chat."),
                MemoryStore.Actor.REFLECTION, "memory-test", "search-group")
            store.finishReflectionGroup("search-group", "completed")
            assertMemoryMatchesSource(store, coordinator)
            store.undoReflectionGroup("search-group", "memory-test")
            assertMemoryMatchesSource(store, coordinator)

            val random = Random(1405L)
            repeat(80) { step ->
                val path = "random-${random.nextInt(6)}.md"
                when (random.nextInt(4)) {
                    0, 1 -> runCatching { store.write(path, note("Entry", "fixedseed token-${random.nextInt(8)} step-$step"),
                        MemoryStore.Actor.USER, "memory-test") }
                    2 -> runCatching { store.edit(path, "fixedseed", "changed$step", MemoryStore.Actor.USER, "memory-test") }
                    else -> runCatching { store.delete(path, MemoryStore.Actor.USER, "memory-test") }
                }
                assertMemoryMatchesSource(store, coordinator)
            }

            store.clearAll(MemoryStore.Actor.USER)
            assertFalse(persistedMemoryIndex.exists())
            assertMemoryMatchesSource(store, coordinator)
        } finally { coordinator.disposeForTests() }
    }

    private fun assertMemoryMatchesSource(store: MemoryStore, coordinator: MemorySearchIndexCoordinator) {
        val expected = store.searchDocuments().associateBy { it.path to it.content }
        val actual = coordinator.snapshot().filter { it.zone == "memory" }.associateBy { it.path to it.content }
        assertEquals(expected.keys, actual.keys)
    }

    private fun note(name: String, body: String) = "---\nname: $name\ndescription: Search test note\n---\n$body\n"
}
