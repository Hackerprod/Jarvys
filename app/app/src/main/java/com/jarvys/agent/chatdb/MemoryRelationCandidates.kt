package com.jarvys.agent.chatdb

/** Candidate questions are distinct from explicit memory_links and are not judgments. */
object MemoryRelationCandidates {
    private val stop = "a an the and or for to of in on with from by at as is are be was were we it this that use used using add added update updated fix fixed implement implemented".split(' ').toSet()
    @JvmStatic fun candidateTerms(title: String): List<String> = MemoryObservationContent.fields(title).mapNotNull { raw ->
        val term=raw.trim('"')
        if(term.isEmpty()) null else buildString {
            append('"'); var i=0
            while(i<term.length) {
                val c=term[i++]; append(c)
                if(c=='"') { append('"'); if(i<term.length && term[i]=='"') i++ }
            }
            append('"')
        }
    }
    @JvmStatic fun relevanceTerms(title: String): List<String> {
        val result=java.util.TreeSet<String> { a,b ->
            var x=0; var y=0; var order=0
            while(x<a.length && y<b.length && order==0) {
                val ac=a.codePointAt(x); val bc=b.codePointAt(y); order=ac.compareTo(bc)
                x+=Character.charCount(ac); y+=Character.charCount(bc)
            }
            if(order!=0) order else (a.length-x).compareTo(b.length-y)
        }; val word=StringBuilder()
        fun flush() { if(word.isNotEmpty()) { val value=word.toString(); if(value !in stop && value.codePoints().anyMatch { Character.isLetter(it)||Character.isDigit(it) }) result.add(value); word.setLength(0) } }
        var i=0
        while(i<title.length) {
            val cp=Character.toLowerCase(title.codePointAt(i)); i+=Character.charCount(title.codePointAt(i))
            if(Character.isLetter(cp)||Character.isDigit(cp)||cp==43||cp==35) word.appendCodePoint(cp) else flush()
        }
        flush(); return result.toList()
    }
    @JvmStatic fun relevant(sourceTitle:String,sourceTopic:ByteArray?,targetTitle:String,targetTopic:ByteArray?):Boolean {
        if(sourceTopic!=null && sourceTopic.isNotEmpty() && targetTopic!=null && sourceTopic.contentEquals(targetTopic)) return true
        val a=relevanceTerms(sourceTitle).toSet(); val b=relevanceTerms(targetTitle).toSet(); val common=a.intersect(b).size
        return common>=2 || common==1 && a.size==1 && b.size==1
    }
}

data class MemoryRelationCandidate(val judgmentId:String,val id:String,val title:String,val type:String,
    val topicKey:String?,val score:Double,val titleBytesBase64:String?=null,val titleIsUtf8:Boolean?=null,
    val topicKeyBytesBase64:String?=null,val topicKeyIsUtf8:Boolean?=null)
data class MemoryCandidateResult(val status:String,val candidates:List<MemoryRelationCandidate>,val problem:String?=null)
data class MemoryRelationView(val judgmentId:String,val sourceId:String,val targetId:String,
    val sourceTitle:String,val targetTitle:String,val sourceMissing:Boolean,val targetMissing:Boolean,
    val relation:String,val status:String,val createdAt:Long,val direction:String?=null,
    val sourceTitleBytesBase64:String?=null,val sourceTitleIsUtf8:Boolean?=null,
    val targetTitleBytesBase64:String?=null,val targetTitleIsUtf8:Boolean?=null,
    val reason:String?=null,val evidence:String?=null,val confidence:Double?=null,
    val markedByActor:String?=null,val markedByKind:String?=null,val markedByModel:String?=null,
    val memorySessionId:String?=null,val updatedAt:Long=createdAt)
data class MemoryRelationPage(val items:List<MemoryRelationView>,val total:Long,val hasMore:Boolean,val nextOffset:Int?)

/** Displayed evidence preconditions, never ownership authority. */
class MemoryRelationDetails internal constructor(val relation:MemoryRelationView,val source:PersonalMemoryHit?,val target:PersonalMemoryHit?,
    internal val sourceSnapshot:MemoryRelationSnapshot?,internal val targetSnapshot:MemoryRelationSnapshot?)
data class MemoryJudgmentOutcome(val index:Int,val judgmentId:String?,val status:String,val relation:MemoryRelationView?=null,val error:String?=null)
data class MemoryJudgmentResult(val outcomes:List<MemoryJudgmentOutcome>,val stopped:Boolean=false,val problem:String?=null)

/** Known failed item transaction, distinct from a transport failure after an unknown commit ACK. */
class MemoryJudgmentSqlItemException internal constructor(cause:Throwable):IllegalStateException("memory_judgment_sql_item_failed",cause)

/** Narrow negative classifications, never permission or a generic effect ledger. */
class MemoryJudgmentStorageCorruptionException internal constructor(cause:Throwable):IllegalStateException("memory_judgment_storage_corrupt",cause)
class MemoryJudgmentSourceResolutionException internal constructor(cause:Throwable):IllegalStateException("memory_judgment_registry_or_source_error",cause)
