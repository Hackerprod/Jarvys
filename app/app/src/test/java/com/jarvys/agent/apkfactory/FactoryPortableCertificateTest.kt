package com.jarvys.agent.apkfactory

import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

/** Production generation is exercised only with disposable, RAM-only host-test identities. */
class FactoryPortableCertificateTest {
    @Test fun generatedIdentityHasStrongMatchingExportableRsaAndSelfSignedCertificate() {
        val privateKey = identity.key as RSAPrivateKey
        val publicKey = identity.certificate.publicKey as RSAPublicKey
        assertEquals(3072, publicKey.modulus.bitLength())
        assertEquals(publicKey.modulus, privateKey.modulus)
        assertEquals("PKCS#8", identity.key.format)
        val encoded = identity.key.encoded
        try { assertTrue(encoded.isNotEmpty()) } finally { encoded.fill(0) }
        identity.certificate.verify(publicKey)
        assertEquals("1.2.840.113549.1.1.11", identity.certificate.sigAlgOID)
        assertEquals(identity.certificate.subjectX500Principal, identity.certificate.issuerX500Principal)
        assertEquals(3, identity.certificate.version)
    }

    @Test fun generatedCertificateHasLongValidityPositiveRandomSerialAndSigningOnlyUsage() {
        val certificate = identity.certificate
        certificate.checkValidity(Date())
        assertTrue(certificate.notBefore.before(Date()))
        assertTrue(certificate.notAfter.time - System.currentTimeMillis() > 49L * 365 * 24 * 60 * 60 * 1000)
        assertTrue(certificate.serialNumber.signum() > 0)
        assertEquals(159, certificate.serialNumber.bitLength())
        assertTrue(certificate.serialNumber.toByteArray().size <= 20)
        assertEquals(-1, certificate.basicConstraints)
        assertTrue(certificate.keyUsage[0])
        assertFalse(certificate.keyUsage.drop(1).any { it })
        assertEquals(setOf("2.5.29.19", "2.5.29.15"), certificate.criticalExtensionOIDs)
        assertFalse(certificate.hasUnsupportedCriticalExtension())
    }

    @Test fun repeatedGenerationNeverReusesKeySerialOrFingerprint() {
        assertNotEquals(identity.certificate.serialNumber, second.certificate.serialNumber)
        assertNotEquals((identity.key as RSAPrivateKey).modulus, (second.key as RSAPrivateKey).modulus)
        assertNotEquals(FactoryIdentityBackup.fingerprint(identity.certificate), FactoryIdentityBackup.fingerprint(second.certificate))
    }

    @Test fun generatedIdentityCanMakeVerifiedSignaturesAndPortableBackups() {
        val payload = "ephemeral production generation test".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").run { initSign(identity.key); update(payload); sign() }
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(identity.certificate.publicKey); update(payload); verify(signed) })
        val material = FactoryIdentityBackup.Material("org.example.generated", identity.key, identity.certificate, 0, null)
        FactoryIdentityBackup.validateMaterial(material)
        val password = "RAM-only generated identity fixture".toCharArray()
        try {
            val restored = FactoryIdentityBackup.decrypt(FactoryIdentityBackup.encrypt(material, password), password)
            assertEquals(FactoryIdentityBackup.fingerprint(identity.certificate), FactoryIdentityBackup.fingerprint(restored.certificate))
        } finally { password.fill('\u0000') }
    }

    @Test fun diagnosticRepresentationIsRedacted() {
        assertEquals("FactoryPortableCertificate.Identity([redacted])", identity.toString())
    }

    companion object {
        private val identity by lazy { FactoryPortableCertificate.generate() }
        private val second by lazy { FactoryPortableCertificate.generate() }
    }
}
