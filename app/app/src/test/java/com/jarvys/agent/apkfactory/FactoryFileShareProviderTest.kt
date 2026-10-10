package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.security.MessageDigest
import java.time.Duration

class ShareTestContext(base: Context) : ContextWrapper(base) {
    val revocations = mutableListOf<Pair<Uri, Int>>()
    var failRevocation = false
    override fun getApplicationContext(): Context = this
    override fun revokeUriPermission(uri: Uri, modeFlags: Int) {
        revocations += uri to modeFlags
        check(!failRevocation) { "Synthetic revocation failure" }
    }
}

internal abstract class FactoryFileShareTestSupport {
    protected lateinit var context: ShareTestContext
    protected lateinit var provider: FactoryFileShareProvider
    protected val root get() = File(context.cacheDir.canonicalFile, "factory-file-shares")
    protected val bytes = "private generated export\n".toByteArray()
    protected val nonce = "a".repeat(64)
    protected fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    protected fun store(value: ByteArray = bytes) = FactoryFileShareStore(context) { _, _, _, _, output, active -> active.run(); output.write(value) }
    protected fun stage(store: FactoryFileShareStore = store(), filename: String = "report.txt", size: Int = bytes.size,
                        sha256: String = digest(bytes), checkActive: () -> Unit = {}) =
        store.stage(nonce, filename, "text/plain", size, sha256, Binder(), checkActive)
    protected fun reject(block: () -> Unit) { assertTrue("Expected fail-closed rejection", runCatching(block).isFailure) }
    protected fun read(uri: Uri): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
    protected fun snapshotFile(snapshot: FactoryFileShareStore.Snapshot) = File(root, snapshot.uri.lastPathSegment!!)

    @Before fun setUpShare() {
        context = ShareTestContext(ApplicationProvider.getApplicationContext())
        root.deleteRecursively()
        FactoryFileShareStore(context).cleanup()
        val info = context.packageManager.getProviderInfo(ComponentName(context, FactoryFileShareProvider::class.java), PackageManager.GET_META_DATA)
        provider = FactoryFileShareProvider().apply { attachInfo(context, info) }
    }
    @After fun tearDownShare() {
        context.failRevocation = false
        root.deleteRecursively()
        FactoryFileShareStore(context).cleanup()
    }
}

