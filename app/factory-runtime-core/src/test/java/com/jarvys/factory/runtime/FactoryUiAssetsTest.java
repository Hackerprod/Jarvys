package com.jarvys.factory.runtime;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class FactoryUiAssetsTest {
    private Map<String, byte[]> assets() throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String path : FactoryUiAssets.paths())
            result.put(path, Files.readAllBytes(new File("src/main/assets", path).toPath()));
        return result;
    }
    private byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry item = new ZipEntry("assets/" + entry.getKey()); item.setTime(0);
                zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    @Test public void sourceCatalogHasExactBytesNoticesAndBoundedOfflineCss() throws Exception {
        Map<String, byte[]> resources = assets(); FactoryUiAssets.verifyComplete(resources);
        assertEquals(5, resources.size());
        assertEquals(FactoryUiAssets.TOTAL_BYTES, resources.values().stream().mapToInt(v -> v.length).sum());
        assertTrue(FactoryUiAssets.TOTAL_BYTES <= FactoryUiAssets.MAX_TOTAL_BYTES);
        for (Map.Entry<String, byte[]> item : resources.entrySet()) {
            assertArrayEquals(item.getValue(), FactoryUiAssets.read(item.getKey(), new ByteArrayInputStream(item.getValue())));
            if (item.getKey().endsWith(".css")) {
                String source = new String(item.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(source.contains("Pure v3.0.0"));
                String css = source.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(Locale.ROOT);
                for (String denied : new String[]{"url(", "@import", "expression(", "javascript:", "behavior:", "-moz-binding"})
                    assertFalse(denied, css.contains(denied));
                assertFalse(source.contains("sourceMappingURL"));
            }
        }
        String license = new String(resources.get(FactoryUiAssets.ROOT + "LICENSE.txt"), "UTF-8");
        assertTrue(license.contains("Copyright 2013 Yahoo! Inc."));
        assertTrue(license.contains("Redistribution and use in source and binary forms"));
        assertTrue(new String(resources.get(FactoryUiAssets.ROOT + "normalize-LICENSE.txt"), "UTF-8").contains("Permission is hereby granted, free of charge"));
        JSONObject provenance = new JSONObject(new String(resources.get(FactoryUiAssets.ROOT + "PROVENANCE.json"), "UTF-8"));
        assertEquals("3.0.0", provenance.getString("version"));
        assertEquals(4, provenance.getJSONArray("files").length());
        for (int i=0;i<4;i++) {
            JSONObject file=provenance.getJSONArray("files").getJSONObject(i);
            String path=FactoryUiAssets.ROOT+file.getString("path");
            assertEquals(FactoryUiAssets.size(path),file.getInt("bytes"));
            assertEquals(FactoryUiAssets.sha256(path),file.getString("sha256"));
        }
        assertThrows(UnsupportedOperationException.class, () -> FactoryUiAssets.paths().clear());
    }
    @Test public void apkRejectsMissingChangedExtraWrongVersionAndOversizeCatalog() throws Exception {
        Map<String,byte[]> original=assets();
        assertEquals(original.keySet(), FactoryUiAssets.fromApk(zip(original)).keySet());
        String path=original.keySet().iterator().next();
        Map<String,byte[]> missing=new LinkedHashMap<>(original);missing.remove(path);
        assertThrows(IllegalArgumentException.class, () -> FactoryUiAssets.fromApk(zip(missing)));
        Map<String,byte[]> changed=new LinkedHashMap<>(original);byte[] bad=original.get(path).clone();bad[0]^=1;changed.put(path,bad);
        assertThrows(IllegalArgumentException.class, () -> FactoryUiAssets.fromApk(zip(changed)));
        for (String unknown:new String[]{FactoryUiAssets.ROOT+"evil.css","factory-ui/pure-9.0.0/pure-min.css","factory-ui",FactoryUiAssets.ROOT}) {
            Map<String,byte[]> extra=new LinkedHashMap<>(original);extra.put(unknown,new byte[0]);
            assertThrows(IOException.class, () -> FactoryUiAssets.fromApk(zip(extra)));
        }
        assertThrows(IOException.class, () -> FactoryUiAssets.read(path,new ByteArrayInputStream(new byte[FactoryUiAssets.size(path)+1])));
        assertThrows(IllegalArgumentException.class, () -> FactoryUiAssets.read(path,new ByteArrayInputStream(new byte[1])));
    }
    @Test public void duplicateZipNamesCannotBeHiddenByMapConstruction() throws Exception {
        Map<String,byte[]> all=assets();String path=FactoryUiAssets.ROOT+"pure-min.css";
        all.put(FactoryUiAssets.ROOT+"pure-min.csx",all.get(path));
        byte[] archive=zip(all),from="pure-min.csx".getBytes("UTF-8"),to="pure-min.css".getBytes("UTF-8");
        int replacements=0;
        for(int i=0;i<=archive.length-from.length;i++) {
            boolean matches=true;for(int j=0;j<from.length;j++)if(archive[i+j]!=from[j]){matches=false;break;}
            if(matches){System.arraycopy(to,0,archive,i,to.length);replacements++;}
        }
        assertEquals(2,replacements);
        assertThrows(IOException.class, () -> FactoryUiAssets.fromApk(archive));
    }
}
