package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
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
        // Direct-javac runners do not generate a shadow resetter service. Keep each fixture isolated.
        FactoryFileShareOsShadow.reset()
        context = ShareTestContext(ApplicationProvider.getApplicationContext())
        root.deleteRecursively()
        FactoryFileShareStore(context).cleanup()
        val info = context.packageManager.getProviderInfo(ComponentName(context, FactoryFileShareProvider::class.java), PackageManager.GET_META_DATA)
        provider = FactoryFileShareProvider()
        provider.attachInfo(context, info)
    }
    @After fun tearDownShare() {
        context.failRevocation = false
        root.deleteRecursively()
        FactoryFileShareStore(context).cleanup()
    }
}

/** Synthetic host tests. Real resolver grants, chooser forwarding and receiver FDs require device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryFileShareOsShadow::class])
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

    @Test fun livePolicyDeniesNewMetadataAndOpenWhileAlreadyOpenFdCannotBeRecalled() {
        val policy = FactoryOperationPolicy.inMemory()
        val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 1, "c".repeat(64))
        val subject = FactoryOperationPolicy.Subject.installed(proof)
        val grant = policy.acquire(subject, FactoryOperationPolicy.Operation.FILE_SHARE, FactoryOperationPolicy.binding(nonce, digest(bytes)))
        val snapshot = store().stage(nonce, "report.txt", "text/plain", bytes.size, digest(bytes), Binder(), {}, { grant.checkBeforeEffect() })
        val alreadyOpen = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(snapshot.uri, "r"))
        assertEquals("text/plain", provider.getType(snapshot.uri))
        policy.setMode(subject, FactoryOperationPolicy.Operation.FILE_SHARE, FactoryOperationPolicy.Mode.DENY)
        reject { provider.getType(snapshot.uri) }; reject { provider.query(snapshot.uri, null, null, null, null) }; reject { provider.openFile(snapshot.uri, "r") }
        // Revoking new opens cannot retract an FD that Android already handed to a recipient.
        alreadyOpen.use { assertArrayEquals(bytes, it.readBytes()) }; grant.close()
    }

    @Test fun oldExpiryCannotInvalidateNewRecipientProviderRegistration() {
        val policy = FactoryOperationPolicy.inMemory()
        val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 1, "c".repeat(64))
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        fun <T> io(action: () -> T) = worker.submit(java.util.concurrent.Callable(action)).get(5, java.util.concurrent.TimeUnit.SECONDS)
        val coordinator = FactoryFileShareCoordinator(context, { proof }, receive = { request, active ->
            store().stage(request.nonce, request.filename, request.mimeType, bytes.size, digest(bytes), Binder(), active)
        }, clean = { store().cleanup() }, policy = policy)
        val request = FactoryFileShareCoordinator.Request(nonce, "report.txt", "text/plain", bytes.size, digest(bytes), Binder())
        try {
            val oldOwner = io { coordinator.begin(proof.appId, request) {} }
            val oldExpiry = FactoryFileShareCoordinator::class.java.getDeclaredField("expire").apply { isAccessible = true }.get(coordinator) as Runnable
            io { coordinator.close(oldOwner, false) {} }
            val newOwner = io { coordinator.begin(proof.appId, request.copy(nonce = "f".repeat(64))) {} }
            val chooser = io { coordinator.prepareChooser(newOwner, "Current") {} }
            val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            val uri = send.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)!!
            oldExpiry.run()
            assertEquals("chooser_pending", coordinator.status()); assertArrayEquals(bytes, read(uri))
            io { coordinator.close(newOwner, true) {} }
        } finally { worker.shutdownNow(); policy.revokeActive(); java.io.File(context.noBackupFilesDir,"factory-file-sharing").deleteRecursively() }
    }

    @Test fun queryRejectsUnboundedDuplicateAndUnknownProjections() {
        val uri = stage().uri
        for (projection in listOf(emptyArray(), arrayOf("_data"),
            arrayOf(OpenableColumns.SIZE, OpenableColumns.SIZE),
            Array(4096) { OpenableColumns.DISPLAY_NAME })) {
            reject { provider.query(uri, projection, null, null, null) }
        }
        provider.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
            assertEquals(1, it.columnCount)
            assertTrue(it.moveToFirst())
            assertEquals(bytes.size.toLong(), it.getLong(0))
        }
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
