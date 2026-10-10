package com.jarvys.agent.apkfactory

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/** Pipe-only provider: no path resolution, files, directories or general storage access. */
class FactoryPhotoProvider : ContentProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        require(info.authority == FactoryPhotoCapture.authority(context) && !info.exported && info.grantUriPermissions)
        super.attachInfo(context, info)
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = try { FactoryPhotoCapture.open(uri, mode) }
        catch (error: Exception) { throw FileNotFoundException("The one-use streaming camera output is unavailable").also { it.initCause(error) } }
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled(); val descriptor = openFile(uri, mode)
        try { signal?.throwIfCanceled(); return descriptor } catch (error: Exception) { descriptor.close(); throw error }
    }
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = deny()
    override fun insert(uri: Uri, values: ContentValues?): Uri = deny()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = deny()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = deny()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = deny()
    private fun deny(): Nothing = throw UnsupportedOperationException("Only the exact one-use camera output can be opened for writing")
}
