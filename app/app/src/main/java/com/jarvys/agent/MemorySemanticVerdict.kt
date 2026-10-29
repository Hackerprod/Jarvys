package com.jarvys.agent

import android.util.JsonReader
import android.util.JsonToken
import com.jarvys.agent.chatdb.MemoryObservationContent
import java.io.StringReader
import java.math.BigDecimal

/** Actual assistant-text decoding, independent of caller-authored P4-C arguments. */
data class MemorySemanticVerdict(val relation:String,val confidence:Double,val reasoning:String)
object MemorySemanticVerdictParser {
    private val verbs=setOf("related","compatible","scoped","conflicts_with","supersedes","not_conflict")
    private val fence=Regex("(?s)^```[a-zA-Z]*\\n?(.+?)\\n?```$")
    private fun field(raw:String)=buildString {
        raw.forEach { c -> append(when(c) { in 'A'..'Z' -> c.lowercaseChar(); '\u017f' -> 's'; else -> c }) }
    }
    @JvmStatic fun parse(raw:String):MemorySemanticVerdict {
        var text=MemoryObservationContent.trimSpace(raw)
        fence.matchEntire(text)?.let { text=MemoryObservationContent.trimSpace(it.groupValues[1]) }
        var relation=""; var reasoning=""; var confidence=BigDecimal.ZERO; var invalid=false
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient=false
                reader.beginObject()
                while(reader.hasNext()) {
                    val name=field(reader.nextName())
                    if(name !in setOf("relation","reasoning","confidence","model")) { reader.skipValue(); continue }
                    if(reader.peek()==JsonToken.NULL) { reader.nextNull(); continue }
                    if(name=="confidence") {
                        if(reader.peek()!=JsonToken.NUMBER) { invalid=true; reader.skipValue() }
                        else { confidence=BigDecimal(reader.nextString()); if(!confidence.toDouble().isFinite()) invalid=true }
                    } else if(reader.peek()!=JsonToken.STRING) { invalid=true; reader.skipValue() }
                    else {
                        val value=reader.nextString()
                        when(name) { "relation" -> relation=value; "reasoning" -> reasoning=value }
                        // Model scalar is validated and discarded, never transport provenance.
                    }
                }
                reader.endObject()
                if(reader.peek()!=JsonToken.END_DOCUMENT) invalid=true
            }
        } catch(failure:Exception) { throw IllegalArgumentException("memory_semantic_invalid_json",failure) }
        require(!invalid) { "memory_semantic_invalid_json" }
        require(relation in verbs) { "memory_semantic_invalid_relation" }
        require(confidence>=BigDecimal.ZERO && confidence<=BigDecimal.ONE && confidence.toDouble().isFinite()) { "memory_semantic_invalid_confidence" }
        return MemorySemanticVerdict(relation,confidence.toDouble(),MemoryObservationContent.prepareTitle(reasoning))
    }
}

object MemorySemanticPrompt {
    private val template="""You are a semantic memory auditor. Compare the two observations below and classify their relationship.

=== Observation A ===
ID: %1${'$'}s
Title: %2${'$'}s
Content: %3${'$'}s

=== Observation B ===
ID: %4${'$'}s
Title: %5${'$'}s
Content: %6${'$'}s

Choose EXACTLY ONE relation from this locked vocabulary:
- conflicts_with   — the observations make contradictory claims
- supersedes       — observation A replaces or overrides observation B (or vice versa)
- scoped           — one is a narrower instance of the other
- related          — they share a topic but do not conflict
- compatible       — they are consistent and complementary
- not_conflict     — they are unrelated; no meaningful semantic overlap

Respond with a single-line JSON object and nothing else:
{"Relation":"<verb>","Confidence":<0.0–1.0>,"Reasoning":"<≤200 chars>"}"""
    @JvmStatic fun build(sourceId:String,sourceTitle:String,sourceContent:String,targetId:String,targetTitle:String,targetContent:String):String =
        String.format(java.util.Locale.ROOT,template,sourceId,sourceTitle,sourceContent,targetId,targetTitle,targetContent)
}
