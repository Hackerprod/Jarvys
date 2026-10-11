package com.jarvys.factory.contract;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationManifestContractTest {
    private static ManifestPlan plan(NotificationPolicy notifications) {
        return new ManifestPlan("org.example.notify", "Notify", 1, "1", 0x7f010001, 0x7f020001,
                Collections.singletonList("notifications"), null, notifications);
    }
    @Test public void immediateActionsDoNotAcquireReceiversOrAlarmPermissions() throws Exception {
        ManifestPlan absent=plan(null), disabled=plan(NotificationPolicy.disabled());
        assertArrayEquals(ManifestXml.encode(absent),ManifestXml.encode(disabled));
        assertTrue(absent.privateComponents.isEmpty());
        assertEquals(Collections.singletonList(CapabilityCatalog.NOTIFICATION_PERMISSION),absent.permissions);
    }
    @Test public void schedulingReceiversAndPermissionsAreExactAndAudited() throws Exception {
        for(boolean exact:new boolean[]{false,true}) {
            ManifestPlan p=plan(new NotificationPolicy(true,exact));
            byte[] bytes=ManifestXml.encode(p); ManifestAudit.Document decoded=ManifestAudit.read(bytes); decoded.verify(p);
            ManifestAudit.verifyTree(bytes,p.root);
            assertEquals(Arrays.asList(NotificationPolicy.ALARM_RECEIVER,NotificationPolicy.RECOVERY_RECEIVER),p.privateComponents);
            assertEquals(Collections.singletonList(ManifestPlan.ACTIVITY),p.exportedComponents);
            assertTrue(p.permissions.contains(NotificationPolicy.BOOT_PERMISSION));
            assertEquals(exact,p.permissions.contains(NotificationPolicy.EXACT_PERMISSION));
            assertFalse(p.permissions.contains("android.permission.USE_EXACT_ALARM"));
            assertEquals(2L,p.nodes.stream().filter(n->n.path.equals("manifest/application/receiver")).count());
            assertEquals(exact?3L:2L,p.nodes.stream().filter(n->n.path.equals("manifest/application/receiver/intent-filter/action")).count());
            for(ManifestPlan.Node n:p.nodes) if(n.path.equals("manifest/application/receiver")) {
                assertEquals(2,n.parentIndex); assertEquals(2,n.attributes.size());
                assertEquals(0,n.attributes.get(ManifestPlan.ANDROID+"|exported").value);
            }
            assertThrows(IOException.class,()->decoded.verify(plan(new NotificationPolicy(true,!exact))));
            assertThrows(IOException.class,()->decoded.verify(plan(null)));
            assertThrows(IllegalArgumentException.class,()->new ManifestPlan(p.appId,p.label,1,"1",p.iconResourceId,p.backupResourceId,
                    p.capabilities,null,p.notifications,ManifestPlan.Profile.V3_BEFORE_INCOMING_SHARE));
        }
    }
    @Test public void allMaximumIncomingAndSchedulingContributionsFitTheClosedAudit() throws Exception {
        java.util.List<String> mimes=new java.util.ArrayList<>();for(int i=0;i<32;i++)mimes.add(String.format("application/x-%02d",i));
        ManifestPlan p=new ManifestPlan("org.example.all","All",1,"1",0x7f010001,0x7f020001,CapabilityCatalog.NAMES,
                new IncomingSharePolicy(mimes,true),new NotificationPolicy(true,true));
        ManifestAudit.read(ManifestXml.encode(p)).verify(p);
        assertTrue(p.nodes.size()<ManifestXml.MAX_NODES);
    }
    @Test public void invalidOrUndeclaredSchedulingCannotCreateManifestAuthority() {
        assertThrows(IllegalArgumentException.class,()->new NotificationPolicy(false,true));
        assertThrows(IllegalArgumentException.class,()->new ManifestPlan("org.example.none","None",1,"1",0x7f010001,0x7f020001,
                Collections.emptyList(),null,NotificationPolicy.disabled()));
    }
}
