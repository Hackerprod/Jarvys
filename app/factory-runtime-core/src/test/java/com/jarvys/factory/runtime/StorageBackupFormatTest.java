package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class StorageBackupFormatTest {
    static final String APP="example.preferences",CERT=repeat("a",64);
    static StorageBackupFormat.Archive archive(JSONObject values) throws Exception { return StorageBackupFormat.create(APP,CERT,1,values); }
    static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private void denied(String value) { assertThrows(FactoryException.class,()->StorageBackupFormat.parse(value.getBytes(StandardCharsets.UTF_8))); }
    @Test public void sortedCanonicalStringOnlyRoundTripIsOwnedAndImmutable() throws Exception {
        JSONObject input=new JSONObject().put("z","日本語 🌱\u0000\n\u202e").put("A","").put("a","true");
        StorageBackupFormat.Archive first=archive(input),second=archive(new JSONObject().put("a","true").put("z",input.get("z")).put("A",""));
        assertArrayEquals(first.bytes(),second.bytes()); assertEquals(Arrays.asList("A","a","z"),first.keys);
        assertEquals(APP,first.appId);assertEquals(CERT,first.certificateSha256);assertEquals(1,first.version);
        String encoded=text(first.bytes());assertTrue(encoded.contains("\"format\":\"jarvys-preferences-data\""));assertTrue(encoded.contains("\\u0000"));assertTrue(encoded.contains("\\u202e"));
        assertArrayEquals(first.bytes(),StorageBackupFormat.parse(first.bytes()).bytes());assertEquals(first.sha256,StorageBackupFormat.sha256(first.bytes()));
        input.put("a","changed");first.data().put("a","changed");byte[] bytes=first.bytes();Arrays.fill(bytes,(byte)0);
        assertEquals("true",first.data().getString("a"));assertEquals(encoded,text(first.bytes()));assertThrows(UnsupportedOperationException.class,()->first.keys.add("x"));
    }
    @Test public void archiveHasClosedEnvelopeFixedStrictIntegersAndStringValues() throws Exception {
        String original=text(archive(new JSONObject().put("theme","dark")).bytes());JSONObject envelope=new JSONObject(original);
        for(String field:new String[]{"certificateSha256","format","formatVersion","packageName","storageVersion","values"}) { JSONObject changed=new JSONObject(original);changed.remove(field);denied(text(StorageBackupFormat.canonical(changed))); }
        denied(text(StorageBackupFormat.canonical(new JSONObject(original).put("extra",true))));
        for(String field:new String[]{"formatVersion","storageVersion"})for(Object bad:new Object[]{0,2,"1",1.0,true,JSONObject.NULL})denied(text(StorageBackupFormat.canonical(new JSONObject(original).put(field,bad))));
        for(Object bad:new Object[]{1,1.5,true,JSONObject.NULL,new JSONObject()})assertThrows(FactoryException.class,()->archive(new JSONObject().put("theme",bad)));
        for(String app:new String[]{"","no_dot","../example.app",repeat("x",161)+".a"})assertThrows(FactoryException.class,()->StorageBackupFormat.create(app,CERT,1,new JSONObject().put("a","b")));
        for(String cert:new String[]{repeat("A",64),repeat("g",64),repeat("a",63)})assertThrows(FactoryException.class,()->StorageBackupFormat.create(APP,cert,1,new JSONObject().put("a","b")));
        assertThrows(FactoryException.class,()->StorageBackupFormat.create(APP,CERT,2,new JSONObject().put("a","b")));
        denied(text(StorageBackupFormat.canonical(envelope.put("format","jarvys-sqlite-data"))));
    }
    @Test public void rejectsDuplicateNoncanonicalMalformedUnicodeAndNumericLexemes() throws Exception {
        String original=text(archive(new JSONObject().put("theme","dark")).bytes());
        for(String changed:new String[]{" "+original,original+"\n","\ufeff"+original,original.replace("\"dark\"","\"d\\u0061rk\""),
                original.replace("\"storageVersion\":1","\"storageVersion\":1e0"),original.replace("\"storageVersion\":1","\"storageVersion\":1.0"),
                original.replace("\"theme\":\"dark\"","\"theme\":\"dark\",\"theme\":\"light\""),original.replace("\"dark\"","\"\\ud800\"")})denied(changed);
        assertThrows(FactoryException.class,()->StorageBackupFormat.parse(new byte[]{(byte)0xc3,(byte)0x28}));
        assertThrows(FactoryException.class,()->StorageBackupFormat.parse(null));assertThrows(FactoryException.class,()->StorageBackupFormat.parse(new byte[0]));
        assertThrows(FactoryException.class,()->archive(new JSONObject().put("a","\ud800")));
    }
    @Test public void exactKeyValueAndArchiveSizeBoundaries() throws Exception {
        assertThrows(FactoryException.class,()->archive(new JSONObject()));
        for(String key:new String[]{"","bad key","a/b",repeat("a",97)})assertThrows(FactoryException.class,()->archive(new JSONObject().put(key,"x")));
        archive(new JSONObject().put(repeat("a",96),repeat("x",BridgeProtocol.MAX_VALUE_BYTES)));
        assertThrows(FactoryException.class,()->archive(new JSONObject().put("a",repeat("x",BridgeProtocol.MAX_VALUE_BYTES+1))));
        archive(new JSONObject().put("a",repeat("é",BridgeProtocol.MAX_VALUE_BYTES/2)));
        assertThrows(FactoryException.class,()->archive(new JSONObject().put("a",repeat("é",BridgeProtocol.MAX_VALUE_BYTES/2+1))));
        JSONObject values=new JSONObject();for(int i=0;i<32;i++)values.put(String.format("k%02d",i),repeat("x",32700));
        StorageBackupFormat.Archive base=archive(values);int padding=StorageBackupFormat.MAX_BYTES-base.bytes().length;
        values.put("k31",repeat("x",32700+padding));StorageBackupFormat.Archive exact=archive(values);
        assertEquals(StorageBackupFormat.MAX_BYTES,exact.bytes().length);assertArrayEquals(exact.bytes(),StorageBackupFormat.parse(exact.bytes()).bytes());
        values.put("k31",repeat("x",32701+padding));assertEquals("TOO_LARGE",assertThrows(FactoryException.class,()->archive(values)).code);
        assertEquals("TOO_LARGE",assertThrows(FactoryException.class,()->StorageBackupFormat.parse(new byte[StorageBackupFormat.MAX_BYTES+1])).code);
        values.put("k32","");assertThrows(FactoryException.class,()->archive(values));
    }
}
