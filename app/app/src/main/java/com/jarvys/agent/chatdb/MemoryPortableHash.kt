package com.jarvys.agent.chatdb

import com.jarvys.agent.CancellationToken
import java.io.Reader
import java.security.MessageDigest

/** Incremental equivalent of normalizedHash on the validated Unicode body that will be stored. */
internal object MemoryPortableHash {
    fun compute(reader:Reader,token:CancellationToken):String {
        val digest=MessageDigest.getInstance("SHA-256")
        var emitted=false;var pendingSpace=false;var high:Char?=null
        fun emit(cp:Int) {
            if(cp<0x80)digest.update(cp.toByte())
            else if(cp<0x800) {digest.update((0xc0 or (cp shr 6)).toByte());digest.update((0x80 or (cp and 63)).toByte())}
            else if(cp<0x10000) {digest.update((0xe0 or (cp shr 12)).toByte());digest.update((0x80 or ((cp shr 6) and 63)).toByte());digest.update((0x80 or (cp and 63)).toByte())}
            else {digest.update((0xf0 or (cp shr 18)).toByte());digest.update((0x80 or ((cp shr 12) and 63)).toByte());digest.update((0x80 or ((cp shr 6) and 63)).toByte());digest.update((0x80 or (cp and 63)).toByte())}
        }
        fun point(cp:Int) {
            if(MemoryObservationContent.isGoSpace(cp))pendingSpace=emitted
            else {if(pendingSpace)emit(32);pendingSpace=false;emit(Character.toLowerCase(cp));emitted=true}
        }
        val buffer=CharArray(4096)
        while(true) {
            token.throwIfCancelled();val count=reader.read(buffer);if(count<0)break
            for(i in 0 until count) {
                val ch=buffer[i];val prior=high
                if(prior!=null) {check(Character.isLowSurrogate(ch)) {"memory_import_unicode_invalid"};high=null;point(Character.toCodePoint(prior,ch))}
                else if(Character.isHighSurrogate(ch))high=ch
                else {check(!Character.isLowSurrogate(ch)) {"memory_import_unicode_invalid"};point(ch.code)}
            }
        }
        check(high==null) {"memory_import_unicode_invalid"}
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
