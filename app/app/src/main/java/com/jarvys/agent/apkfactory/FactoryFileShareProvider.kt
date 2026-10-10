package com.jarvys.agent.apkfactory

import android.content.ClipDescription
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/** A FileProvider with no filesystem authority beyond the one live immutable share registration. */
class FactoryFileShareProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        require(info.authority == FactoryFileShareStore.authority(context)) { "Invalid file-share authority" }
        require(!info.exported && info.grantUriPermissions) { "Unsafe file-share provider configuration" }
        super.attachInfo(context, info)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("File shares are read only")
        return read(uri) { snapshot, file, identity ->
            // Do not delegate to FileProvider: its canonical path resolution alone cannot pin an inode.
            val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or FactoryFileShareStore.ATOMIC_CLOSE_ON_EXEC, 0)
            try {
                check(FactoryFileShareStore.Identity.file(Os.fstat(descriptor), snapshot.size) == identity)
                ParcelFileDescriptor.dup(descriptor)
            } finally { Os.close(descriptor) }
        }
    }

    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val descriptor = openFile(uri, mode)
        try { signal?.throwIfCanceled(); return descriptor }
        catch (e: Exception) { descriptor.close(); throw e }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor = read(uri) { snapshot, _, _ ->
        require(selection == null && selectionArgs == null && sortOrder == null) { "Unsupported file-share query" }
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        require(requested.size in 1..2) { "Unsupported file-share projection" }
        require(requested.distinct().size == requested.size && requested.all {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }) { "Unsupported file-share projection" }
        val columns = requested.map { it }.toTypedArray()
        MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) snapshot.filename else snapshot.size.toLong() }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String = read(uri) { snapshot, _, _ -> snapshot.mimeType }
    // Newer FileProvider versions otherwise return a generic type even for unregistered files.
    override fun getTypeAnonymous(uri: Uri): String = getType(uri)
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        val type = getType(uri)
        return if (ClipDescription.compareMimeTypes(type, mimeTypeFilter)) arrayOf(type) else null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = denyMutation()
    override fun insert(uri: Uri, values: ContentValues?, extras: Bundle?): Uri = denyMutation()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = denyMutation()
    override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int = denyMutation()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = denyMutation()
    override fun delete(uri: Uri, extras: Bundle?): Int = denyMutation()
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = denyMutation()
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = denyMutation()
    override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = denyMutation()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = denyMutation()
    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle = denyMutation()

    private fun denyMutation(): Nothing = throw UnsupportedOperationException("File shares are read only")
    private fun <T> read(uri: Uri, block: (FactoryFileShareStore.Snapshot, java.io.File, FactoryFileShareStore.Identity) -> T): T {
        try { return FactoryFileShareRegistry.read(context ?: error("Provider is not attached"), uri, block) }
        catch (e: FileNotFoundException) { throw e }
        catch (e: Exception) { throw FileNotFoundException("File share is unavailable").also { it.initCause(e) } }
    }
}
