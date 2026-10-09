package com.jarvys.agent.apkfactory

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Generates a fresh portable identity in RAM only. Persistence and approval belong to the caller. */
internal object FactoryPortableCertificate {
    data class Identity(val key: PrivateKey, val certificate: X509Certificate) {
        override fun toString(): String = "FactoryPortableCertificate.Identity([redacted])"
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val VALIDITY_MILLIS = 50L * 366 * DAY_MILLIS

    fun generate(): Identity {
        val random = SecureRandom()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, random) }.generateKeyPair()
        try {
            return Identity(pair.private, certificate(pair, random, System.currentTimeMillis()))
        } catch (_: Exception) {
            FactoryIdentityBackup.destroyBestEffort(pair.private)
            throw IllegalStateException("Cannot create portable signing identity")
        }
    }

    private fun certificate(pair: KeyPair, random: SecureRandom, now: Long): X509Certificate {
        val algorithm = der(0x30, byteArrayOf(6, 9, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 1, 1, 0x0b), der(5))
        val commonName = der(0x30, byteArrayOf(6, 3, 0x55, 4, 3), der(12, "Jarvys Generated App".toByteArray(Charsets.UTF_8)))
        val name = der(0x30, der(0x31, commonName))
        val validity = der(0x30, time(now - DAY_MILLIS), time(now + VALIDITY_MILLIS))
        // Positive 159-bit serial, at most 20 DER octets; never a fixed or timestamp-only serial.
        val serial = BigInteger(159, random).setBit(158)
        val basicConstraints = der(0x30, byteArrayOf(6, 3, 0x55, 0x1d, 0x13), der(1, byteArrayOf(0xff.toByte())), der(4, der(0x30)))
        val keyUsage = der(0x30, byteArrayOf(6, 3, 0x55, 0x1d, 0x0f), der(1, byteArrayOf(0xff.toByte())),
            der(4, der(3, byteArrayOf(7, 0x80.toByte()))))
        val extensions = der(0xa3, der(0x30, basicConstraints, keyUsage))
        val tbs = der(0x30, der(0xa0, der(2, byteArrayOf(2))), der(2, serial.toByteArray()),
            algorithm, name, validity, name, pair.public.encoded, extensions)
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private, random); update(tbs); sign() }
        val encoded = der(0x30, tbs, algorithm, der(3, byteArrayOf(0), signature))
        return (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate).also {
            it.verify(pair.public)
            it.checkValidity(Date(now))
        }
    }

    private fun time(millis: Long): ByteArray {
        val date = Date(millis)
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date).toInt()
        val utc = year in 1950..2049
        val format = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(if (utc) 23 else 24, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun der(tag: Int, vararg pieces: ByteArray): ByteArray {
        val size = pieces.sumOf { it.size }
        require(size <= 65535)
        val length = when {
            size < 128 -> byteArrayOf(size.toByte())
            size < 256 -> byteArrayOf(0x81.toByte(), size.toByte())
            else -> byteArrayOf(0x82.toByte(), (size ushr 8).toByte(), size.toByte())
        }
        val encoded = ByteArray(1 + length.size + size)
        encoded[0] = tag.toByte()
        length.copyInto(encoded, 1)
        var offset = 1 + length.size
        for (piece in pieces) { piece.copyInto(encoded, offset); offset += piece.size }
        return encoded
    }
}
