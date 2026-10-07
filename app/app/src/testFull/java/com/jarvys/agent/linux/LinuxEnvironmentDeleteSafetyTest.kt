package com.jarvys.agent.linux

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.jarvys.agent.CancellationToken
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinuxEnvironmentDeleteSafetyTest {
    @Test fun execTemporaryCleanupUnlinksAWorkspaceAliasWithoutFollowingIt() {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val sandbox = File(tempRoot, "lf1-process-temp-${System.nanoTime()}").apply { check(mkdirs()) }
        assertUnder(tempRoot, sandbox)
        try {
            val files = File(sandbox, "app/files").apply { mkdirs() }
            val data = File(sandbox, "app/private").apply { mkdirs() }
            val libs = File(sandbox, "app/lib").apply { mkdirs() }
            val memory = File(data, "memory").apply { mkdirs() }
            assertUnder(tempRoot, memory)
            val sentinel = File(memory, "MEMORY.md").apply { writeText("must survive exec cleanup") }
            val appInfo = ApplicationInfo(RuntimeEnvironment.getApplication().applicationInfo).apply {
                nativeLibraryDir = libs.absolutePath
                dataDir = data.absolutePath
            }
            val context = FilesOnlyContext(RuntimeEnvironment.getApplication(), files, appInfo)
            val linux = LinuxEnvironment(context, fileKindReader = NioFileKindReader)
            File(files, "jarvys/linux/rootfs/bin").apply { mkdirs() }
            File(files, "jarvys/linux/rootfs/bin/sh").apply { writeText("#!/bin/sh\n"); check(setExecutable(true, false)) }
            createFakeProot(libs, memory)

            val output = mutableListOf<String>()
            val result = linux.exec("id", "/workspace", 5_000L,
                LinuxOutputCallback { _, text -> output += text }, CancellationToken.cancellable())

            assertEquals(0, result.exitCode)
            assertEquals(listOf("fake proot ran\n"), output)
            assertEquals("must survive exec cleanup", sentinel.readText())
            val tmpRoot = File(files, "jarvys/linux/tmp")
            assertTrue("per-process temp tree remains: ${tmpRoot.listFiles()?.toList()}",
                tmpRoot.listFiles().orEmpty().none { it.name.startsWith("proot-") })
        } finally {
            assertUnder(tempRoot, sandbox)
            RootfsInstaller.deletePrivateTree(sandbox, NioFileKindReader)
        }
    }

    private fun createFakeProot(libs: File, memory: File) {
        val proot = File(libs, "libproot_exec.so")
        proot.writeText("#!/bin/sh\n/bin/ln -s '${memory.absolutePath}' \"\$PROOT_TMP_DIR/escape\"\n/bin/echo 'fake proot ran'\n")
        check(proot.setExecutable(true, true))
        File(libs, "libproot_loader.so").apply { writeText("loader"); setExecutable(true, true) }
        File(libs, "libtalloc.so").writeText("talloc")
        File(libs, "libandroid-shmem.so").writeText("shmem")
    }

    private fun assertUnder(root: File, target: File) {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        assertTrue("refusing destructive process-temp test outside tmp: $targetPath",
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator))
    }

    private class FilesOnlyContext(base: Context, private val files: File,
                                   private val info: ApplicationInfo) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getApplicationInfo(): ApplicationInfo = info
        override fun getCacheDir(): File = File(files, "cache").apply { mkdirs() }
        override fun getCodeCacheDir(): File = File(files, "code_cache").apply { mkdirs() }
    }
}
