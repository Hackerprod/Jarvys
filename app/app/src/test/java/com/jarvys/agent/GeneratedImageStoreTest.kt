package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeneratedImageStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun imageSavesAtomicallyUnderItsConversationAndTraversalIsRejected() {
        val store = GeneratedImageStore(temporaryFolder.root)
        val session = "session-42"
        val imageId = UUID.randomUUID().toString()
        val bytes = pngFixture()
        val relativePath = store.save(session, imageId, bytes)
        val image = store.resolve(session, relativePath)
        assertEquals("$imageId.png", relativePath)
        assertTrue(image.readBytes().contentEquals(bytes))
        assertTrue(image.canonicalPath.startsWith(File(temporaryFolder.root, "generated/session-42").canonicalPath))
        assertFalse(image.parentFile!!.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        runCatching { store.resolve(session, "../other/$relativePath") }
            .onSuccess { error("Generated image traversal must be rejected") }
        runCatching { store.save("../outside", UUID.randomUUID().toString(), bytes) }
            .onSuccess { error("Unsafe session ids must be rejected") }
    }

    @Test
    fun imageRowsRoundTripAndConversationDeletionRemovesOnlyThatImageDirectory() {
        val files = temporaryFolder.root
        val runStore = LocalRunStore(files)
        val images = GeneratedImageStore(File(files, "jarvys"))
        val session = "generated-session"
        val relativePath = images.save(session, UUID.randomUUID().toString(), pngFixture())
        runStore.appendConversationMessage(session, "user", "Make a lighthouse.")
        runStore.appendGeneratedImageEvent(session, relativePath, "A cobalt lighthouse", "A blue lighthouse",
            "1024x1024", "image/png")
        val event = runStore.readConversationTimeline(session).single { it.kind == "generated_image" }
        assertEquals(relativePath, event.generatedImagePath)
        assertEquals("A cobalt lighthouse", event.generatedImagePrompt)
        assertEquals("A blue lighthouse", event.generatedImageRevisedPrompt)
        assertEquals("image/png", event.generatedImageMimeType)
        assertEquals("COMPLETED", event.generatedImageStatus)
        assertTrue(images.resolve(session, relativePath).isFile)
        assertTrue(images.deleteImage(session, relativePath))
        val missing = runStore.readConversationTimeline(session).single { it.kind == "generated_image" }
        assertEquals(relativePath, missing.generatedImagePath)
        assertFalse(images.sessionDirectory(session).resolve(relativePath).exists())

        val otherPath = images.save("other-session", UUID.randomUUID().toString(), pngFixture())
        assertTrue(runStore.deleteConversation(session))
        assertFalse(images.sessionDirectory(session).exists())
        assertTrue(images.resolve("other-session", otherPath).isFile)
    }

    @Test
    fun productionContextFilesDirMayHavePlatformSymlinkedAncestor() {
        val real = File(temporaryFolder.root, "real-data").apply { mkdirs() }
        val realFiles = File(real, "files").apply { mkdirs() }
        val alias = File(temporaryFolder.root, "platform-alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val appContext = FilesDirContext(context, File(alias, "files"))
        val store = GeneratedImageStore(appContext)
        val session = "platform-session"
        val imageId = UUID.randomUUID().toString()
        val bytes = pngFixture()

        val relativePath = store.save(session, imageId, bytes)
        val sessionDirectory = store.sessionDirectory(session)
        val image = store.resolve(session, relativePath)
        assertTrue(sessionDirectory.canonicalPath.startsWith(realFiles.canonicalPath))
        assertTrue(store.isGeneratedFile(image))
        assertTrue(image.readBytes().contentEquals(bytes))
        assertTrue(store.deleteImage(session, relativePath))

        val secondPath = store.save(session, UUID.randomUUID().toString(), bytes)
        assertTrue(store.resolve(session, secondPath).isFile)
        assertTrue(store.deleteSession(session))
        assertFalse(store.sessionDirectory(session).exists())
    }

    @Test
    fun symlinksAtOrBelowGeneratedRootRemainRejected() {
        val realFiles = File(temporaryFolder.root, "real-files").apply { mkdirs() }
        val outside = File(temporaryFolder.root, "outside").apply { mkdirs() }
        val jarvys = File(realFiles, "jarvys").apply { mkdirs() }
        Files.createSymbolicLink(File(jarvys, "generated").toPath(), outside.toPath())
        runCatching { GeneratedImageStore(jarvys) }
            .onSuccess { error("generated/ root symlink must be rejected") }

        val store = GeneratedImageStore(File(temporaryFolder.root, "ordinary-jarvys"))
        val generated = store.rootDirectory()
        generated.mkdirs()
        val sessionLink = File(generated, "linked-session")
        Files.createSymbolicLink(sessionLink.toPath(), outside.toPath())
        runCatching { store.sessionDirectory("linked-session") }
            .onSuccess { error("session-directory symlink must be rejected") }
        runCatching { store.deleteSession("linked-session") }
            .onSuccess { error("deleteSession must not traverse a linked session") }

        val regularSession = store.sessionDirectory("regular-session").apply { mkdirs() }
        val externalImage = File(outside, "external.png").apply { writeBytes(pngFixture()) }
        val imageId = UUID.randomUUID().toString() + ".png"
        val imageLink = File(regularSession, imageId)
        Files.createSymbolicLink(imageLink.toPath(), externalImage.toPath())
        runCatching { store.resolve("regular-session", imageId) }
            .onSuccess { error("resolve must reject a linked image") }
        runCatching { store.isGeneratedFile(imageLink) }
            .onSuccess { error("isGeneratedFile must reject a linked image") }
        runCatching { store.deleteImage("regular-session", imageId) }
            .onSuccess { error("deleteImage must not traverse a linked image") }
        assertTrue(externalImage.isFile)
    }

    @Test
    fun liveImageEventRefreshesFromLedgerWithoutDuplicationAndFailedRowsRestore() {
        val store = LocalRunStore(temporaryFolder.root)
        val session = "live-generated-image"
        val images = GeneratedImageStore(File(temporaryFolder.root, "jarvys"))
        val path = images.save(session, UUID.randomUUID().toString(), pngFixture())
        store.appendGeneratedImageEvent(session, path, "A lighthouse", "", "auto", "image/png")
        val restored = store.readConversationTimeline(session)
        AgentRunUiState.resetSession(session)
        AgentRunUiState.restoreSession(session, restored)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.kind == "generated_image" })
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Another request")
        AgentRunUiState.generatedImageAdded(session, restored.single { it.kind == "generated_image" })
        AgentRunUiState.refreshPersistedSession(session, restored)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.kind == "generated_image" })
        val failedText = "The provider returned an incomplete stream."
        store.appendGeneratedImageFailure(session, "An image prompt", failedText)
        AgentRunUiState.refreshPersistedSession(session, store.readConversationTimeline(session))
        assertEquals(1, AgentRunUiState.state.value.events.count { it.kind == "generated_image" && it.generatedImageStatus == "COMPLETED" })
        assertTrue(AgentRunUiState.state.value.events.any { it.kind == "generated_image" && it.generatedImageError == failedText })
    }

    @Test
    fun fileProviderOnlyExposesThePrivateGeneratedSubtreeAndIsNotExported() {
        val store = GeneratedImageStore(context)
        val session = "provider-test"
        val relativePath = store.save(session, UUID.randomUUID().toString(), pngFixture())
        val image = store.resolve(session, relativePath)
        val provider = context.packageManager.getProviderInfo(
            android.content.ComponentName(context, FileProvider::class.java), PackageManager.GET_META_DATA)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val pathsId = provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS")
        val paths = context.resources.getXml(pathsId)
        var entries = 0
        while (paths.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (paths.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && paths.name == "files-path") {
                entries++
                val attributes = (0 until paths.attributeCount).associate { paths.getAttributeName(it) to paths.getAttributeValue(it) }
                assertEquals("generated_images", attributes["name"])
                assertEquals("jarvys/generated/", attributes["path"])
            }
        }
        paths.close()
        assertEquals(1, entries)

        val share = GeneratedImageIntents.shareIntent(context, image)
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("image/png", share.type)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(context.packageName + ".generated-images", uri?.authority)

        val save = ActivityResultContracts.CreateDocument("image/png").createIntent(context, "lighthouse.png")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, save.action)
        assertEquals("image/png", save.type)
        assertEquals("lighthouse.png", save.getStringExtra(Intent.EXTRA_TITLE))

        val outside = File(context.filesDir, "not-generated/outside.png").apply {
            parentFile!!.mkdirs()
            writeBytes(pngFixture())
        }
        runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".generated-images", outside)
        }.onSuccess { error("FileProvider exposed a file outside generated/") }
    }

    private fun pngFixture(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(12, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(120, 45, 30))
        return ByteArrayOutputStream().also { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
    }

    private class FilesDirContext(base: Context, private val filesDirectory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = filesDirectory
    }
}
