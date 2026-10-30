package com.jarvys.agent

import java.io.*

/** Format-specific streaming parser. JSON strings and numeric tokens never become whole payload Strings. */
internal class MemoryImportParser(private val input:Reader,private val cells:OutputStream,
    private val catalog:DataOutputStream,private val token:CancellationToken) {
    data class Result(val counts:Map<String,Long>,val riskyFieldCount:Long,val hasExtension:Boolean)
    private var look=-2
    private var offset=0L
    private var risky=0L
    private var extension=false
    private val counts=LongArray(MemoryImportWire.fields.size)
    private fun fail():Nothing=throw IllegalArgumentException("memory_import_json_invalid")
    private fun peek():Int {token.throwIfCancelled();if(look==-2)look=input.read();return look}
    private fun take():Int {val value=peek();look=-2;return value}
    private fun whitespace(){while(peek() in intArrayOf(32,9,10,13))take()}
    private fun expect(ch:Char){whitespace();if(take()!=ch.code)fail()}
    private fun writeByte(value:Int){cells.write(value);offset=Math.addExact(offset,1L)}
    private fun emit(cp:Int){
        if(cp<0x80)writeByte(cp)
        else if(cp<0x800){writeByte(0xc0 or(cp shr 6));writeByte(0x80 or(cp and 63))}
        else if(cp<0x10000){writeByte(0xe0 or(cp shr 12));writeByte(0x80 or((cp shr 6)and 63));writeByte(0x80 or(cp and 63))}
        else {writeByte(0xf0 or(cp shr 18));writeByte(0x80 or((cp shr 12)and 63));writeByte(0x80 or((cp shr 6)and 63));writeByte(0x80 or(cp and 63))}
    }
    private fun decodedString(accept:(Char)->Unit){
        expect('"')
        while(true){
            val next=take();if(next<0)fail();if(next==34)return
            if(next<32)fail()
            if(next!=92){accept(next.toChar());continue}
            when(val escaped=take()){
                34,92,47->accept(escaped.toChar())
                98->accept('\b');102->accept('\u000c');110->accept('\n');114->accept('\r');116->accept('\t')
                117->{var code=0;repeat(4){val raw=take();val digit=when(raw){in 48..57->raw-48;in 65..70->raw-55;in 97..102->raw-87;else->fail()};code=(code shl 4)or digit};accept(code.toChar())}
                else->fail()
            }
        }
    }
    private fun key():String {
        val key=StringBuilder(48)
        decodedString { ch->if(key.length>=64)fail();key.append(when(ch){in 'A'..'Z'->ch.lowercaseChar();'ſ'->'s';'K'->'k';else->ch}) }
        return key.toString()
    }
    private fun string():MemoryImportCell {
        val start=offset;var high:Char?=null;var flagged=false
        val guard=MemoryExportSecretGuard()
        fun character(ch:Char){
            if(!flagged)try{guard.accept(ch)}catch(failure:IllegalArgumentException){
                if(failure.message!="memory_export_secret_blocked")throw failure
                flagged=true
            }
        }
        fun point(cp:Int){emit(cp);if(cp<=65535)character(cp.toChar())else Character.toChars(cp).forEach(::character)}
        decodedString { ch ->
            val prior=high
            if(prior!=null){
                high=null
                if(Character.isLowSurrogate(ch)){point(Character.toCodePoint(prior,ch));return@decodedString}
                point(0xfffd)
            }
            when {Character.isHighSurrogate(ch)->high=ch;Character.isLowSurrogate(ch)->point(0xfffd);else->point(ch.code)}
        }
        if(high!=null)point(0xfffd)
        if(!flagged)try{guard.finish()}catch(failure:IllegalArgumentException){if(failure.message!="memory_export_secret_blocked")throw failure;flagged=true}
        if(flagged)risky=Math.addExact(risky,1L)
        return MemoryImportCell(MemoryImportWire.STRING,start,offset-start)
    }
    private fun literal(expected:String,type:Int):MemoryImportCell {for(ch in expected)if(take()!=ch.code)fail();return MemoryImportCell(type)}
    private fun number():MemoryImportCell {
        val start=offset
        fun next(){val ch=take();if(ch<0)fail();writeByte(ch)}
        if(peek()==45)next()
        when(peek()){48->{next();if(peek() in 48..57)fail()};in 49..57->{do{next()}while(peek() in 48..57)};else->fail()}
        if(peek()==46){next();if(peek() !in 48..57)fail();do{next()}while(peek() in 48..57)}
        if(peek()==101||peek()==69){next();if(peek()==43||peek()==45)next();if(peek() !in 48..57)fail();do{next()}while(peek() in 48..57)}
        return MemoryImportCell(MemoryImportWire.NUMBER,start,offset-start)
    }
    private fun scalar():MemoryImportCell {whitespace();return when(peek()){
        34->string();110->literal("null",MemoryImportWire.NULL);116->literal("true",MemoryImportWire.TRUE)
        102->literal("false",MemoryImportWire.FALSE);45,in 48..57->number();else->fail()}}
    private fun objectFields(block:(String)->Unit){
        expect('{');whitespace();if(peek()==125){take();return}
        val keys=HashSet<String>() // Known keys only; an unknown key is rejected by the immediate callback.
        while(true){val name=key();if(!keys.add(name))fail();expect(':');block(name);whitespace()
            when(take()){125->return;44->Unit;else->fail()}}
    }
    private fun record(domain:Int){
        val values=MutableList(MemoryImportWire.SLOTS){MemoryImportCell(MemoryImportWire.MISSING)}
        objectFields { key->val index=MemoryImportWire.fields[domain].indexOf(key);if(index<0)fail();values[index]=scalar() }
        append(domain,values)
    }
    private fun append(domain:Int,values:List<MemoryImportCell>){
        catalog.writeInt(domain);catalog.writeLong(counts[domain]);counts[domain]=Math.addExact(counts[domain],1L)
        repeat(MemoryImportWire.SLOTS){val cell=values.getOrElse(it){MemoryImportCell(MemoryImportWire.MISSING)}
            catalog.writeByte(cell.type);catalog.writeLong(cell.offset);catalog.writeLong(cell.length)}
    }
    private fun array(domain:Int){
        whitespace();if(peek()==110){literal("null",MemoryImportWire.NULL);return}
        expect('[');whitespace();if(peek()==93){take();return}
        while(true){record(domain);whitespace();when(take()){93->return;44->Unit;else->fail()}}
    }
    private fun metadataCount(){
        whitespace();var current=peek();val negative=current==45;if(negative){take();current=peek()}
        var value=0L;var digits=0
        while(current in 48..57){
            if(digits>0&&value==0L)fail()
            if(value>(Long.MAX_VALUE-(current-48))/10L)fail()
            value=value*10L+(current-48);digits++;take();current=peek()
        }
        if(digits==0||negative&&value!=0L||current==46||current==101||current==69)fail()
    }
    private fun metadataArray(){
        expect('[');whitespace();if(peek()==93){take();return}
        while(true){if(scalar().type!=MemoryImportWire.STRING)fail();whitespace();when(take()){93->return;44->Unit;else->fail()}}
    }
    private fun extension(){
        extension=true;var version=false
        objectFields { name->when(name){
            "version"->{whitespace();if(take()!=49)fail();if(peek() in 48..57||peek()==46||peek()==101||peek()==69)fail();version=true}
            "observation_fields"->array(MemoryImportWire.RAW)
            "deleted_observations"->array(MemoryImportWire.OBS_MARKERS)
            "deleted_sessions"->array(MemoryImportWire.SESSION_MARKERS)
            "selection"->objectFields { selected->when(selected){
                "projects","supporting_session_projects"->metadataArray()
                "include_prompts"->{if(scalar().type !in setOf(MemoryImportWire.TRUE,MemoryImportWire.FALSE))fail()}
                "supporting_sessions"->metadataCount()
                else->fail()}}
            else->fail()}}
        if(!version)fail()
    }
    fun parse():Result {
        catalog.writeInt(MemoryImportWire.MAGIC)
        val header=MutableList(MemoryImportWire.SLOTS){MemoryImportCell(MemoryImportWire.MISSING)}
        objectFields { name->when(name){
            "version"->header[0]=scalar();"exported_at"->header[1]=scalar()
            "sessions"->array(MemoryImportWire.SESSIONS);"observations"->array(MemoryImportWire.OBSERVATIONS)
            "prompts"->array(MemoryImportWire.PROMPTS);"relations"->array(MemoryImportWire.RELATIONS)
            "prompt_tombstones"->array(MemoryImportWire.PROMPT_MARKERS);"jarvys_extension"->extension()
            else->fail()}}
        whitespace();if(peek()!=-1)fail();append(MemoryImportWire.HEADER,header);catalog.flush();cells.flush()
        return Result(MemoryImportWire.names.indices.filter{it!=0}.associate{MemoryImportWire.names[it] to counts[it]},risky,extension)
    }
}
