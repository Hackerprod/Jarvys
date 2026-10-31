package com.jarvys.agent.apkfactory

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Synthetic certificate construction for RAM-only host signing fixtures. */
internal object EphemeralFactoryCertificate {
    fun create(publicKey:ByteArray,sign:(ByteArray)->ByteArray):X509Certificate {
        val algorithm=der(0x30,hex("06092a864886f70d01010b"),der(5,byteArrayOf()))
        val name=der(0x30,der(0x31,der(0x30,hex("0603550403"),der(12,"Ephemeral factory test".toByteArray()))))
        val time=der(0x30,der(23,"260101000000Z".toByteArray()),der(23,"460101000000Z".toByteArray()))
        val tbs=der(0x30,der(0xa0,der(2,byteArrayOf(2))),der(2,BigInteger.ONE.toByteArray()),algorithm,name,time,name,publicKey)
        val encoded=der(0x30,tbs,algorithm,der(3,byteArrayOf(0)+sign(tbs)))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
    }
    private fun der(tag:Int,vararg pieces:ByteArray):ByteArray {
        val body=pieces.fold(byteArrayOf()){acc,item->acc+item}
        val length=when {body.size<128->byteArrayOf(body.size.toByte());body.size<256->byteArrayOf(0x81.toByte(),body.size.toByte());else->byteArrayOf(0x82.toByte(),(body.size ushr 8).toByte(),body.size.toByte())}
        return byteArrayOf(tag.toByte())+length+body
    }
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
}
