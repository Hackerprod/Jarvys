package com.jarvys.agent.apkfactory

import com.jarvys.agent.coding.ProjectScope
import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.ResourceBudgetsJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Literal historical writer output, with independent pinned byte digests. Do not regenerate from the current writer. */
internal object FactorySigningScopeFixtures {
    val digests = linkedMapOf(
        "v3-custom" to "a1ea1f9586eaf7996030f6d44dd7582aaa86bdee2a4a7976d10e9714a73097ae",
        "v3-default" to "265d242b2adaab415e7605c9c12584b89666c5143eaece08799eaf47aa39e5bc",
        "v4-custom" to "e9426b1847750936cf37b8f17cc8fc2d39d7c8a644508e8c1d9680c12ce949b0",
        "v4-default" to "4a268b13a8ad554635b473efd829dbd30026525841e05ba24a2165b1704429c5",
        "hardware-custom" to "9bbf5a69bef63ae6b10a23da60fb9008633b31d55fd0d55eb6ee1da80eaea375",
        "hardware-default" to "fb3fbfd2ce44f46d171a96d929b828e37d319593d6368dcaecdf938bf7c8e266"
    )
    fun bytes(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/factory-signing-scopes/$name.json"))
        .use { it.readBytes() }.also { assertEquals(digests.getValue(name), ProjectScope.sha256(it)) }
}

/** Synthetic receipts only: no signing key, installed APK, document, network or credential access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryHistoricalSigningScopeTest {
    @get:Rule val folder = TemporaryFolder()
    private val appId = "org.example.notebook"
    private val certificate = "a".repeat(64)
    private val sha = "b".repeat(64)
    @After fun releaseAuthorities() { FactoryArtifactAuthority.invalidate(appId) }
    private fun plan(version: Int = 1) = ManifestPlan(appId, "Notebook", version, "$version.0", 0x7f010001, 0x7f020001,
        listOf("storage", "documents", "speech"))
    private fun saved(anchor: JSONObject) = JSONObject().put("lastApkSha256", sha).put("lastSignedScope", anchor)
    private fun read(anchor: JSONObject) = FactorySigningScope.readBaseline(saved(anchor), appId, certificate, 1)
    private fun current() = FactorySigningScope.fromPlan(plan(), ResourceBudgets.legacy())

    @Test fun historicalWriterFixturesRetainEveryCanonicalByteAndDigest() {
        for ((name, digest) in FactorySigningScopeFixtures.digests) {
            val bytes = FactorySigningScopeFixtures.bytes(name)
            val anchor = JSONObject(bytes.toString(Charsets.UTF_8))
            val original = anchor.getJSONObject("scope").getJSONObject("resourceBudgets")
            val loaded = requireNotNull(read(anchor)) { "Historical writer $name was rejected" }
            assertArrayEquals(name, bytes, loaded.anchored(appId, certificate, 1, sha).toString().toByteArray(Charsets.UTF_8))
            assertEquals(digest, ProjectScope.sha256(loaded.anchored(appId, certificate, 1, sha).toString().toByteArray(Charsets.UTF_8)))
            assertEquals(ResourceBudgetsJson.parse(original), loaded.resources())
            assertEquals(if (name.endsWith("custom")) 8 else 4, loaded.resources().documents.openHandles)
            assertEquals(if (name.endsWith("custom")) 45000L else 30000L, loaded.resources().audio.durationMs)
            assertEquals(300000L, loaded.resources().audio.setupTimeoutMs)
            assertEquals(8L * 1024 * 1024, loaded.resources().fileShare.sourceBytes)
            assertFalse(original.getJSONObject("audio").has("setupTimeoutMs"))
            assertFalse(original.has("fileShare"))
            loaded.disclosure(current())
            // Resolving runtime defaults and computing deltas must not upgrade the receipt.
            assertArrayEquals(name, bytes, loaded.anchored(appId, certificate, 1, sha).toString().toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun historicalDefaultsDoNotInventResourceExpansionButCurrentCustomValuesRemainBound() {
        val budgets = ResourceBudgets.parse(mapOf("version" to 1,
            "audio" to mapOf("setupTimeoutMs" to 120000),
            "fileShare" to mapOf("sourceBytes" to 16777216, "transferTimeoutMs" to 120000, "leaseMs" to 600000)))
        val custom = FactorySigningScope.fromPlan(plan(), budgets)
        val encoded = custom.anchored(appId, certificate, 1, sha)
        assertEquals(custom, read(encoded))
        assertEquals(budgets, read(encoded)!!.resources())
        assertEquals(120000L, encoded.getJSONObject("scope").getJSONObject("resourceBudgets").getJSONObject("audio").getLong("setupTimeoutMs"))
        assertEquals(16777216L, encoded.getJSONObject("scope").getJSONObject("resourceBudgets").getJSONObject("fileShare").getLong("sourceBytes"))
        for (profile in listOf("v3", "v4", "hardware")) {
            val old = read(JSONObject(FactorySigningScopeFixtures.bytes("$profile-default").toString(Charsets.UTF_8)))!!
            assertEquals(ResourceBudgets.legacy(), old.resources())
            assertTrue(current().disclosure(old).none { it.startsWith("Resource capacity changes") })
            assertEquals(setOf(
                "Resource capacity changes (audio.setupTimeoutMs): 300000 -> 120000",
                "Resource capacity changes (fileShare.sourceBytes): 8388608 -> 16777216",
                "Resource capacity changes (fileShare.transferTimeoutMs): 300000 -> 120000",
                "Resource capacity changes (fileShare.leaseMs): 300000 -> 600000"
            ), custom.disclosure(old).filter { it.startsWith("Resource capacity changes") }.toSet())
        }
    }

    @Test fun mixedPartialAndUnknownResourceProfilesFailClosed() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.remove("fileShare") },
            { it.remove("secrets") },
            { it.getJSONObject("audio").remove("setupTimeoutMs") },
            { it.getJSONObject("audio").remove("sourceBytes") },
            { it.getJSONObject("fileShare").remove("leaseMs") },
            { it.getJSONObject("documents").remove("openHandles") },
            { it.getJSONObject("audio").put("futureField", 1) },
            { it.getJSONObject("audio").put("setupTimeoutMs", "120000") },
            { it.getJSONObject("fileShare").put("sourceBytes", 0) }
        )) {
            val anchor = current().anchored(appId, certificate, 1, sha)
            mutate(anchor.getJSONObject("scope").getJSONObject("resourceBudgets"))
            assertNull(read(anchor))
        }
        for (name in FactorySigningScopeFixtures.digests.keys) {
            for (mutate in listOf<(JSONObject) -> Unit>(
                { it.getJSONObject("audio").put("setupTimeoutMs", 300000) },
                { it.getJSONObject("audio").put("setupTimeoutMs", 120000) },
                { it.put("fileShare", ResourceBudgetsJson.toJson(ResourceBudgets.legacy()).getJSONObject("fileShare")) },
                { it.getJSONObject("audio").remove("durationMs") },
                { it.getJSONObject("documents").remove("openHandles") }
            )) {
                val anchor = JSONObject(FactorySigningScopeFixtures.bytes(name).toString(Charsets.UTF_8))
                mutate(anchor.getJSONObject("scope").getJSONObject("resourceBudgets"))
                assertNull(name, read(anchor))
            }
        }
    }

    @Test fun actualHistoryDecoderRetainsHistoricalBytesAndProofsAcrossNewSignatures() {
        for ((name, digest) in FactorySigningScopeFixtures.digests) {
            FactoryArtifactAuthority.invalidate(appId)
            val bytes = FactorySigningScopeFixtures.bytes(name)
            val record = bytes.toString(Charsets.UTF_8)
            val anchor = JSONObject(record)
            val old = requireNotNull(read(anchor))
            val state = FactorySigningIdentity.State(true, certificate, 1, old, sha, ProjectScope.sha256("identity-1".toByteArray()))
            val directory = folder.newFolder(name)
            val file = File(directory, "$appId.json")
            file.writeText(JSONObject().put("schemaVersion", 1).put("appId", appId).put("artifacts", JSONArray()
                .put(JSONObject().put("record", record).put("sha256", digest))).toString())
            val history = FactorySignedArtifactHistory(directory)
            val before = history.verify(appId, certificate, sha, 1, state, setOf("documents"))
            assertEquals(digest, before.record)
            assertEquals(old.resources(), before.resources)
            val latest = FactorySigningIdentity.State(true, certificate, 2,
                FactorySigningScope.fromPlan(plan(2), ResourceBudgets.maxima()), "c".repeat(64), ProjectScope.sha256("identity-2".toByteArray()))
            history.recordCurrent(appId, latest)
            val reloaded = FactorySignedArtifactHistory(directory)
            assertEquals(before, reloaded.verify(appId, certificate, sha, 1, latest, setOf("documents")))
            reloaded.capture(before, latest, setOf("documents")).use { it.requireCurrent() }
            val retained = JSONObject(file.readText()).getJSONArray("artifacts").getJSONObject(1)
            assertArrayEquals(bytes, retained.getString("record").toByteArray(Charsets.UTF_8))
            assertEquals(digest, retained.getString("sha256"))
            // Even recomputing the outer checksum cannot legitimize a mixed historical schema.
            val invalid = JSONObject(record)
            invalid.getJSONObject("scope").getJSONObject("resourceBudgets").getJSONObject("audio").put("setupTimeoutMs", 120000)
            val malformed = invalid.toString()
            val saved = JSONObject(file.readText())
            saved.getJSONArray("artifacts").getJSONObject(1).put("record", malformed)
                .put("sha256", ProjectScope.sha256(malformed.toByteArray(Charsets.UTF_8)))
            file.writeText(saved.toString())
            assertThrows(Exception::class.java) { reloaded.verify(appId, certificate, sha, 1, latest, setOf("documents")) }
        }
    }
}
