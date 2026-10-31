package com.jarvys.agent.coding

import com.jarvys.agent.CancellationToken
import com.jarvys.factory.contract.ResourceBudgets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryPackagingSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private fun fixture(): ProjectScope = ProjectScopeStore(folder.newFolder()).open("packaging").also {
        File(it.rootDirectory(), "web").mkdir()
        File(it.rootDirectory(), "web/index.html").writeText("index")
    }
    @Test fun signedBudgetsAllowOver128FilesOver256EntriesAndOver12Directories() {
        val scope = fixture()
        repeat(299) { File(scope.rootDirectory(), "web/f$it.js").writeText("file") }
        val nested = File(scope.rootDirectory(), "web/" + List(14) { "d" }.joinToString("/"))
        nested.mkdirs(); File(nested, "end.css").writeText("nested")
        val plan = FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, CancellationToken.cancellable())
        assertEquals(301, plan.sizes.size)
        assertEquals(plan.totalBytes, FactoryProjectFiles.readWebsite(scope, plan, CancellationToken.cancellable()).values.sumOf { it.size.toLong() })
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.website(scope, "web", CancellationToken.cancellable()) }
    }
    @Test fun raisedFileAndTotalBytesReadAndRehashWithoutOldOneOrEightMiBLimits() {
        val scope = fixture()
        val content = ByteArray(9 * 1024 * 1024) { 42 }
        File(scope.rootDirectory(), "web/large.bin").writeBytes(content)
        val web = FactoryProjectFiles.website(scope, "web", CancellationToken.cancellable(), ResourceBudgets.maxima().packaging)
        assertArrayEquals(content, web.getValue("large.bin"))
        assertEquals(ProjectScope.sha256(content), FactoryProjectFiles.sha(scope, "web/large.bin", ResourceBudgets.maxima().packaging.singleSourceBytes.toInt(), CancellationToken.cancellable()))
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.website(scope, "web", CancellationToken.cancellable()) }
    }
    @Test fun growthAfterPlanningCancellationAndSymlinksFailClosed() {
        val scope = fixture()
        val token = CancellationToken.cancellable()
        val plan = FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, token)
        File(scope.rootDirectory(), "web/index.html").appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.readWebsite(scope, plan, token) }
        token.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, token) }
        Files.createSymbolicLink(File(scope.rootDirectory(), "web/link").toPath(), File(scope.rootDirectory(), "web/index.html").toPath())
        assertThrows(Exception::class.java) { FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, CancellationToken.cancellable()) }
    }
    @Test fun declaredTotalSingleAndPathDerivedDirectoryBoundsFailBeforeReading() {
        val scope = fixture()
        val token = CancellationToken.cancellable()
        File(scope.rootDirectory(), "web/second").writeBytes(ByteArray(16))
        val small = ResourceBudgets.parse(mapOf("version" to 1, "packaging" to mapOf("totalSourceBytes" to 20, "singleSourceBytes" to 20))).packaging
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.planWebsite(scope, "web", small, token) }
        File(scope.rootDirectory(), "web/second").delete()
        java.io.RandomAccessFile(File(scope.rootDirectory(), "web/too-large"), "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, token) }
        File(scope.rootDirectory(), "web/too-large").delete()
        repeat(121) { File(scope.rootDirectory(), "web/empty$it").mkdir() }
        val one = ResourceBudgets.parse(mapOf("version" to 1, "packaging" to mapOf("sourceFiles" to 1))).packaging
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.planWebsite(scope, "web", one, token) }
    }
    @Test fun full512SourcePlanAndDeclaredAggregateLimitsAreEnforced() {
        val scope = fixture()
        repeat(511) { File(scope.rootDirectory(), "web/f$it").writeText("x") }
        assertEquals(512, FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, CancellationToken.cancellable()).sizes.size)
        File(scope.rootDirectory(), "web/overflow").writeText("x")
        assertThrows(IllegalArgumentException::class.java) { FactoryProjectFiles.planWebsite(scope, "web", ResourceBudgets.maxima().packaging, CancellationToken.cancellable()) }
    }
}
