package com.jarvys.agent.apkfactory

import android.content.Intent
import android.os.Binder
import android.system.Os
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class FactoryFileShareStoreTest : FactoryFileShareTestSupport() {
    @Test fun cleanupGloballyRevokesThenDeletesAndInvalidatesEveryRead() {
        val store = store()
        val snapshot = stage(store)
        store.cleanup()
        assertEquals(listOf(snapshot.uri to Intent.FLAG_GRANT_READ_URI_PERMISSION), context.revocations)
        assertTrue(root.list()!!.isEmpty())
        reject { provider.openFile(snapshot.uri, "r") }
        reject { provider.getType(snapshot.uri) }
        reject { provider.query(snapshot.uri, null, null, null, null) }
        assertNotEquals(snapshot.uri, stage().uri)
    }

    @Test fun failedRevocationRetainsAdmissionUntilCleanupCanRetry() {
        val store = store()
        val snapshot = stage(store)
        context.failRevocation = true
        reject { store.cleanup() }
        reject { provider.getType(snapshot.uri) }
        reject { stage() }
        context.failRevocation = false
        store.cleanup()
        assertEquals(2, context.revocations.size)
        assertEquals(snapshot.uri, context.revocations.last().first)
        stage()
    }

    @Test fun malformedLengthDigestAndCancellationNeverRegisterOrLeaveStaging() {
        for (broken in listOf(store(bytes.copyOf(bytes.size - 1)), store(bytes + 0), store(ByteArray(bytes.size)))) {
            reject { stage(broken) }
            assertTrue(root.list()!!.isEmpty())
        }
        var checks = 0
        reject { stage(checkActive = { checks++; check(checks < 3) }) }
        assertTrue(root.list()!!.isEmpty())
        val snapshot = stage()
        assertArrayEquals(bytes, read(snapshot.uri))
    }

    @Test fun sizesAndNamesAreValidatedBeforeAnyCopy() {
        var copied = false
        val store = FactoryFileShareStore(context) { _, _, _, _, _, _ -> copied = true }
        for (size in listOf(-1, 0, FactoryFileShareStore.MAX_BYTES + 1)) reject { stage(store, size = size) }
        for (filename in listOf("../secret", "a/b", "a\\b", "..", "a\nb", "a.", "x".repeat(121))) reject { stage(store, filename = filename) }
        reject { stage(store, sha256 = "0".repeat(63)) }
        reject { store.stage("guess", "report.txt", "text/plain", bytes.size, digest(bytes), Binder()) {} }
        reject { store.stage(nonce, "report.txt", "*/*", bytes.size, digest(bytes), Binder()) {} }
        assertFalse(copied)
        assertFalse(root.exists())
    }

    @Test fun exactEightMiBSnapshotIsAllowedButNeverOneByteMore() {
        val maximum = ByteArray(FactoryFileShareStore.MAX_BYTES) { (it % 251).toByte() }
        val last = stage(store(maximum), size = maximum.size, sha256 = digest(maximum))
        assertArrayEquals(maximum, read(last.uri))
    }

    @Test fun stagingCannotBeOpenedAndConcurrentAdmissionCannotBeTaken() {
        var checked = false
        val store = FactoryFileShareStore(context) { _, _, _, _, output, _ ->
            val staging = root.listFiles()!!.single()
            assertTrue(staging.name.startsWith("staging-"))
            val uri = FileProvider.getUriForFile(context, FactoryFileShareStore.authority(context), staging)
            reject { provider.openFile(uri, "r") }
            reject { provider.query(uri, null, null, null, null) }
            reject { provider.getType(uri) }
            reject { stage() }
            output.write(bytes)
            checked = true
        }
        stage(store)
        assertTrue(checked)
        reject { stage() }
    }

    @Test fun cleanupDuringCopyCancelsPublicationAndDoesNotReleaseItsAdmissionEarly() {
        val store = FactoryFileShareStore(context) { _, _, _, _, output, _ ->
            output.write(bytes)
            FactoryFileShareStore(context).cleanup()
            reject { stage() }
        }
        reject { stage(store) }
        assertTrue(root.list()!!.isEmpty())
        stage()
    }

    @Test fun cleanupUnlinksSymlinksWithoutFollowingThemAndRejectsNestedDirectories() {
        root.mkdirs()
        Os.chmod(root.path, 448)
        val elsewhere = File(context.cacheDir, "share-test-untouched").apply { writeText("keep") }
        try {
            val symlink = File(root, "snapshot-00000000-0000-0000-0000-000000000000.bin")
            Os.symlink(elsewhere.path, symlink.path)
            store().cleanup()
            assertEquals("keep", elsewhere.readText())
            assertFalse(symlink.exists())
            val nested = File(root, "staging-00000000-0000-0000-0000-000000000000.tmp").apply { mkdir() }
            reject { store().cleanup() }
            assertTrue(nested.isDirectory)
        } finally { elsewhere.delete() }
    }

    @Test fun symlinkedRootNeverStagesOrDeletesOtherFiles() {
        val elsewhere = File(context.cacheDir, "share-test-elsewhere").apply { mkdir() }
        val untouched = File(elsewhere, "snapshot-00000000-0000-0000-0000-000000000000.bin").apply { writeText("keep") }
        try {
            Os.symlink(elsewhere.path, root.path)
            reject { stage() }
            reject { store().cleanup() }
            assertEquals("keep", untouched.readText())
        } finally { root.delete(); elsewhere.deleteRecursively() }
    }
}
