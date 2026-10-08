package com.jarvys.agent.connectors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Public Android OAuth client identifiers only. Does not inspect secrets or contact Google. */
object GoogleConfigurationDiagnostics {
    data class ClientIdentity(val packageName: String, val signingSha1: List<String>)

    @Suppress("DEPRECATION")
    fun identity(context: Context): ClientIdentity {
        val certificates = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners.orEmpty()
            } else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }.getOrDefault(emptyArray())
        return ClientIdentity(context.packageName, certificates.map { fingerprint(it.toByteArray()) }.distinct())
    }

    internal fun fingerprint(certificate: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(certificate).joinToString(":") { "%02X".format(it.toInt() and 0xff) }
}
