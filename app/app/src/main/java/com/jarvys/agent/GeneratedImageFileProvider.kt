package com.jarvys.agent

import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/** Shared image grants use the same admission barrier as in-process conversation stores. */
class GeneratedImageFileProvider : FileProvider() {
    private fun awaitStorage() = FreshStorageBootstrap.awaitReadyBlocking(requireNotNull(context).filesDir)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        awaitStorage()
        return super.openFile(uri, mode)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        awaitStorage()
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        awaitStorage()
        return super.delete(uri, selection, selectionArgs)
    }
}
