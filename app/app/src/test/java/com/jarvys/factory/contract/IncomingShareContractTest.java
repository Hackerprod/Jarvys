package com.jarvys.factory.contract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class IncomingShareContractTest {
    private static ManifestPlan plan(IncomingSharePolicy policy) {
        return new ManifestPlan("org.example.incoming", "Incoming", 1, "1", 0x7f010001, 0x7f020001,
                Arrays.asList("documents", "incomingShare"), policy);
    }
    @Test public void separateExactFiltersAndMimeNodesRoundTrip() throws Exception {
        for (boolean multiple : new boolean[]{false, true}) {
            IncomingSharePolicy policy = new IncomingSharePolicy(Arrays.asList("image/*", "text/plain"), multiple);
            ManifestPlan plan = plan(policy);
            byte[] bytes = ManifestXml.encode(plan);
            ManifestAudit.Document audited = ManifestAudit.read(bytes); audited.verify(plan);
            ManifestAudit.verifyTree(bytes, plan.root);
            assertTrue(plan.permissions.isEmpty()); assertEquals(1, plan.exportedComponents.size());
            List<Integer> filters = new ArrayList<>();
            for (int i=0;i<plan.nodes.size();i++) if (plan.nodes.get(i).path.endsWith("/intent-filter")) filters.add(i);
            assertEquals(multiple ? 3 : 2, filters.size());
            for (int i=1;i<filters.size();i++) {
                int index=filters.get(i); assertEquals(3, plan.nodes.get(index).parentIndex);
                assertEquals(i == 1 ? "android.intent.action.SEND" : "android.intent.action.SEND_MULTIPLE", plan.nodes.get(index+1).attributes.get(ManifestPlan.ANDROID+"|name").value);
                assertEquals("android.intent.category.DEFAULT", plan.nodes.get(index+2).attributes.get(ManifestPlan.ANDROID+"|name").value);
                assertEquals("image/*", plan.nodes.get(index+3).attributes.get(ManifestPlan.ANDROID+"|mimeType").value);
                assertEquals("text/plain", plan.nodes.get(index+4).attributes.get(ManifestPlan.ANDROID+"|mimeType").value);
                for(int j=1;j<=4;j++) assertEquals(index,plan.nodes.get(index+j).parentIndex);
            }
            assertThrows(IOException.class, () -> audited.verify(plan(new IncomingSharePolicy(Arrays.asList("image/*", "text/plain"), !multiple))));
            assertThrows(IOException.class, () -> audited.verify(plan(new IncomingSharePolicy(Collections.singletonList("*/*"), multiple))));
        }
    }
    @Test public void maximumMimePatternsFitWithoutRaisingUnrelatedSiblingLimits() throws Exception {
        List<String> mime = new ArrayList<>(); for(int i=0;i<32;i++) mime.add(String.format("application/x-%02d",i));
        ManifestPlan plan = plan(new IncomingSharePolicy(mime,true));
        ManifestAudit.read(ManifestXml.encode(plan)).verify(plan);
        List<ManifestNodes.Element> tooMany = new ArrayList<>(); for(int i=0;i<33;i++) tooMany.add(ManifestNodes.queryPackage("org.example.p"+i));
        assertThrows(IllegalArgumentException.class, () -> ManifestNodes.queries(tooMany));
        assertThrows(UnsupportedOperationException.class, () -> plan.incomingShare.mimeTypes.add("text/plain"));
    }
    @Test public void malformedOrImplicitPolicyFailsClosed() {
        for(List<String> mimes: Arrays.asList(Collections.<String>emptyList(), Arrays.asList("text/plain","image/*"), Arrays.asList("text/plain","text/plain"),
                Collections.singletonList("Text/plain"), Collections.singletonList("text/plain; charset=utf-8"), Collections.singletonList("*/plain"),Collections.singletonList("text/")))
            assertThrows(IllegalArgumentException.class, () -> new IncomingSharePolicy(mimes,false));
        IncomingSharePolicy policy = new IncomingSharePolicy(Collections.singletonList("*/*"),false);
        assertThrows(IllegalArgumentException.class, () -> IncomingSharePolicy.validateSelection(Collections.singletonList("incomingShare"),policy));
        assertThrows(IllegalArgumentException.class, () -> IncomingSharePolicy.validateSelection(Collections.singletonList("documents"),policy));
        assertThrows(IllegalArgumentException.class, () -> plan(null));
    }
    @Test public void historicalNativeProfileRetainsBytesAndCannotAcquireNewFilters() throws Exception {
        List<String> caps=Arrays.asList("documents","share","clipboard","network","sensors","recording","steps");
        ManifestPlan current=new ManifestPlan("org.example.old","Old",1,"1",0x7f010001,0x7f020001,caps);
        ManifestPlan old=new ManifestPlan("org.example.old","Old",1,"1",0x7f010001,0x7f020001,caps,ManifestPlan.Profile.V3_BEFORE_INCOMING_SHARE);
        assertArrayEquals(ManifestXml.encode(current), ManifestXml.encode(old));
        for(ManifestPlan.Profile profile:ManifestPlan.Profile.values()) if(profile!=ManifestPlan.Profile.CURRENT)
            assertThrows(IllegalArgumentException.class, () -> new ManifestPlan("org.example.old","Old",1,"1",0x7f010001,0x7f020001,
                    Arrays.asList("documents","incomingShare"),new IncomingSharePolicy(Collections.singletonList("text/plain"),false),profile));
    }
}
