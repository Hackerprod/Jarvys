package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jarvys.factory.runtime.DatabaseBackupFormat
import com.jarvys.factory.runtime.DatabaseBackupPlan
import com.jarvys.factory.runtime.DocumentBrokerIdentity
import com.jarvys.factory.runtime.FactoryConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.ZipInputStream

/** The allowlist is read from the same installed-APK descriptor whose complete bytes are verified. */
internal object FactoryDataBackupPolicy {
    internal const val MAX_APK_BYTES = 256L * 1024 * 1024
    internal const val MAX_ENTRIES = 8192
    internal const val MAX_INFLATED_BYTES = 512L * 1024 * 1024
    internal const val MAX_CONFIG_BYTES = 8192
    private val hosts = setOf(DocumentBrokerIdentity.PRIMARY, DocumentBrokerIdentity.RECOVERY)
    data class Installed(val sourceDir: String, val certificate: String, val version: Long, val uid: Int, val updated: Long)
    data class Verified(val proof: FactoryDocumentCoordinator.Proof, val source: Installed, val host: Installed, val tables: Set<String>)

    fun verify(context: Context, appId: String, selected: List<String>): Verified {
        require(context.packageName in hosts && appId !in hosts)
        fun soleHost() {
            for (other in hosts - context.packageName) {
                val absent = try { context.packageManager.getApplicationInfo(other, 0); false } catch (_: PackageManager.NameNotFoundException) { true }
                check(absent)
            }
        }
        soleHost()
        val before = installed(context, appId)
        val hostBefore = installed(context, context.packageName)
        val identity = FactorySigningIdentity(context)
        val receipt = identity.state(appId)
        FactoryDocumentCoordinator.verifyEvidence(appId, before.certificate, receipt.lastApkSha256 ?: error("Unknown APK"), before.version, receipt, setOf("database", "documents"))
        val (apk, config) = RandomAccessFile(before.sourceDir, "r").use { file ->
            readVerifiedConfig(file, receipt.lastApkSha256 ?: error("Unknown APK"))
        }
        val policy = FactoryConfig.parse(config, appId)
        require(policy.capabilities.containsAll(setOf("database", "documents")))
        val broker = policy.documentBroker ?: error("Missing host binding")
        require(broker.packageName == context.packageName && broker.certificateSha256 == hostBefore.certificate)
        require(selected.isNotEmpty() && selected.toSet().size == selected.size && policy.dataBackupTables.containsAll(selected))
        require(receipt == identity.state(appId)); soleHost()
        require(before == installed(context, appId) && hostBefore == installed(context, context.packageName))
        val proof = FactoryDocumentCoordinator.verifyEvidence(appId, before.certificate, apk, before.version, receipt, setOf("database", "documents"))
        return Verified(proof, before, hostBefore, Collections.unmodifiableSet(policy.dataBackupTables.toSortedSet()))
    }
    /** Lightweight checks for each IPC/chunk boundary. Full APK/config hashing remains at review boundaries. */
    fun requireCurrent(context: Context, verified: Verified) {
        require(context.packageName in hosts && verified.proof.appId !in hosts)
        fun soleHost() {
            for (other in hosts - context.packageName) {
                val absent = try { context.packageManager.getApplicationInfo(other, 0); false } catch (_: PackageManager.NameNotFoundException) { true }
                check(absent)
            }
        }
        soleHost()
        check(installed(context, verified.proof.appId) == verified.source && installed(context, context.packageName) == verified.host)
        val receipt = FactorySigningIdentity(context).state(verified.proof.appId)
        check(FactoryDocumentCoordinator.verifyEvidence(verified.proof.appId, verified.source.certificate, verified.proof.apk,
            verified.source.version, receipt, setOf("database", "documents")) == verified.proof)
        soleHost()
        check(installed(context, verified.proof.appId) == verified.source && installed(context, context.packageName) == verified.host)
    }
    /** Uses only the already opened descriptor. Never resolves the package path again. */
    internal fun readVerifiedConfig(file: RandomAccessFile, expectedApk: String): Pair<String, String> {
        require(expectedApk.matches(Regex("[a-f0-9]{64}")))
        val length = file.length(); require(length in 1..MAX_APK_BYTES)
        file.seek(0)
        val apk = FactoryDocumentCoordinator.hashApk(descriptorStream(file), MAX_APK_BYTES)
        require(file.length() == length && apk == expectedApk)
        file.seek(0)
        val config = extractConfig(descriptorStream(file))
        require(file.length() == length)
        // A changed descriptor cannot substitute policy bytes even if the package path is stable.
        file.seek(0); require(FactoryDocumentCoordinator.hashApk(descriptorStream(file), MAX_APK_BYTES) == apk)
        require(file.length() == length)
        return apk to config
    }
    fun validate(plan: DatabaseBackupPlan, verified: Verified) {
        require(plan.appId == verified.proof.appId && plan.certificateSha256 == verified.proof.certificate && verified.tables.containsAll(plan.tables))
    }
    private fun installed(context: Context, appId: String): Installed {
        val pm = context.packageManager
        @Suppress("DEPRECATION") val info = pm.getPackageInfo(appId, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val app = info.applicationInfo ?: error("Missing package")
        FactoryDocumentCoordinator.verifyPackageShape(appId, info, pm.getPackagesForUid(app.uid)?.toSet())
        @Suppress("DEPRECATION") val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        require(signatures?.size == 1)
        @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return Installed(app.sourceDir, DatabaseBackupFormat.sha256(signatures!![0].toByteArray()), version, app.uid, info.lastUpdateTime)
    }
    // The wrapper never closes/reopens the descriptor. RandomAccessFile remains its only owner.
    private fun descriptorStream(file: RandomAccessFile) = object : InputStream() {
        override fun read() = file.read()
        override fun read(bytes: ByteArray, offset: Int, count: Int) = file.read(bytes, offset, count)
    }
    internal fun extractConfig(input: InputStream): String {
        var config: ByteArray? = null
        var entries = 0; var total = 0L
        val seen = HashSet<String>()
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(32768)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES && entry.name.length <= 1024 && seen.add(entry.name))
                val selected = entry.name == "assets/factory-app.json"
                if (selected) require(!entry.isDirectory && config == null)
                val output = if (selected) ByteArrayOutputStream() else null
                var count = 0L
                while (true) {
                    val n = zip.read(buffer); if (n < 0) break
                    require(n > 0 && n <= MAX_INFLATED_BYTES - total)
                    total += n; count += n
                    if (selected) { require(count <= MAX_CONFIG_BYTES); output!!.write(buffer, 0, n) }
                }
                if (selected) config = output!!.toByteArray()
                zip.closeEntry()
            }
        }
        val bytes = config ?: error("Missing installed configuration")
        FactoryJson.objectFrom(bytes, MAX_CONFIG_BYTES)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
