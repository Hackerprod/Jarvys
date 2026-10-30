package com.jarvys.agent

import android.content.Context
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Owns one human export lifetime, including idle consent and physical cancellation cleanup. */
class MemoryExportController private constructor(private val context: Context) {
    private val bridge = ChatDatabaseBridge.getInstance(context)
    private class Active(val token: CancellationToken) {
        val handle = MemoryExportOperationHandle()
        val lock = java.lang.Object()
        var worker = false
        var cleaning = false
        var closeUnconfirmed = false
        var prepared: PreparedMemoryExport? = null
        var root: File? = null
        var rootIdentity: Pair<Long,Long>? = null
        var file: File? = null
        var fileIdentity: Pair<Long,Long>? = null
        var unregister: Runnable? = null
        var cleanupResult: MemoryExportCleanupResult? = null
    }
    private val active = AtomicReference<Active?>()
    private val cleanupStatus = AtomicReference<MemoryExportCleanupResult?>()
    fun activeOperation(): MemoryExportOperationHandle? = active.get()?.handle
    fun isRunning(): Boolean = active.get() != null
    fun lastCleanup(): MemoryExportCleanupResult? = cleanupStatus.get()
    fun cancelIfCurrent(expected: MemoryExportOperationHandle): Boolean {
        val operation = active.get() ?: return false
        if (operation.handle !== expected) return false
        operation.token.cancel(); return true
    }
    fun projects(token: CancellationToken): List<String> = bridge.personalAgentMemoryStatsProjects(token)
    private fun requireWorker() { check(Looper.myLooper() != Looper.getMainLooper()) { "memory_export_worker_required" } }
    private fun <T> phase(operation: Active, block: () -> T): T {
        requireWorker()
        operation.token.throwIfCancelled()
        synchronized(operation.lock) {
            check(active.get() === operation && !operation.worker && !operation.cleaning && !operation.closeUnconfirmed) { "memory_export_busy" }
            operation.worker = true
        }
        try { return block() }
        finally { synchronized(operation.lock) { operation.worker = false; operation.lock.notifyAll() } }
    }
    fun prepare(options: MemoryExportOptions, token: CancellationToken): PreparedMemoryExport {
        requireWorker(); token.throwIfCancelled()
        val operation = Active(token)
        check(active.compareAndSet(null,operation)) { "memory_export_busy" }
        cleanupStatus.set(null)
        operation.unregister = token.registerCancelAction {
            if(active.get()===operation) cleanupStatus.set(MemoryExportCleanupResult("pending",true))
            // Never wait for file/provider unwind on the UI cancellation caller.
            CompletableFuture.runAsync { cleanup(operation) }
        }
        try {
            return phase(operation) {
                token.throwIfCancelled()
                val root = Files.createTempDirectory(context.cacheDir.toPath(),"memory-export-").toFile()
                operation.root = root; operation.rootIdentity = identity(root,true)
                Os.chmod(root.absolutePath,448)
                val file=File(root,"snapshot.json"); operation.file=file
                val exportedAt=DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now())
                val snapshot=bridge.exportMemorySnapshot(options,exportedAt,token) {
                    token.throwIfCancelled(); requireRoot(operation)
                    val flags=OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or
                        (if(operation.fileIdentity==null) OsConstants.O_CREAT or OsConstants.O_EXCL else 0)
                    val descriptor=Os.open(file.absolutePath,flags,384)
                    val stream=try { FileOutputStream(descriptor) } catch(failure:Throwable) {
                        try { Os.close(descriptor) } catch(close:Throwable) { operation.closeUnconfirmed=true;failure.addSuppressed(close) }
                        throw failure
                    }
                    try {
                        val stat=Os.fstat(descriptor)
                        check(OsConstants.S_ISREG(stat.st_mode)) { "memory_export_staging_invalid" }
                        val current=stat.st_dev to stat.st_ino
                        check(operation.fileIdentity==null || operation.fileIdentity==current) { "memory_export_staging_changed" }
                        operation.fileIdentity=current
                        requireRoot(operation)
                        Os.ftruncate(descriptor,0L)
                    } catch(failure:Throwable) {
                        try {closeOwned(stream,operation)} catch(close:Throwable) {failure.addSuppressed(close)}
                        throw failure
                    }
                    object:FilterOutputStream(stream) {
                        override fun write(bytes:ByteArray,offset:Int,length:Int)=stream.write(bytes,offset,length)
                        override fun close()=closeOwned(stream,operation)
                    }
                }
                token.throwIfCancelled(); requireRoot(operation)
                check(file.length()==snapshot.sizeBytes) { "memory_export_staging_changed" }
                val sealedIdentity=identity(file,false)
                check(sealedIdentity==operation.fileIdentity) { "memory_export_staging_changed" }
                PreparedMemoryExport(this,file,root,sealedIdentity,checkNotNull(operation.rootIdentity),snapshot,operation.handle)
                    .also { operation.prepared=it; token.throwIfCancelled() }
            }
        } catch(failure:Throwable) { cleanup(operation); throw failure }
    }
    private fun operation(prepared:PreparedMemoryExport):Active {
        require(prepared.owner===this) { "memory_export_preparation_invalid" }
        val operation=active.get()
        check(operation!=null && operation.handle===prepared.operationHandle && operation.prepared===prepared) { "memory_export_preparation_invalid" }
        return operation
    }
    fun preview(prepared:PreparedMemoryExport,offset:Long,maxBytes:Int=32768,token:CancellationToken):MemoryExportPreviewPage {
        val operation=operation(prepared)
        require(token===operation.token) { "memory_export_token_invalid" }
        require(offset>=0 && offset<=prepared.sizeBytes && maxBytes in 4..32768) { "memory_export_preview_invalid" }
        return phase(operation) {
            token.throwIfCancelled(); check(!prepared.consumed.get()); requireArtifact(operation,prepared)
            val input=FileInputStream(prepared.file)
            try {
                requireDescriptor(input,prepared)
                input.channel.position(offset)
                val bytes=ByteArray(minOf(maxBytes.toLong(),prepared.sizeBytes-offset).toInt())
                var size=0
                while(size<bytes.size) { token.throwIfCancelled();val read=input.read(bytes,size,bytes.size-size);check(read>0);size+=read }
                var end=size
                // Leave an incomplete UTF8 sequence for the next page; never replacement-decode a split.
                if(offset+size<prepared.sizeBytes && end>0) {
                    var start=end-1
                    while(start>=0 && (bytes[start].toInt() and 0xc0)==0x80) start--
                    if(start>=0) {
                        val lead=bytes[start].toInt() and 255
                        val length=when {lead<128->1;(lead and 0xe0)==0xc0->2;(lead and 0xf0)==0xe0->3;else->4}
                        if(end-start<length) end=start
                    }
                }
                val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,end)).toString()
                token.throwIfCancelled();requireArtifact(operation,prepared)
                MemoryExportPreviewPage(text,if(offset+end<prepared.sizeBytes)offset+end else null,prepared.sizeBytes)
            } finally { closeOwned(input,operation) }
        }
    }
    /** Own the actual picker and destination metadata call until their physical return. No stream opens here. */
    fun <T> destination(prepared:PreparedMemoryExport,token:CancellationToken,select:()->T):T {
        val operation=operation(prepared)
        require(token===operation.token) { "memory_export_token_invalid" }
        return phase(operation) {
            token.throwIfCancelled();check(!prepared.consumed.get());requireArtifact(operation,prepared)
            val result=select()
            token.throwIfCancelled();requireArtifact(operation,prepared)
            result
        }
    }

    fun write(prepared:PreparedMemoryExport,contentConfirmed:Boolean,destinationConfirmed:Boolean,
        openDestination:()->OutputStream,token:CancellationToken):MemoryExportReceipt {
        val operation=operation(prepared)
        require(token===operation.token) { "memory_export_token_invalid" }
        require(contentConfirmed && destinationConfirmed) { "memory_export_consent_required" }
        try {
            phase(operation) {
                token.throwIfCancelled();check(prepared.consumed.compareAndSet(false,true)) { "memory_export_preparation_consumed" }
                validateSource(prepared,token)
                requireArtifact(operation,prepared)
                val input=FileInputStream(prepared.file)
                try {
                    requireDescriptor(input,prepared)
                    val inspected=copyDigest(input,null,token)
                    check(inspected.first==prepared.sha256 && inspected.second==prepared.sizeBytes) { "memory_export_staging_changed" }
                    input.channel.position(0)
                    token.throwIfCancelled();requireArtifact(operation,prepared);requireDescriptor(input,prepared)
                    val output=openDestination()
                    try {
                        val copied=copyDigest(input,output,token)
                        output.flush()
                        check(copied.first==prepared.sha256 && copied.second==prepared.sizeBytes) { "memory_export_copy_unconfirmed" }
                        requireDescriptor(input,prepared);requireArtifact(operation,prepared);token.throwIfCancelled()
                    } finally { closeOwned(output,operation) }
                    validateSource(prepared,token)
                } finally { closeOwned(input,operation) }
            }
            val cleaned=cleanup(operation)
            token.throwIfCancelled()
            return MemoryExportReceipt("exported",prepared.sizeBytes,prepared.sha256,cleaned.cleanupState,true)
        } catch(failure:Throwable) { cleanup(operation); throw failure }
    }
    private fun validateSource(prepared:PreparedMemoryExport,token:CancellationToken) {
        prepared.snapshot.requireOriginalFile()
        val current=bridge.exportMemorySnapshot(prepared.snapshot.options,prepared.exportedAt,token) { object:OutputStream() {
            override fun write(value:Int)=Unit
            override fun write(bytes:ByteArray,offset:Int,count:Int)=Unit
        } }
        check(current.database===prepared.snapshot.database && current.generation==prepared.snapshot.generation &&
            current.owners==prepared.snapshot.owners && current.projects==prepared.projects &&
            current.sha256==prepared.sha256 && current.sizeBytes==prepared.sizeBytes) { "memory_export_source_changed" }
        prepared.snapshot.requireOriginalFile()
    }
    fun discard(prepared:PreparedMemoryExport,token:CancellationToken):MemoryExportCleanupResult {
        requireWorker(); val operation=operation(prepared)
        prepared.consumed.set(true)
        // Cancellation cannot prevent cleanup of this exact owned object; it grants no data access.
        operation.token.cancel()
        return cleanup(operation)
    }
    private fun copyDigest(input:InputStream,output:OutputStream?,token:CancellationToken):Pair<String,Long> {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(32768);var size=0L
        while(true) { token.throwIfCancelled();val count=input.read(buffer);if(count<0)break
            if(count==0)continue;output?.write(buffer,0,count);digest.update(buffer,0,count);size=Math.addExact(size,count.toLong()) }
        return digest.digest().joinToString(""){"%02x".format(it)} to size
    }
    private fun identity(file:File,directory:Boolean):Pair<Long,Long> {
        val value=Os.lstat(file.absolutePath)
        check(if(directory)OsConstants.S_ISDIR(value.st_mode) else OsConstants.S_ISREG(value.st_mode)) { "memory_export_staging_invalid" }
        return value.st_dev to value.st_ino
    }
    private fun requireRoot(operation:Active) { check(identity(checkNotNull(operation.root),true)==operation.rootIdentity) { "memory_export_staging_changed" } }
    private fun requireArtifact(operation:Active,prepared:PreparedMemoryExport) {
        requireRoot(operation);check(identity(prepared.file,false)==prepared.identity && prepared.file.length()==prepared.sizeBytes) { "memory_export_staging_changed" }
    }
    private fun requireDescriptor(input:FileInputStream,prepared:PreparedMemoryExport) {
        val value=Os.fstat(input.fd)
        check(OsConstants.S_ISREG(value.st_mode) && (value.st_dev to value.st_ino)==prepared.identity && value.st_size==prepared.sizeBytes) { "memory_export_staging_changed" }
    }
    private fun closeOwned(closeable:Closeable,operation:Active) {
        try {closeable.close()} catch(failure:Throwable) {operation.closeUnconfirmed=true;throw failure}
    }
    private fun cleanup(operation:Active):MemoryExportCleanupResult {
        synchronized(operation.lock) {
            while(operation.worker || operation.cleaning) operation.lock.wait()
            if(active.get()!==operation) return checkNotNull(operation.cleanupResult)
            if(operation.closeUnconfirmed) return MemoryExportCleanupResult("pending",true).also { operation.cleanupResult=it;cleanupStatus.set(it) }
            operation.cleaning=true
        }
        var success=true
        try {
            operation.prepared?.consumed?.set(true)
            val root=operation.root
            if(root!=null) {
                if(operation.rootIdentity==null || runCatching {identity(root,true)==operation.rootIdentity}.getOrDefault(false).not()) success=false
                else {
                    operation.file?.let { file ->
                        // The private directory is not permission to delete a substituted inode.
                        val current=try { identity(file,false) } catch(missing:android.system.ErrnoException) {
                            if(missing.errno==OsConstants.ENOENT) null else throw missing
                        }
                        if(current!=null) {
                            if(operation.fileIdentity==null || current!=operation.fileIdentity) success=false
                            else if(!file.delete()) success=false
                        }
                    }
                    if(success && root.exists() && !root.delete())success=false
                }
            }
        } catch(_:Exception) {success=false}
        finally {
            operation.unregister?.run()
            synchronized(operation.lock) {
                operation.cleanupResult=MemoryExportCleanupResult(if(success)"cleaned" else "failed",false)
                cleanupStatus.set(operation.cleanupResult)
                active.compareAndSet(operation,null)
                operation.cleaning=false;operation.lock.notifyAll()
            }
        }
        return checkNotNull(operation.cleanupResult)
    }
    companion object {
        private val instances=ConcurrentHashMap<String,MemoryExportController>()
        @JvmStatic fun get(context:Context):MemoryExportController {
            val app=context.applicationContext
            return instances.computeIfAbsent(app.filesDir.canonicalPath) {MemoryExportController(app)}
        }
    }
}
