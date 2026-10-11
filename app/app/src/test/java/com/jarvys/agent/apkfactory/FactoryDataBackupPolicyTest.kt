package com.jarvys.agent.apkfactory

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure synthetic APK-entry streams. Installed APK/receipt integration is exercised separately. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class FactoryDataBackupPolicyTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun config(bytes: ByteArray) = FactoryDataBackupPolicy.extractConfig(ByteArrayInputStream(bytes))
    private fun denied(bytes: ByteArray) { assertTrue(runCatching { config(bytes) }.isFailure) }
    @Test fun extractsOnlyExactBoundedConfigurationEntry() {
        assertEquals("{\"synthetic\":true}", config(zip("assets/factory-app.json" to "{\"synthetic\":true}".toByteArray(), "classes.dex" to byteArrayOf(1, 2))))
        denied(zip("factory-app.json" to "{}".toByteArray()))
        denied(zip("assets/../assets/factory-app.json" to "{}".toByteArray()))
        denied(zip("assets/factory-app.json/" to "{}".toByteArray()))
        denied(zip("assets/factory-app.json" to ByteArray(8193) { 32 }))
    }
    @Test fun rejectsMalformedUtf8DuplicateJsonKeysAndNonJsonGrammar() {
        for (bytes in listOf(byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x3a, 0x31, 0x7d), "{\"a\":1,\"a\":2}".toByteArray(), "{a:1}".toByteArray(), "[]".toByteArray()))
            denied(zip("assets/factory-app.json" to bytes))
    }
    @Test fun excessiveEntriesFailEvenWhenConfigurationAppearsFirst() {
        val entries = mutableListOf("assets/factory-app.json" to "{}".toByteArray())
        repeat(FactoryDataBackupPolicy.MAX_ENTRIES) { entries += "synthetic/$it" to byteArrayOf() }
        denied(zip(*entries.toTypedArray()))
    }
    @Test fun openedDescriptorBindsPolicyEvenIfNativePackagePathIsReplaced() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "synthetic-backup-policy.apk")
        val old = zip("assets/factory-app.json" to "{\"policy\":\"original\"}".toByteArray())
        val replacement = zip("assets/factory-app.json" to "{\"policy\":\"swapped!\"}".toByteArray())
        val hash = com.jarvys.factory.runtime.DatabaseBackupFormat.sha256(old)
        file.writeBytes(old)
        java.io.RandomAccessFile(file, "r").use { opened ->
            val moved = java.io.File(context.cacheDir, "synthetic-backup-policy-old.apk")
            check(file.renameTo(moved)); file.writeBytes(replacement)
            try {
                val verified = FactoryDataBackupPolicy.readVerifiedConfig(opened, hash)
                assertEquals(hash, verified.first); assertEquals("{\"policy\":\"original\"}", verified.second)
                assertTrue(runCatching { FactoryDataBackupPolicy.readVerifiedConfig(opened, com.jarvys.factory.runtime.DatabaseBackupFormat.sha256(replacement)) }.isFailure)
            } finally { moved.delete(); file.delete() }
        }
    }
    @Test fun archiveHashCannotAuthorizeDifferentConfigAndInPlaceChangesFail() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "synthetic-backup-policy-mutation.apk")
        val original = zip("assets/factory-app.json" to "{\"policy\":\"one\"}".toByteArray())
        val changed = zip("assets/factory-app.json" to "{\"policy\":\"two\"}".toByteArray())
        val expected = com.jarvys.factory.runtime.DatabaseBackupFormat.sha256(original)
        file.writeBytes(changed)
        java.io.RandomAccessFile(file, "r").use { opened -> assertTrue(runCatching { FactoryDataBackupPolicy.readVerifiedConfig(opened, expected) }.isFailure) }
        file.writeBytes(original)
        object : java.io.RandomAccessFile(file, "r") {
            var changedOnce = false
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val result = super.read(bytes, offset, length)
                if (result == -1 && !changedOnce) { changedOnce = true; file.writeBytes(changed) }
                return result
            }
        }.use { opened -> assertTrue(runCatching { FactoryDataBackupPolicy.readVerifiedConfig(opened, expected) }.isFailure) }
        file.delete()
    }
}
