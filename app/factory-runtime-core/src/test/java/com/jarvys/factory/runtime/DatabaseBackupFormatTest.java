package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.DatabaseRequestTest.invalid;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DatabaseBackupFormatTest {
    static final String APP="test.backup", CERT=repeat("a",64);
    static JSONObject def(String type,boolean nullable) throws Exception { return new JSONObject().put("type",type).put("nullable",nullable); }
    static JSONObject columns() throws Exception {
        return new JSONObject().put("title",def("text",false)).put("count",def("integer",false))
                .put("score",def("real",false)).put("active",def("boolean",false)).put("optional",def("text",true));
    }
    static JSONObject row(String id) throws Exception {
        return new JSONObject().put("id",id).put("title","日本語 🌱").put("count",DatabaseRequest.MAX_SAFE_INTEGER)
                .put("score",0.1).put("active",true).put("optional",JSONObject.NULL);
    }
    static DatabaseBackupFormat.Archive archive(JSONObject... rows) throws Exception {
        return DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",columns()),new JSONObject().put("notes",new JSONArray(rows)));
    }
    static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    static DatabaseBackupFormat.Archive parse(String text) throws Exception { return DatabaseBackupFormat.parse(text.getBytes(StandardCharsets.UTF_8)); }
    static JSONObject envelope(DatabaseBackupFormat.Archive archive) throws Exception { return BackupJson.object(archive.bytes()); }
    @Test public void deterministicSortedFullTypedRoundTripAndDigest() throws Exception {
        DatabaseBackupFormat.Archive first=archive(row("z"),row("A"),row("a"));
        DatabaseBackupFormat.Archive second=archive(row("a"),row("z"),row("A"));
        assertArrayEquals(first.bytes(),second.bytes()); assertEquals(first.sha256,DatabaseBackupFormat.sha256(first.bytes()));
        assertEquals(APP,first.appId); assertEquals(CERT,first.certificateSha256); assertEquals(1,first.version);
        assertEquals(Collections.singletonList("notes"),first.tables);
        assertEquals("A",first.data().getJSONArray("notes").getJSONObject(0).getString("id"));
        assertEquals("a",first.data().getJSONArray("notes").getJSONObject(1).getString("id"));
        assertEquals("z",first.data().getJSONArray("notes").getJSONObject(2).getString("id"));
        String encoded=text(first.bytes()); assertTrue(encoded.startsWith("{\"certificateSha256\":\""+CERT+"\",\"databaseVersion\":1,\"format\":\"jarvys-sqlite-data\",\"formatVersion\":1,\"packageName\":\"test.backup\",\"tables\":"));
        assertArrayEquals(first.bytes(),DatabaseBackupFormat.parse(first.bytes()).bytes());
        JSONObject restored=DatabaseBackupFormat.parse(first.bytes()).data().getJSONArray("notes").getJSONObject(0);
        assertTrue(restored.get("count") instanceof Long); assertEquals(DatabaseRequest.MAX_SAFE_INTEGER,restored.getLong("count"));
        assertTrue(restored.get("score") instanceof Double); assertEquals(Boolean.TRUE,restored.get("active")); assertTrue(restored.has("optional")); assertTrue(restored.isNull("optional"));
        assertTrue(DatabaseBackupFormat.sameTables(first,Collections.singletonList("notes")));
        assertFalse(DatabaseBackupFormat.sameTables(first,Arrays.asList("notes","other")));
        assertFalse(DatabaseBackupFormat.sameTables(first,Collections.singletonList("other")));
    }
    @Test public void v1ColumnsStayLegacyBoundedWhenTypedDatabaseGrammarExpands() throws Exception {
        JSONObject schema = new JSONObject();
        for (int i = 0; i < DatabaseRequest.MAX_COLUMNS; i++) schema.put("c" + i, def("text", true));
        DatabaseBackupFormat.create(APP, CERT, 1, new JSONObject().put("notes", schema), new JSONObject().put("notes", new JSONArray()));
        schema.put("expanded", def("text", true));
        DatabaseRequest.columns(schema); // Pure typed-DB grammar supports this; archive v1 does not.
        invalid(() -> DatabaseBackupFormat.create(APP, CERT, 1,
                new JSONObject().put("notes", schema), new JSONObject().put("notes", new JSONArray())));
    }
    @Test public void emptySelectedTablesRemainPresentAndAreImmutable() throws Exception {
        JSONObject schema=new JSONObject().put("zeta",columns()).put("alpha",columns());
        JSONObject data=new JSONObject().put("alpha",new JSONArray()).put("zeta",new JSONArray());
        DatabaseBackupFormat.Archive backup=DatabaseBackupFormat.create(APP,CERT,1,schema,data);
        assertEquals(Arrays.asList("alpha","zeta"),backup.tables);
        assertEquals(0,DatabaseBackupFormat.parse(backup.bytes()).data().getJSONArray("alpha").length());
        byte[] original=backup.bytes(); byte[] writable=backup.bytes(); writable[0]=0;
        backup.schema().getJSONObject("alpha").remove("title"); backup.data().remove("alpha"); schema.remove("alpha"); data.remove("alpha");
        assertArrayEquals(original,backup.bytes()); assertTrue(backup.schema().has("alpha")); assertTrue(backup.data().has("alpha"));
        try { backup.tables.add("evil"); fail(); } catch(UnsupportedOperationException expected) { }
    }
    @Test public void closedEnvelopeSchemaAndCompleteRowsRejectUnknownMissingAndCoercedFields() throws Exception {
        for(String key:new String[]{"format","formatVersion","packageName","certificateSha256","databaseVersion","tables"}) {
            JSONObject input=envelope(archive()); input.remove(key); invalid(()->DatabaseBackupFormat.parse(DatabaseBackupFormat.canonical(input)));
        }
        JSONObject unknown=envelope(archive()); unknown.put("signature","pretend-authentication");
        invalid(()->DatabaseBackupFormat.parse(DatabaseBackupFormat.canonical(unknown)));
        for(Object value:new Object[]{"1",1.0,true,JSONObject.NULL,0,1001}) {
            JSONObject input=envelope(archive()); input.put("databaseVersion",value);
            invalid(()->DatabaseBackupFormat.parse(DatabaseBackupFormat.canonical(input)));
        }
        for(String key:new String[]{"id","title","count","score","active","optional"}) {
            JSONObject incomplete=row("one"); incomplete.remove(key); invalid(()->archive(incomplete));
        }
        invalid(()->archive(row("one").put("unknown","x")));
        invalid(()->archive(row("one").put("title",JSONObject.NULL)));
        invalid(()->archive(row("one").put("active",1)));
        invalid(()->archive(row("one").put("count",1.0)));
        invalid(()->archive(row("one").put("count","1")));
        invalid(()->archive(row("one"),row("one")));
        for(String field:new String[]{"type","nullable"}) {
            JSONObject broken=columns(); broken.getJSONObject("title").remove(field);
            invalid(()->DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",broken),new JSONObject().put("notes",new JSONArray())));
        }
        JSONObject broken=columns(); broken.getJSONObject("title").put("default","x");
        invalid(()->DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",broken),new JSONObject().put("notes",new JSONArray())));
        invalid(()->DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",columns()),new JSONObject().put("other",new JSONArray())));
    }
    @Test public void rejectsPermissiveJsonDuplicateKeysAndNoncanonicalByteForms() throws Exception {
        String valid=text(archive(row("one")).bytes());
        for(String bad:new String[]{" "+valid,valid+"\n","\uFEFF"+valid,valid.replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1"),
                valid.replace("\"formatVersion\":1","\"formatVersion\":01"),valid.replace("\"formatVersion\":1","\"formatVersion\":+1"),
                valid.replace("\"formatVersion\":1","\"formatVersion\":0x1"),valid.replace("\"formatVersion\":1","\"formatVersion\":1/*comment*/"),
                valid.replace("\"formatVersion\":1","'formatVersion':1"),valid.substring(0,valid.length()-1)+",}",
                valid.replace("\"title\":\"日本語", "\"title\":\"\\u65e5本語")}) invalid(()->parse(bad));
        invalid(()->DatabaseBackupFormat.parse(new byte[]{(byte)0xc3,(byte)0x28}));
        invalid(()->DatabaseBackupFormat.parse(new byte[0])); invalid(()->DatabaseBackupFormat.parse(null));
    }
    @Test public void malformedArchiveErrorNamesItsFieldWithoutEchoingInput() throws Exception {
        byte[] supplied = "{\"private-value\":\"do-not-echo\"}".getBytes(StandardCharsets.UTF_8);
        try { DatabaseBackupFormat.parse(supplied); fail("Expected INVALID_ARGUMENT"); }
        catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertTrue(failure.getMessage().contains("database.backupImport.archive"));
            assertFalse(failure.getMessage().contains("do-not-echo"));
        }
    }
    @Test public void exactIntegerLexemesAndFiniteCanonicalRealsNeverSilentlyRound() throws Exception {
        String valid=text(archive(row("one")).bytes());
        for(String number:new String[]{"9007199254740992","9007199254740993","9223372036854775808","-9223372036854775809","9.007199254740991E15","9007199254740991.0"})
            invalid(()->parse(valid.replace("\"count\":9007199254740991","\"count\":"+number)));
        for(String number:new String[]{"0.10000000000000001","1e-400","1e400","NaN","Infinity","-0.0","0.10","1e-1","0"})
            invalid(()->parse(valid.replace("\"score\":0.1","\"score\":"+number)));
        DatabaseBackupFormat.Archive zero=archive(row("one").put("score",-0.0)); assertTrue(text(zero.bytes()).contains("\"score\":0.0"));
        assertEquals(0,Double.doubleToLongBits(zero.data().getJSONArray("notes").getJSONObject(0).getDouble("score")));
        for(double value:new double[]{Double.MIN_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,1.0,1.0E20}) {
            DatabaseBackupFormat.Archive finite=archive(row("one").put("score",value)); assertArrayEquals(finite.bytes(),DatabaseBackupFormat.parse(finite.bytes()).bytes());
        }
    }
    @Test public void unicodeControlsAreVisibleAndTextAndRowQuotasMatchDatabase() throws Exception {
        DatabaseBackupFormat.Archive backup=archive(row("one").put("title","line\n\t\r\b\f\u202e\u2066日本語 🌱"));
        String encoded=text(backup.bytes());
        for(String escape:new String[]{"\\u000a","\\u0009","\\u000d","\\u0008","\\u000c","\\u202e","\\u2066"}) assertTrue(encoded.contains(escape));
        assertArrayEquals(backup.bytes(),DatabaseBackupFormat.parse(backup.bytes()).bytes());
        for(String invalid:new String[]{"nul\0x","\uD800","\uDC00",repeat("é",2049),repeat("x",4097)}) invalid(()->archive(row("one").put("title",invalid)));
        archive(row("one").put("title",repeat("é",2048)));
        JSONObject schema=new JSONObject(), row=new JSONObject().put("id","one");
        for(int i=0;i<4;i++) { schema.put("part"+i,def("text",false)); row.put("part"+i,repeat("x",4096)); }
        invalid(()->DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",schema),new JSONObject().put("notes",new JSONArray().put(row))));
    }
    @Test public void archiveGrammarIsBoundedWithoutRelaxingBridgeGrammar() throws Exception {
        JSONObject schema=new JSONObject().put("title",def("text",false)); JSONArray rows=new JSONArray();
        for(int i=0;i<1000;i++) rows.put(new JSONObject().put("id","id"+i).put("title","x"));
        DatabaseBackupFormat.Archive backup=DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",schema),new JSONObject().put("notes",rows));
        assertEquals(1000,DatabaseBackupFormat.parse(backup.bytes()).data().getJSONArray("notes").length());
        try { StrictJson.object(text(backup.bytes()),DatabaseBackupFormat.MAX_BYTES); fail(); }
        catch(FactoryException expected) { assertEquals("INVALID_REQUEST",expected.code); }
        for(int i=1000;i<=10000;i++) rows.put(new JSONObject().put("id","id"+i).put("title","x"));
        invalid(()->DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",schema),new JSONObject().put("notes",rows)));
        try { DatabaseBackupFormat.parse(new byte[DatabaseBackupFormat.MAX_BYTES+1]); fail(); }
        catch(FactoryException expected) { assertEquals("TOO_LARGE",expected.code); }
        JSONArray large=new JSONArray(); for(int i=0;i<256;i++) large.put(new JSONObject().put("id","id"+i).put("title",repeat("x",4096)));
        try { DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",schema),new JSONObject().put("notes",large)); fail(); }
        catch(FactoryException expected) { assertEquals("TOO_LARGE",expected.code); }
        invalid(()->BackupJson.object("{\"x\":[[[[[[[[[0]]]]]]]]]}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void metadataAndIdentifiersAreCompatibilityLabelsWithClosedGrammar() throws Exception {
        for(String app:new String[]{"", "no_dot", "../test.backup",repeat("x",161)+".a"})
            invalid(()->DatabaseBackupFormat.create(app,CERT,1,new JSONObject().put("notes",columns()),new JSONObject().put("notes",new JSONArray())));
        for(String cert:new String[]{repeat("A",64),repeat("a",63),repeat("g",64)})
            invalid(()->DatabaseBackupFormat.create(APP,cert,1,new JSONObject().put("notes",columns()),new JSONObject().put("notes",new JSONArray())));
        invalid(()->DatabaseBackupFormat.create(APP,CERT,1,new JSONObject(),new JSONObject()));
        for(String id:new String[]{"", "é", "a/b", "' OR 1=1", repeat("x",97)}) invalid(()->archive(row(id)));
    }
}
