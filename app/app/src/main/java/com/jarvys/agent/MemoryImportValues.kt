package com.jarvys.agent

import com.jarvys.agent.chatdb.MemoryObservationContent
import java.io.InputStream
import java.math.BigInteger

/** Bounded typed decoding of catalog cells; no payload or opaque identifier is materialized here. */
internal object MemoryImportValues {
    fun nullable(cell:MemoryImportCell)=cell.type==MemoryImportWire.NULL||cell.type==MemoryImportWire.MISSING
    fun string(cell:MemoryImportCell,allowNull:Boolean=true) {
        require(cell.type==MemoryImportWire.STRING || allowNull&&nullable(cell)){"memory_import_field_type"}
    }
    fun blank(data:MemoryImportData,cell:MemoryImportCell,token:CancellationToken):Boolean {
        string(cell);if(nullable(cell))return true
        data.reader(cell).use {r->while(true){token.throwIfCancelled();val ch=r.read();if(ch<0)return true;if(!MemoryObservationContent.isGoSpace(ch))return false}}
    }
    fun integer(data:MemoryImportData,cell:MemoryImportCell,token:CancellationToken,default:Long=0):Long {
        if(nullable(cell))return default
        require(cell.type==MemoryImportWire.NUMBER){"memory_import_field_type"}
        data.open(cell).use {input->
            var c=input.read();val negative=c==45;if(negative)c=input.read()
            var value=0L;val limit=if(negative)Long.MIN_VALUE else -Long.MAX_VALUE;val mult=limit/10
            require(c in 48..57){"memory_import_integer_invalid"}
            while(c>=0){token.throwIfCancelled();require(c in 48..57 && value>=mult){"memory_import_integer_invalid"}
                value*=10;val digit=c-48;require(value>=limit+digit){"memory_import_integer_invalid"};value-=digit;c=input.read()}
            return if(negative)value else -value
        }
    }
    fun bool(cell:MemoryImportCell):Boolean {require(nullable(cell)||cell.type in setOf(MemoryImportWire.TRUE,MemoryImportWire.FALSE)){"memory_import_field_type"};return cell.type==MemoryImportWire.TRUE}
    /** Go scalar float decoding, with a bounded significand sufficient for IEEE754 rounding. */
    fun decimal(data:MemoryImportData,cell:MemoryImportCell,token:CancellationToken):Double? {
        if(nullable(cell))return null
        require(cell.type==MemoryImportWire.NUMBER){"memory_import_field_type"}
        data.open(cell).use {input->
            var c=input.read();val negative=c==45;if(negative)c=input.read()
            var before=0L;var leading=0L;var fraction=false;var significant=false;var sticky=false
            val digits=StringBuilder(769)
            while(c>=0 && c!=101 && c!=69){token.throwIfCancelled()
                if(c==46)fraction=true else {require(c in 48..57){"memory_import_number_invalid"}
                    if(!fraction)before=Math.addExact(before,1L)
                    if(!significant&&c==48)leading=Math.addExact(leading,1L)
                    else {significant=true;if(digits.length<768)digits.append(c.toChar())else if(c!=48)sticky=true}}
                c=input.read()
            }
            var exponent=BigInteger.ZERO;var expNegative=false;var expDigits=0;var huge=false
            if(c>=0){c=input.read();if(c==43||c==45){expNegative=c==45;c=input.read()}
                while(c>=0){token.throwIfCancelled();require(c in 48..57){"memory_import_number_invalid"}
                    if(expDigits!=0||c!=48){expDigits=(expDigits+1).coerceAtMost(21);if(expDigits<=20)exponent=exponent*BigInteger.TEN+BigInteger.valueOf((c-48).toLong())else huge=true};c=input.read()}}
            if(!significant)return if(negative)-0.0 else 0.0
            if(huge){require(expNegative){"memory_import_number_invalid"};return if(negative)-0.0 else 0.0}
            if(expNegative)exponent=exponent.negate()
            exponent=exponent+BigInteger.valueOf(before)-BigInteger.valueOf(leading)-BigInteger.ONE
            require(exponent<=BigInteger.valueOf(309)){"memory_import_number_invalid"}
            if(exponent<BigInteger.valueOf(-400))return if(negative)-0.0 else 0.0
            // Any discarded nonzero tail lies beyond all binary64 midpoint expansions; retain a sticky digit.
            val normalized=(if(negative)"-" else "")+digits[0]+"."+digits.substring(1)+(if(sticky)"1" else "")+"e"+exponent
            return normalized.toDouble().also {require(it.isFinite()){ "memory_import_number_invalid" }}
        }
    }
    fun ownershipMode(data:MemoryImportData,cell:MemoryImportCell,token:CancellationToken):String {
        if(blank(data,cell,token))return "shared"
        return when {data.equals(cell,"shared",token)->"shared";data.equals(cell,"project_owned",token)->"project_owned"
            else->throw IllegalArgumentException("memory_import_ownership_mode_invalid")}
    }
    fun enumValue(data:MemoryImportData,cell:MemoryImportCell,allowed:Set<String>,default:String?,lower:Boolean,token:CancellationToken):String? {
        string(cell);if(nullable(cell))return default
        val max=allowed.maxOfOrNull {it.length}?:0;val value=StringBuilder(max+1);var trailing=false
        data.reader(cell).use {r->while(true){token.throwIfCancelled();val cp=r.read();if(cp<0)break
            if(MemoryObservationContent.isGoSpace(cp)){if(value.isNotEmpty())trailing=true;continue}
            if(trailing||value.length>=max)return null
            value.append(if(lower)Character.toLowerCase(cp).toChar()else cp.toChar())}}
        if(value.isEmpty())return default
        return value.toString().takeIf{it in allowed}
    }
    fun base64(input:InputStream,length:Long):InputStream {
        require(length%4L==0L){"memory_import_base64_invalid"}
        return object:InputStream(){
            private val decoded=ByteArray(3);private var size=0;private var position=0;private var remaining=length;private var ended=false
            private fun digit(c:Int):Int=when(c){in 65..90->c-65;in 97..122->c-71;in 48..57->c+4;43->62;47->63;else->throw IllegalArgumentException("memory_import_base64_invalid")}
            private fun refill():Boolean {
                if(remaining==0L)return false
                require(!ended){"memory_import_base64_invalid"}
                val a=input.read();val b=input.read();val c=input.read();val d=input.read();remaining-=4
                require(a>=0&&b>=0&&c>=0&&d>=0){"memory_import_base64_invalid"}
                val x=digit(a);val y=digit(b);decoded[0]=((x shl 2)or(y shr 4)).toByte();size=1
                if(c==61){require(d==61 && (y and 15)==0 && remaining==0L){"memory_import_base64_invalid"};ended=true}
                else {val z=digit(c);decoded[1]=((y shl 4)or(z shr 2)).toByte();size=2
                    if(d==61){require((z and 3)==0 && remaining==0L){"memory_import_base64_invalid"};ended=true}
                    else {decoded[2]=((z shl 6)or digit(d)).toByte();size=3}}
                position=0;return true
            }
            override fun read():Int {if(position==size&&!refill())return -1;return decoded[position++].toInt()and 255}
            override fun read(b:ByteArray,off:Int,len:Int):Int {if(len==0)return 0;if(position==size&&!refill())return -1
                val count=minOf(len,size-position);decoded.copyInto(b,off,position,position+count);position+=count;return count}
            override fun close()=input.close()
        }
    }
}