/** Synthetic host tests. Real resolver grants, chooser forwarding and receiver FDs require device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class FactoryFileShareProviderTest : FactoryFileShareTestSupport() {
    @Test fun manifestAndAttachRequireExactPrivateGrantingAuthority() {
        val info = context.packageManager.getProviderInfo(ComponentName(context, FactoryFileShareProvider::class.java), PackageManager.GET_META_DATA)
        assertEquals(context.packageName + ".factory.files", info.authority)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        for (change in listOf<(ProviderInfo) -> Unit>(
            { it.authority = "evil.files" }, { it.authority += ";evil.files" },
            { it.exported = true }, { it.grantUriPermissions = false }
        )) reject { FactoryFileShareProvider().attachInfo(context, ProviderInfo(info).also(change)) }
    }

    @Test fun exactSnapshotReturnsKnownMetadataAndReadOnlyBytes() {
        val snapshot = stage()
        assertArrayEquals(bytes, read(snapshot.uri))
        assertEquals("text/plain", provider.getType(snapshot.uri))
        assertEquals("text/plain", provider.getTypeAnonymous(snapshot.uri))
        assertArrayEquals(arrayOf("text/plain"), provider.getStreamTypes(snapshot.uri, "text/*"))
        provider.query(snapshot.uri, null, null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals("report.txt", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
            assertFalse(it.moveToNext())
        }
        assertFalse(snapshot.uri.toString().contains("report.txt"))
        assertEquals(256, Os.lstat(snapshotFile(snapshot).path).st_mode and 511)
    }

    @Test fun writableModesAndEveryMutationAreRejected() {
        val uri = stage().uri
        for (mode in listOf("w", "wt", "wa", "rw", "rwt", "", "R", "r ", "rt")) reject { provider.openFile(uri, mode) }
        reject { provider.insert(uri, ContentValues()) }
        reject { provider.insert(uri, ContentValues(), Bundle()) }
        reject { provider.update(uri, ContentValues(), null, null) }
        reject { provider.update(uri, ContentValues(), Bundle()) }
        reject { provider.delete(uri, null, null) }
        reject { provider.delete(uri, Bundle()) }
        reject { provider.bulkInsert(uri, emptyArray()) }
        reject { provider.applyBatch(arrayListOf()) }
        reject { provider.applyBatch(uri.authority!!, arrayListOf(ContentProviderOperation.newDelete(uri).build())) }
        reject { provider.call("write", null, Bundle()) }
        assertArrayEquals(bytes, read(uri))
    }

    @Test fun aliasesTraversalAndUnknownUrisNeverRevealMetadataOrBytes() {
        val uri = stage().uri
        val aliases = listOf(uri.buildUpon().appendQueryParameter("displayName", "secret").build(),
            uri.buildUpon().fragment("alias").build(), uri.buildUpon().authority("evil.files").build(),
            Uri.parse(uri.toString().replace("/files/", "/files/../files/")),
            Uri.parse(uri.toString().replace("/files/", "/files/%2e%2e/files/")),
            Uri.parse(uri.toString().replace("/files/", "/%66iles/")),
            uri.buildUpon().appendPath("other").build())
        for (alias in aliases) {
            reject { provider.openFile(alias, "r") }
            reject { provider.query(alias, null, null, null, null) }
            reject { provider.getType(alias) }
            reject { provider.getTypeAnonymous(alias) }
            reject { provider.getStreamTypes(alias, "*/*") }
        }
    }

    @Test fun expiryIsMonotonicAndAppliesAtTheExactFiveMinuteBoundary() {
        val snapshot = stage()
        ShadowSystemClock.advanceBy(Duration.ofMillis(FactoryFileShareStore.LIFETIME_MILLIS - 1))
        assertEquals("text/plain", provider.getType(snapshot.uri))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        reject { provider.openFile(snapshot.uri, "r") }
        reject { provider.query(snapshot.uri, null, null, null, null) }
        reject { provider.getType(snapshot.uri) }
        reject { provider.getTypeAnonymous(snapshot.uri) }
    }

    @Test fun symlinkSubstitutionAndReplacedInodeFailClosed() {
        val snapshot = stage()
        val file = snapshotFile(snapshot)
        val elsewhere = File(context.cacheDir, "share-test-private-target").apply { writeText("never reveal") }
        try {
            assertTrue(file.delete())
            Os.symlink(elsewhere.path, file.path)
            reject { provider.openFile(snapshot.uri, "r") }
            reject { provider.getType(snapshot.uri) }
            assertTrue(file.delete())
            file.writeBytes(bytes)
            Os.chmod(file.path, 256)
            reject { provider.query(snapshot.uri, null, null, null, null) }
            reject { provider.openFile(snapshot.uri, "r") }
        } finally { elsewhere.delete() }
    }

    @Test fun unregisteredCacheAndCancelledOpenCannotBeRead() {
        val snapshot = stage()
        val signal = CancellationSignal().apply { cancel() }
        reject { provider.openFile(snapshot.uri, "r", signal) }
        FactoryFileShareStore(context).cleanup()
        val orphan = File(root, "snapshot-00000000-0000-0000-0000-000000000000.bin").apply { writeBytes(bytes) }
        Os.chmod(orphan.path, 256)
        val uri = FileProvider.getUriForFile(context, FactoryFileShareStore.authority(context), orphan)
        // Merely instantiating the store never rediscovers authority from old cache contents.
        FactoryFileShareStore(context)
        reject { provider.openFile(uri, "r") }
        reject { provider.query(uri, null, null, null, null) }
        reject { provider.getType(uri) }
    }
}
