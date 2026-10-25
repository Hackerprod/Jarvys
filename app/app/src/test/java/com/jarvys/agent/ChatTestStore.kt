package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import org.junit.rules.TestRule
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolated Room fixture for tests that exercise the LocalRunStore file constructor. */
class ChatTestStore @JvmOverloads constructor(
    private val runTestOnWorker: Boolean = true,
    private val registerApplicationFilesDirectory: Boolean = false,
    private val bridgeExecutorFactory: (() -> ExecutorService)? = null,
) : TestRule {
    private val temporary = TemporaryFolder()
    private val workerThread = ThreadLocal<Boolean>()
    @Volatile private var workerThreadHandle: Thread? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            workerThread.set(true)
            task.run()
        }, "chat-test-store").apply {
            isDaemon = true
            workerThreadHandle = this
        }
    }
    private val registeredFilesDirectories = ConcurrentHashMap<String, File>()

    override fun apply(base: Statement, description: Description): Statement {
        val closeDatabase = object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    closeRegisteredBridges()
                }
            }
        }
        val withTemporaryFolder = temporary.apply(closeDatabase, description)
        return object : Statement() {
            override fun evaluate() {
                try {
                    if (!runTestOnWorker) ChatDatabaseBridge.mainThreadGuardDisabledForTests = true
                    if (registerApplicationFilesDirectory) {
                        onWorker {
                            registerBridge(ApplicationProvider.getApplicationContext<Context>().filesDir)
                        }
                    }
                    if (runTestOnWorker) onWorker { withTemporaryFolder.evaluate() }
                    else withTemporaryFolder.evaluate()
                } finally {
                    ChatDatabaseBridge.mainThreadGuardDisabledForTests = false
                    worker.shutdown()
                    var interrupted = false
                    val thread = workerThreadHandle
                    if (thread != null) {
                        while (thread.isAlive) {
                            try {
                                thread.join()
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    /** Creates a filesDir under this test's temporary folder. */
    fun newFilesDirectory(vararg directoryNames: String): File =
        if (directoryNames.isEmpty()) temporary.newFolder() else temporary.newFolder(*directoryNames)

    /** Registers an isolated, lazy ChatDatabaseBridge for this filesDir, then returns the facade. */
    fun localRunStore(filesDirectory: File): LocalRunStore {
        registerBridge(filesDirectory)
        return LocalRunStore(filesDirectory)
    }

    /** Runs DB setup and store operations away from Robolectric's main looper. */
    fun <T> withLocalRunStore(filesDirectory: File, operation: StoreOperation<T>): T = onWorker {
        operation.run(localRunStore(filesDirectory))
    }

    /** Runs a block that uses the application-scoped ChatDatabaseBridge away from the main looper. */
    fun <T> onDatabaseWorker(operation: () -> T): T = onWorker(operation)

    fun interface StoreOperation<T> {
        fun run(store: LocalRunStore): T
    }

    private fun registerBridge(filesDirectory: File) {
        val files = filesDirectory.canonicalFile
        check(files.isDirectory || files.mkdirs()) { "Could not create test files directory: $files" }
        val key = files.path
        registeredFilesDirectories.computeIfAbsent(key) {
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            try { androidx.work.WorkManager.getInstance(appContext) }
            catch (_: IllegalStateException) {
                androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
            }
            val context = TestFilesContext(appContext, files)
            val bridgeExecutor = bridgeExecutorFactory?.invoke()
            if (bridgeExecutor == null) ChatDatabaseBridge.getInstance(context)
            else ChatDatabaseBridge.getInstance(context, bridgeExecutor)
            files
        }
    }

    companion object {
        /** Runs Room bridge work inline in tests to avoid a Robolectric background-classloader hop. */
        @JvmStatic
        fun directExecutorService(): ExecutorService = object : AbstractExecutorService() {
            @Volatile private var shutdown = false

            override fun execute(command: Runnable) {
                check(!shutdown) { "Test database executor is shut down" }
                command.run()
            }

            override fun shutdown() { shutdown = true }

            override fun shutdownNow(): MutableList<Runnable> {
                shutdown = true
                return mutableListOf()
            }

            override fun isShutdown(): Boolean = shutdown

            override fun isTerminated(): Boolean = shutdown

            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
        }

    }

    private fun closeRegisteredBridges() {
        registeredFilesDirectories.keys.toList().forEach { key ->
            val files = registeredFilesDirectories.remove(key) ?: return@forEach
            onWorker { ChatDatabaseBridge.closeInstanceForTests(files) }
        }
    }

    private fun <T> onWorker(operation: () -> T): T {
        if (workerThread.get() == true) return operation()
        return try {
            worker.submit(Callable(operation)).get()
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        }
    }

    private class TestFilesContext(base: Context, private val testFilesDirectory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testFilesDirectory
    }
}
