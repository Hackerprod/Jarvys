package com.jarvys.agent.coding;

import com.jarvys.agent.CrewInputContext;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.agent.coding.CodingJobEvidence.*;

/** Strict v2 syntax/shape tests; decoded values never certify IO, attribution or authorization. */
public class CodingJobEvidenceCodecV2Test {
    private static final String UUID="00000000-0000-0000-0000-000000000001";
    private static final String SHA="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static CodingJobEvidence.Record fixture() {
        CrewInputContext.Scope scope=new CrewInputContext.Scope("source","execution","workspace","assignment","bot","mission");
        CrewInputContext.Reference binding=CrewInputContext.Reference.binding("a","binding","source",scope,"cycle","first","reply",SHA,SHA,SHA);
        CrewInputContext.Reference conversation=CrewInputContext.Reference.conversation("a","conversation","source",scope,"user","","","",9007199254740993L,"USER","request","cycle");
        CrewInputContext inputs=CrewInputContext.resolve(Arrays.asList("a","y","z"),Arrays.asList(conversation,binding));
        CrewSource crew=new CrewSource("execution","bot","mission","cycle","scope",SHA,"call","project_exec",inputs);
        Binding job=new Binding(UUID,UUID,UUID,"owner","scope","scope",UUID,UUID,new Source(crew,null,null),SemanticCapture.DISABLED);
        return new CodingJobEvidence.Record(job,1,Phase.SUBMISSION_INTENT,false,null,null);
    }
    private static String wire(){return CodingJobEvidenceCodec.encode(fixture());}
    private static void reject(String text){assertThrows(IllegalArgumentException.class,()->CodingJobEvidenceCodec.decode(text,text.length()));}
    private static String replace(String source,String old,String value){assertTrue("Mutation anchor missing",source.contains(old));return source.replace(old,value);}
    private static String[] refs(String wire){
        int start=wire.indexOf("\"references\":[")+"\"references\":[".length();
        int end=wire.indexOf("],\"unresolvedInputIds\"",start);
        String array=wire.substring(start,end); int split=array.indexOf("},{\"channel\"");
        assertTrue(split>0);return new String[]{array.substring(0,split+1),array.substring(split+2)};
    }
    @Test public void maximumSchemaDepthAndCanonicalBothChannelsRoundTrip() {
        CodingJobEvidence.Record original=fixture();String text=CodingJobEvidenceCodec.encode(original);
        assertEquals(original,CodingJobEvidenceCodec.decode(text,text.length()).record);
        assertTrue(text.contains("\"envelopeVersion\":2"));
        assertEquals(2,CodingJobEvidenceCodec.decode(text,text.length()).record.binding.source.crew.inputContext.references.size());
    }
    @Test public void utf8ByteRoundTripPreservesEverySurrogateUnitAndOrdinaryUnicode() {
        List<String> identities=Arrays.asList(
                "high-"+String.valueOf((char)0xD800),
                "low-"+String.valueOf((char)0xDC00),
                "pair-"+new String(new char[]{(char)0xD83D,(char)0xDE80}),
                "ordinary-"+new String(new char[]{(char)0x00E9,(char)0x03BB,(char)0x4E2D}));
        for(String identity:identities) {
            CodingJobEvidence.Record base=fixture(); Binding b=base.binding;
            Binding changed=new Binding(b.technicalStoreGeneration,b.jobId,b.submissionId,identity,
                    b.scopeId,b.durableScopeIdentity,b.ownerProcessId,b.managerInstanceId,b.source,b.semanticCapture);
            CodingJobEvidence.Record original=new CodingJobEvidence.Record(changed,base.revision,
                    base.highestEffectPhase,base.submissionCommitted,base.authorization,base.terminal);
            String encoded=CodingJobEvidenceCodec.encode(original);
            for(char unit:encoded.toCharArray())assertFalse("Surrogate must be escaped before UTF-8",Character.isSurrogate(unit));
            String transported=new String(encoded.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
            assertEquals(encoded,transported);
            CodingJobEvidence.Record decoded=CodingJobEvidenceCodec.decode(transported,transported.length()).record;
            assertEquals(original,decoded);
            assertEquals(identity,decoded.binding.owner);
        }
    }
    @Test public void requiresExplicitVersionAndRejectsLegacyShapeAndUnknownFields() {
        String text=wire();
        reject(replace(text,"\"envelopeVersion\":2,",""));
        reject(replace(text,"\"envelopeVersion\":2","\"envelopeVersion\":1"));
        reject(replace(text,"\"envelopeVersion\":2","\"envelopeVersion\":\"2\""));
        reject(replace(text,"\"kind\":\"CREW_CHECKPOINT\"","\"kind\":\"CREW_CHECKPOINT\",\"storageGeneration\":\"g3\""));
        reject(replace(text,"\"semanticCapture\":\"DISABLED\"","\"captureMode\":\"MEMORY_DISABLED\""));
        reject(replace(text,"\"revision\":1","\"revision\":1,\"unknown\":null"));
    }
    @Test public void rejectsDuplicateKeysIncludingEscapedEquivalentKey() {
        String text=wire();
        reject(replace(text,"\"schema\":3","\"schema\":3,\"schema\":3"));
        reject(replace(text,"\"schema\":3","\"schema\":3,\"sche\\u006da\":3"));
        reject(replace(text,"\"inputId\":\"a\"","\"inputId\":\"a\",\"inputId\":\"a\""));
    }
    @Test public void rejectsDuplicateReorderedAndConflictingReferenceIdentities() {
        String text=wire();String[] refs=refs(text);String pair=refs[0]+","+refs[1];
        reject(replace(text,pair,refs[0]+","+refs[0]+","+refs[1]));
        reject(replace(text,pair,refs[1]+","+refs[0]));
        reject(replace(text,pair,refs[0]+","+refs[0].replace("\"sourceId\":\"source\"","\"sourceId\":\"different\"")+","+refs[1]));
    }
    @Test public void rejectsUnresolvedDuplicatesOverlapOrderingAndFalseCoverage() {
        String text=wire();
        for(String ids:Arrays.asList("[\"y\",\"y\",\"z\"]","[\"a\",\"y\",\"z\"]","[\"z\",\"y\"]","[null]","[1]"))
            reject(replace(text,"[\"y\",\"z\"]",ids));
        reject(replace(text,"\"coverage\":\"PARTIAL\"","\"coverage\":\"COMPLETE\""));
    }
    @Test public void rejectsChannelSpecificExtraFieldsAndWrongArrayTypes() {
        String text=wire();
        reject(replace(text,"\"channel\":\"CREW_BINDING\"","\"channel\":\"CREW_BINDING\",\"principalRole\":\"user\""));
        reject(replace(text,"\"channel\":\"CONVERSATION_INPUT\"","\"channel\":\"CONVERSATION_INPUT\",\"admissionCycleId\":\"cycle\""));
        String[] refs=refs(text);
        reject(replace(text,"\"references\":["+refs[0]+","+refs[1]+"]","\"references\":{}"));
        reject(replace(text,"\"unresolvedInputIds\":[\"y\",\"z\"]","\"unresolvedInputIds\":{}"));
    }
    @Test public void exactLongsRejectOverflowDecimalsAndCoercion() {
        String text=wire();
        for(String invalid:Arrays.asList("9223372036854775808","1.0","1e2","\"1\"","true","01"))
            reject(replace(text,"\"assignmentRevision\":9007199254740993","\"assignmentRevision\":"+invalid));
        String max=replace(text,"\"assignmentRevision\":9007199254740993","\"assignmentRevision\":9223372036854775807");
        assertEquals(Long.MAX_VALUE,CodingJobEvidenceCodec.decode(max,max.length()).record.binding.source.crew.inputContext.references.get(1).assignmentRevision);
    }
    @Test public void schemaDepthRejectsOneExtraContainerBeforeDtoCoercion() {
        String nested=replace(wire(),"\"sourceSessionId\":\"source\"","\"sourceSessionId\":{\"nested\":\"value\"}");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->CodingJobEvidenceCodec.decode(nested,nested.length()));
        assertEquals("Malformed evidence envelope",error.getMessage());
    }
    @Test public void callerBudgetAndStrictJsonBoundaries() {
        String text=wire();
        assertThrows(IllegalArgumentException.class,()->CodingJobEvidenceCodec.decode(text,text.length()-1));
        assertThrows(IllegalArgumentException.class,()->CodingJobEvidenceCodec.decode(text,0));
        reject(text+" true");reject(replace(text,"\"references\":[","\"references\":[,"));
        reject(replace(text,"[\"y\",\"z\"]","[\"y\",\"z\",]"));
        reject(replace(text,"\"owner\":\"owner\"","\"owner\":\"bad\\q\""));
    }
}
