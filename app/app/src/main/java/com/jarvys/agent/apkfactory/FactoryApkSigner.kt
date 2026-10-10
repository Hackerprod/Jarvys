package com.jarvys.agent.apkfactory

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.jarvys.agent.coding.ProjectScope
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** apksig is AOSP's Apache-2.0 verifier/signer. Signing is followed by independent verification. */
internal object FactoryApkSigner {
    fun verify(file: File): String {
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(24).build().verify()
        check(result.isVerified && result.isVerifiedUsingV2Scheme && result.signerCertificates.size == 1) {
            "Generated APK signature did not verify"
        }
        return ProjectScope.sha256(result.signerCertificates.single().encoded)
    }
    fun sign(input: File, output: File, key: PrivateKey, certificate: X509Certificate): String {
        val signer = ApkSigner.SignerConfig.Builder("generated-app", key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer)).setInputApk(input).setOutputApk(output)
            .setMinSdkVersion(24).setV1SigningEnabled(false).setV2SigningEnabled(true)
            .setV3SigningEnabled(true).setV4SigningEnabled(false).setDebuggableApkPermitted(false)
            .setOtherSignersSignaturesPreserved(false).setAlignmentPreserved(true).build().sign()
        val result = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify()
        check(result.isVerified && result.isVerifiedUsingV2Scheme && result.signerCertificates.size == 1) {
            "Generated APK signature did not verify; no project output was published"
        }
        val fingerprint = ProjectScope.sha256(result.signerCertificates.single().encoded)
        check(fingerprint == ProjectScope.sha256(certificate.encoded)) { "Generated APK certificate mismatch" }
        return fingerprint
    }
}
