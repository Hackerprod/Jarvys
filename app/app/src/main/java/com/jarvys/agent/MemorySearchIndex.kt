package com.jarvys.agent

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap
import kotlin.math.ln

/** One searchable file. Paths are zone-relative and never resolved by the ranker. */
data class SearchDocument(
    val zone: String,
    val path: String,
    val content: String,
    val modifiedAtMillis: Long,
    val latestRevisionId: Long? = null,
)

data class SearchFragment(val firstLine: Int, val lastLine: Int, val text: String)

data class SearchHit(
    val document: SearchDocument,
    val score: Double,
    val fragments: List<SearchFragment>,
    val containsPossibleSecret: Boolean,
)

/** Stable Unicode-aware tokenizer shared by indexing and query parsing. */
object SearchTokenizer {
    fun fold(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            decomposed.codePoints().forEach { point ->
                val type = Character.getType(point)
                if (type != Character.NON_SPACING_MARK.toInt()
                    && type != Character.COMBINING_SPACING_MARK.toInt()
                    && type != Character.ENCLOSING_MARK.toInt()) appendCodePoint(point)
            }
        }
    }

    fun tokens(value: String): List<String> {
        val folded = fold(value)
        val result = ArrayList<String>()
        val current = StringBuilder()
        folded.codePoints().forEach { point ->
            if (Character.isLetterOrDigit(point)) current.appendCodePoint(point)
            else if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}

/** Pluggable ranker contract; M5 deliberately ships lexical BM25 without embeddings. */
interface SearchRanker {
    fun rank(query: String, documents: Collection<SearchDocument>, limit: Int? = null): List<SearchHit>
}

/** Inverted-index BM25 implementation with deterministic score/path ordering. */
class Bm25SearchRanker(
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
    private val phraseBonus: Double = 1.5,
) : SearchRanker {
    override fun rank(query: String, documents: Collection<SearchDocument>, limit: Int?): List<SearchHit> {
        require(limit == null || limit > 0) { "limit must be a positive integer" }
        val docs = documents.sortedWith(compareBy<SearchDocument>({ it.path }, { it.zone }))
        val tokenized = docs.map { SearchTokenizer.tokens(it.content) }
        val postings = HashMap<String, MutableMap<String, Int>>()
        val tokenMap = HashMap<String, List<String>>()
        docs.forEachIndexed { index, document ->
            val key = documentKey(document.zone, document.path)
            val tokens = tokenized[index]
            tokenMap[key] = tokens
            tokens.groupingBy { it }.eachCount().forEach { (term, frequency) ->
                postings.getOrPut(term) { HashMap() }[key] = frequency
            }
        }
        return rankIndexed(query, docs, postings, tokenMap, limit)
    }

    internal fun rankIndexed(
        query: String,
        documents: Collection<SearchDocument>,
        invertedIndex: Map<String, Map<String, Int>>,
        tokenizedByDocument: Map<String, List<String>>,
        limit: Int?,
    ): List<SearchHit> {
        require(limit == null || limit > 0) { "limit must be a positive integer" }
        val parsed = parseQuery(query)
        if (parsed.terms.isEmpty()) return emptyList()
        val docs = documents.sortedWith(compareBy<SearchDocument>({ it.path }, { it.zone }))
        if (docs.isEmpty()) return emptyList()
        val docsByKey = docs.associateBy { documentKey(it.zone, it.path) }
        val candidates = HashSet<String>()
        parsed.terms.forEachIndexed { termIndex, term ->
            val isPrefix = parsed.prefixLast && termIndex == parsed.terms.lastIndex
            invertedIndex.forEach { (indexedTerm, rows) ->
                if (indexedTerm == term || (isPrefix && indexedTerm.startsWith(term))) {
                    rows.keys.filterTo(candidates) { it in docsByKey }
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val averageLength = docs.sumOf { tokenizedByDocument[documentKey(it.zone, it.path)]?.size ?: 0 }.toDouble() / docs.size
        val scored = ArrayList<SearchHit>()
        docs.filter { documentKey(it.zone, it.path) in candidates }.forEach { doc ->
            val key = documentKey(doc.zone, doc.path)
            val tokens = tokenizedByDocument[key] ?: SearchTokenizer.tokens(doc.content)
            if (parsed.requiredPhrases.any { phrase -> !containsAdjacent(tokens, phrase) }) return@forEach
            var score = 0.0
            parsed.terms.distinct().forEachIndexed { termIndex, term ->
                val prefix = parsed.prefixLast && termIndex == parsed.terms.lastIndex
                val matching = if (prefix) invertedIndex.keys.filter { it.startsWith(term) } else listOf(term)
                matching.forEach { actual ->
                    val rows = invertedIndex[actual].orEmpty()
                    val tf = rows[key] ?: 0
                    if (tf > 0) {
                        val documentFrequency = rows.keys.count { it in docsByKey }
                        val idf = ln(1.0 + (docs.size - documentFrequency + 0.5) / (documentFrequency + 0.5))
                        val lengthNorm = if (averageLength == 0.0) 1.0
                            else 1.0 - b + b * tokens.size / averageLength
                        score += idf * (tf * (k1 + 1.0)) / (tf + k1 * lengthNorm)
                    }
                }
            }
            if (score <= 0.0) return@forEach
            val phraseMatches = parsed.phrases.count { phrase -> containsAdjacent(tokens, phrase) }
            score += phraseMatches * phraseBonus
            scored += SearchHit(doc, score, fragments(doc.content, parsed), MemoryStore.containsLikelySecret(doc.content))
        }
        val ordered = scored.sortedWith(compareByDescending<SearchHit> { it.score }
            .thenBy { it.document.path }
            .thenBy { it.document.zone })
        return if (limit == null) ordered else ordered.take(limit)
    }

    private fun documentKey(zone: String, path: String) = "$zone\u0000$path"

    private data class ParsedQuery(
        val terms: List<String>,
        val phrases: List<List<String>>,
        val requiredPhrases: List<List<String>>,
        val prefixLast: Boolean,
    )

    private fun parseQuery(raw: String): ParsedQuery {
        val terms = ArrayList<String>()
        val phrases = ArrayList<List<String>>()
        val requiredPhrases = ArrayList<List<String>>()
        val unquoted = ArrayList<String>()
        val regex = Regex("\"([^\"]*)\"|(\\S+)")
        val matches = regex.findAll(raw)
        var lastWasQuoted = false
        for (match in matches) {
            val quoted = match.groups[1]?.value
            val text = quoted ?: match.groups[2]?.value.orEmpty()
            val tokens = SearchTokenizer.tokens(text)
            if (tokens.isEmpty()) continue
            terms += tokens
            if (quoted != null && tokens.size > 1) {
                phrases += tokens
                requiredPhrases += tokens
            } else if (quoted == null) unquoted += tokens
            lastWasQuoted = quoted != null
        }
        if (unquoted.size > 1) phrases += unquoted
        val trailing = raw.trimEnd().endsWith('"')
        val distinctTerms = terms.distinct()
        return ParsedQuery(distinctTerms, phrases, requiredPhrases,
            distinctTerms.isNotEmpty() && !lastWasQuoted && !trailing)
    }

    private fun containsAdjacent(tokens: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > tokens.size) return false
        return (0..tokens.size - phrase.size).any { start ->
            phrase.indices.all { offset -> tokens[start + offset] == phrase[offset] }
        }
    }

    private fun fragments(content: String, query: ParsedQuery): List<SearchFragment> {
        val lines = content.split('\n')
        val terms = query.terms
        val matched = lines.indices.filter { index ->
            val tokens = SearchTokenizer.tokens(lines[index])
            terms.any { term -> tokens.any { it == term || (query.prefixLast && term == terms.last() && it.startsWith(term)) } }
        }
        if (matched.isEmpty()) return emptyList()
        val windows = ArrayList<IntRange>()
        for (line in matched) {
            val next = (line - 1).coerceAtLeast(0)..(line + 1).coerceAtMost(lines.lastIndex)
            val previous = windows.lastOrNull()
            if (previous != null && next.first <= previous.last + 1) windows[windows.lastIndex] = previous.first..maxOf(previous.last, next.last)
            else windows += next
        }
        return windows.take(3).map { range ->
            SearchFragment(range.first + 1, range.last + 1,
                (range.first..range.last).joinToString("\n") { offset -> "${offset + 1}: ${lines[offset]}" })
        }
    }
}

/** Mutable/persisted derived index. Source files remain authoritative and can always rebuild it. */
interface MemorySearchIndex {
    val ready: Boolean
    fun upsert(document: SearchDocument)
    fun remove(zone: String, path: String)
    fun replaceAll(documents: Collection<SearchDocument>)
    fun replaceZone(zone: String, documents: Collection<SearchDocument>)
    fun search(query: String, zones: Set<String>, limit: Int? = null): List<SearchHit>
    fun clearZone(zone: String)
    fun snapshot(): List<SearchDocument>
}

class FileMemorySearchIndex(
    private val file: File,
    private val ranker: SearchRanker = Bm25SearchRanker(),
) : MemorySearchIndex {
    private val monitor = Any()
    private val documents = TreeMap<String, SearchDocument>()
    private val invertedIndex = HashMap<String, MutableMap<String, Int>>()
    private val tokenizedByDocument = HashMap<String, List<String>>()
    @Volatile private var loaded = false
    override val ready: Boolean get() = loaded

    init {
        synchronized(monitor) {
            val restored = runCatching { readIndex() }.getOrNull()
            if (restored != null) {
                restored.forEach(::putDocumentLocked)
                loaded = true
            }
        }
    }

    override fun upsert(document: SearchDocument) = synchronized(monitor) {
        putDocumentLocked(document)
        loaded = true
        persistLocked()
    }

    override fun remove(zone: String, path: String) = synchronized(monitor) {
        removeDocumentLocked(key(zone, path))
        loaded = true
        persistLocked()
    }

    override fun replaceAll(documents: Collection<SearchDocument>) = synchronized(monitor) {
        this.documents.clear()
        invertedIndex.clear()
        tokenizedByDocument.clear()
        documents.forEach(::putDocumentLocked)
        loaded = true
        persistLocked()
    }

    override fun replaceZone(zone: String, documents: Collection<SearchDocument>) = synchronized(monitor) {
        this.documents.keys.filter { it.startsWith("$zone\u0000") }.toList().forEach(::removeDocumentLocked)
        documents.filter { it.zone == zone }.forEach(::putDocumentLocked)
        loaded = true
        persistLocked()
    }

    override fun search(query: String, zones: Set<String>, limit: Int?): List<SearchHit> = synchronized(monitor) {
        if (!loaded) return emptyList()
        val scopedDocuments = documents.values.filter { it.zone in zones }
        val scopedKeys = scopedDocuments.mapTo(HashSet()) { key(it.zone, it.path) }
        val scopedPostings = invertedIndex.mapNotNull { (term, rows) ->
            val filtered = rows.filterKeys { it in scopedKeys }
            if (filtered.isEmpty()) null else term to filtered
        }.toMap()
        if (ranker is Bm25SearchRanker) {
            ranker.rankIndexed(query, scopedDocuments, scopedPostings, tokenizedByDocument, limit)
        } else ranker.rank(query, scopedDocuments, limit)
    }

    override fun clearZone(zone: String) = synchronized(monitor) {
        documents.keys.filter { it.startsWith("$zone\u0000") }.toList().forEach(::removeDocumentLocked)
        loaded = true
        persistLocked()
    }

    override fun snapshot(): List<SearchDocument> = synchronized(monitor) { documents.values.toList() }

    private fun key(zone: String, path: String) = "$zone\u0000$path"

    private fun putDocumentLocked(document: SearchDocument) {
        val documentKey = key(document.zone, document.path)
        removeDocumentLocked(documentKey)
        documents[documentKey] = document
        val tokens = SearchTokenizer.tokens(document.content)
        tokenizedByDocument[documentKey] = tokens
        tokens.groupingBy { it }.eachCount().forEach { (term, frequency) ->
            invertedIndex.getOrPut(term) { HashMap() }[documentKey] = frequency
        }
    }

    private fun removeDocumentLocked(documentKey: String) {
        documents.remove(documentKey)
        val priorTokens = tokenizedByDocument.remove(documentKey).orEmpty()
        priorTokens.toSet().forEach { term ->
            val rows = invertedIndex[term] ?: return@forEach
            rows.remove(documentKey)
            if (rows.isEmpty()) invertedIndex.remove(term)
        }
    }

    private fun persistLocked() {
        runCatching {
            val parent = file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val temporary = File(parent, file.name + ".tmp")
            DataOutputStream(FileOutputStream(temporary)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(documents.size)
                documents.values.forEach { doc ->
                    output.writeString(doc.zone)
                    output.writeString(doc.path)
                    output.writeString(doc.content)
                    output.writeLong(doc.modifiedAtMillis)
                    output.writeLong(doc.latestRevisionId ?: -1L)
                }
                output.flush()
            }
            if (file.exists() && !file.delete()) throw IllegalStateException("Could not replace search index")
            if (!temporary.renameTo(file)) throw IllegalStateException("Could not persist search index")
        }
    }

    private fun readIndex(): List<SearchDocument>? {
        if (!file.isFile) return null
        DataInputStream(FileInputStream(file)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null
            val count = input.readInt()
            if (count !in 0..MAX_INDEX_DOCUMENTS) return null
            val restored = ArrayList<SearchDocument>(count)
            repeat(count) {
                val zone = input.readString()
                val path = input.readString()
                val content = input.readString()
                val modified = input.readLong()
                val revision = input.readLong()
                restored += SearchDocument(zone, path, content, modified, revision.takeIf { it >= 0L })
            }
            if (input.read() != -1) return null
            return restored
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        if (length < 0 || length > MAX_STRING_BYTES) throw IllegalStateException("Invalid index string length")
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x4A565349
        const val FORMAT_VERSION = 1
        private const val MAX_INDEX_DOCUMENTS = 100_000
        private const val MAX_STRING_BYTES = 20 * 1024 * 1024
    }
}
