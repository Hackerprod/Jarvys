package com.jarvys.agent.linux

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Seeds the guest's standard ca-certificates path from Android's default trust store. */
object RootfsCertificates {
    fun ensure(rootfs: File, trustManagers: () -> Array<TrustManager> = ::systemTrustManagers,
               fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader) {
        val root = rootfs.canonicalFile
        val certDirectory = checked(root, "etc/ssl/certs")
        val bundle = checked(root, "etc/ssl/certs/ca-certificates.crt")
        ensureContained(root, certDirectory)
        if (fileKindReader.kind(certDirectory) == LinuxFileKind.SYMLINK)
            throw IOException("Guest CA certificates directory must not be a symlink")
        val bundleKind = fileKindReader.kind(bundle)
        if (bundleKind == LinuxFileKind.SYMLINK) throw IOException("CA bundle path must not be a symlink")
        if (bundleKind == LinuxFileKind.OTHER && LinuxFileModes.mode(bundle) == OsConstants.S_IFREG) {
            ensureContained(root, bundle)
            if (bundle.length() > 0) return
        }
        if (!certDirectory.exists() && !certDirectory.mkdirs()) throw IOException("Cannot create guest CA certificates directory")
        ensureContained(root, certDirectory)
        val manager = trustManagers().filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IOException("Android default trust manager is unavailable")
        val issuers = manager.acceptedIssuers
        if (issuers.isEmpty()) throw IOException("Android system trust store contains no accepted CA certificates")
        val pem = buildString {
            for (certificate in issuers) {
                append("-----BEGIN CERTIFICATE-----\n")
                val encoded = android.util.Base64.encodeToString(certificate.encoded, android.util.Base64.NO_WRAP)
                encoded.chunked(64).forEach { append(it).append('\n') }
                append("-----END CERTIFICATE-----\n")
            }
        }
        val temporary = File(certDirectory, "ca-certificates.crt.jarvys-tmp")
        if (fileKindReader.kind(temporary) == LinuxFileKind.SYMLINK)
            throw IOException("Temporary CA bundle path must not be a symlink")
        try {
            val descriptor = Os.open(temporary.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW,
                0x180 /* 0600 */)
            FileOutputStream(descriptor).use { output ->
                output.write(pem.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            Os.chmod(temporary.absolutePath, 0x1A4 /* 0644 */)
            if (!temporary.renameTo(bundle)) throw IOException("Cannot atomically install guest CA bundle")
            ensureContained(root, bundle)
        } finally {
            temporary.delete()
        }
    }

    fun checked(root: File, relative: String): File {
        val candidate = File(root, relative).absoluteFile
        val canonicalParent = candidate.parentFile?.canonicalFile
            ?: throw IOException("Guest path has no parent: ${candidate.path}")
        ensureContained(root, canonicalParent)
        return File(canonicalParent, candidate.name)
    }

    fun ensureContained(root: File, candidate: File) {
        val base = root.canonicalFile.path
        val resolved = candidate.canonicalFile.path
        if (resolved != base && !resolved.startsWith(base + File.separator))
            throw IOException("Guest patch path escapes rootfs: ${candidate.path}")
    }

    private fun systemTrustManagers(): Array<TrustManager> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
    }
}
