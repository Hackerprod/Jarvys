package com.jarvys.agent.coding;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static com.jarvys.agent.coding.CodingJobEvidenceCodec.*;

/** Strict ONE-envelope operational schema3 codec. Decode is metadata only, never a commit. */
public final class CodingJobJournalCodec {
    private CodingJobJournalCodec() { }
    public static final class Decoded {
        public final CodingJobSnapshot snapshot;
        public final CodingJobEvidence.Record record;
        private Decoded(CodingJobSnapshot snapshot, CodingJobEvidence.Record record) {
            snapshot.requireMatches(record); this.snapshot = snapshot; this.record = record;
        }
    }
    public static byte[] encode(CodingJobSnapshot s, CodingJobEvidence.Record r) {
        s.requireMatches(r);
        return json(object("schema",3L,"snapshot", object("id",s.id,"owner",s.owner,"scopeId",s.scopeId,
                "cwd",s.cwd,"command",s.redactedCommand,"projectRoot",s.projectRoot,"conversationId",s.conversationId,
                "state",s.state.name(),"createdAt",s.createdAt,"startedAt",s.startedAt,"finishedAt",s.finishedAt,
                "exitCode",s.exitCode==null?null:s.exitCode.longValue(),"logArtifact",s.logArtifact,"error",s.error,
                "logBytes",s.logBytes,"logComplete",s.logComplete),"evidence",recordObject(r))).getBytes(StandardCharsets.UTF_8);
    }
    /** Strict discriminator only; legacy decoders retain their own format/authority boundary. */
    public static long schemaVersion(byte[] bytes,int maxBytes) { return number(readRoot(bytes,maxBytes),"schema"); }
    private static Map<String,Object> readRoot(byte[] bytes,int maxBytes) {
        if (bytes == null || maxBytes <= 0 || bytes.length > maxBytes) throw new IllegalArgumentException("Journal exceeds input budget");
        final String encoded;
        try { encoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException bad) { throw new IllegalArgumentException("Invalid journal UTF-8",bad); }
        return parseJournal(encoded,maxBytes);
    }
    public static Decoded decode(byte[] bytes, int maxBytes) {
        Map<String,Object> root = readRoot(bytes,maxBytes);
        keys(root,"schema","snapshot","evidence");
        if (number(root,"schema") != 3) throw new IllegalArgumentException("Unsupported operational journal schema");
        Map<String,Object> s = map(root.get("snapshot"));
        keys(s,"id","owner","scopeId","cwd","command","projectRoot","conversationId","state","createdAt",
                "startedAt","finishedAt","exitCode","logArtifact","error","logBytes","logComplete");
        Long exit = nullableLong(s,"exitCode");
        if (exit != null && (exit < Integer.MIN_VALUE || exit > Integer.MAX_VALUE)) throw new IllegalArgumentException("Invalid process exit code");
        return new Decoded(new CodingJobSnapshot(string(s,"id"),string(s,"owner"),string(s,"scopeId"),string(s,"cwd"),
                string(s,"command"),string(s,"projectRoot"),string(s,"conversationId"),CodingJobSnapshot.State.valueOf(string(s,"state")),
                number(s,"createdAt"),nullableLong(s,"startedAt"),nullableLong(s,"finishedAt"),exit==null?null:exit.intValue(),
                string(s,"logArtifact"),s.get("error")==null?null:string(s,"error"),number(s,"logBytes"),bool(s,"logComplete")),
                readRecord(map(root.get("evidence"))).record);
    }
    private static Long nullableLong(Map<String,Object> value,String key) { return value.get(key)==null?null:number(value,key); }
}
