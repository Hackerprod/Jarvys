package com.jarvys.factory.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class F3ManifestContractTest {
    private ManifestPlan plan(List<String> caps, BluetoothPolicy bluetooth) {
        return new ManifestPlan("org.example.generic", "Generic", 1, "1", 0x7f010001, 0x7f020001,
                caps, null, null, bluetooth, caps.contains("widgets")?0x7f020002:0);
    }
    private ManifestPlan.Node permission(ManifestPlan p,String name) {
        return p.nodes.stream().filter(n->n.path.equals("manifest/uses-permission") && name.equals(n.attributes.get(ManifestPlan.ANDROID+"|name").value)).findFirst().get();
    }
    private Object attr(ManifestPlan.Node n,String name) { ManifestPlan.Attribute a=n.attributes.get(ManifestPlan.ANDROID+"|"+name); return a==null?null:a.value; }
    @Test public void nfcIsOptionalForegroundReaderOnly() throws Exception {
        ManifestPlan p=plan(Collections.singletonList("nfc"),null);
        assertEquals(Collections.singletonList(F3ManifestPolicy.NFC_PERMISSION),p.permissions);
        assertEquals(Collections.singletonList(F3ManifestPolicy.NFC_FEATURE),p.features);
        assertTrue(p.privateComponents.isEmpty());
        assertEquals(1,p.nodes.stream().filter(n->n.path.endsWith("/intent-filter")).count());
        assertEquals(0,p.nodes.stream().filter(n->n.path.equals("manifest/uses-feature")).findFirst().get().attributes.get(ManifestPlan.ANDROID+"|required").value);
        ManifestAudit.read(ManifestXml.encode(p)).verify(p);
    }
    @Test public void bluetoothPermissionsHaveExactVersionAndPrivacyBounds() throws Exception {
        for(String privacy:Arrays.asList("full","neverForLocation")) for(boolean location:new boolean[]{false,true}) {
            ManifestPlan p=plan(location?Arrays.asList("bluetooth","location"):Collections.singletonList("bluetooth"),new BluetoothPolicy(privacy));
            assertEquals(30,attr(permission(p,F3ManifestPolicy.BLUETOOTH_PERMISSION),"maxSdkVersion"));
            assertEquals(30,attr(permission(p,F3ManifestPolicy.BLUETOOTH_ADMIN_PERMISSION),"maxSdkVersion"));
            assertEquals(privacy.equals("full")?null:0x10000,attr(permission(p,F3ManifestPolicy.BLUETOOTH_SCAN_PERMISSION),"usesPermissionFlags"));
            assertEquals(!location&&privacy.equals("neverForLocation")?30:null,attr(permission(p,CapabilityCatalog.FINE_LOCATION_PERMISSION),"maxSdkVersion"));
            assertEquals(!location&&privacy.equals("neverForLocation")?30:null,attr(permission(p,CapabilityCatalog.COARSE_LOCATION_PERMISSION),"maxSdkVersion"));
            assertFalse(p.permissions.contains("android.permission.BLUETOOTH_ADVERTISE"));
            assertFalse(p.permissions.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
            assertEquals(p.permissions.size(),new java.util.HashSet<>(p.permissions).size());
            byte[] bytes=ManifestXml.encode(p); ManifestAudit.read(bytes).verify(p); ManifestAudit.verifyTree(bytes,p.root);
        }
    }
    @Test public void widgetsAndJobsHaveOnlyClosedPrivateComponents() throws Exception {
        ManifestPlan p=plan(Arrays.asList("widgets","jobs","network"),null);
        assertEquals(Arrays.asList(F3ManifestPolicy.WIDGET_PROVIDER,F3ManifestPolicy.WIDGET_ACTION_RECEIVER,F3ManifestPolicy.JOB_SERVICE),p.privateComponents);
        assertEquals(Collections.singletonList(ManifestPlan.ACTIVITY),p.exportedComponents);
        assertEquals(Arrays.asList(CapabilityCatalog.INTERNET_PERMISSION,F3ManifestPolicy.NETWORK_STATE_PERMISSION,NotificationPolicy.BOOT_PERMISSION),p.permissions);
        ManifestPlan.Node service=p.nodes.stream().filter(n->n.path.endsWith("/service")).findFirst().get();
        assertEquals(F3ManifestPolicy.JOB_PERMISSION,attr(service,"permission")); assertEquals(0,attr(service,"exported")); assertEquals(-1,attr(service,"enabled")); assertEquals(0,attr(service,"directBootAware"));
        ManifestPlan.Node metadata=p.nodes.stream().filter(n->n.path.endsWith("/meta-data")).findFirst().get(); assertEquals(0x7f020002,attr(metadata,"resource"));
        ManifestAudit.read(ManifestXml.encode(p)).verify(p); ManifestAudit.verifyTree(ManifestXml.encode(p),p.root);
    }
    @Test public void historicalV4AcceptsPriorPoliciesAndRejectsEveryF3Capability() throws Exception {
        IncomingSharePolicy incoming=new IncomingSharePolicy(Arrays.asList("image/*","text/plain"),true);
        NotificationPolicy notifications=new NotificationPolicy(true,true);
        List<String> caps=Arrays.asList("incomingShare","documents","notifications","secrets");
        ManifestPlan current=new ManifestPlan("org.example.generic","Generic",1,"1",0x7f010001,0x7f020001,caps,incoming,notifications);
        ManifestPlan v4=new ManifestPlan(current.appId,current.label,1,"1",current.iconResourceId,current.backupResourceId,caps,incoming,notifications,ManifestPlan.Profile.V4_BEFORE_GENERIC_NATIVE);
        assertArrayEquals(ManifestXml.encode(current),ManifestXml.encode(v4));
        for(String cap:Arrays.asList("nfc","bluetooth","widgets","jobs")) {
            List<String> f3=cap.equals("jobs")?Arrays.asList("jobs","network"):Collections.singletonList(cap);
            assertThrows(IllegalArgumentException.class,()->new ManifestPlan(current.appId,current.label,1,"1",current.iconResourceId,current.backupResourceId,f3,null,null,cap.equals("bluetooth")?new BluetoothPolicy("full"):null,cap.equals("widgets")?0x7f020002:0,ManifestPlan.Profile.V4_BEFORE_GENERIC_NATIVE));
        }
    }
    @Test public void maximumSelectedManifestFitsAuditAndDeduplicatesBoot() throws Exception {
        java.util.List<String> mimes=new java.util.ArrayList<>();for(int i=0;i<32;i++)mimes.add(String.format("application/x-%02d",i));
        ManifestPlan p=new ManifestPlan("org.example.all","All",1,"1",0x7f010001,0x7f020001,CapabilityCatalog.NAMES,
                new IncomingSharePolicy(mimes,true),new NotificationPolicy(true,true),new BluetoothPolicy("neverForLocation"),0x7f020002);
        ManifestAudit.read(ManifestXml.encode(p)).verify(p);
        assertTrue(p.nodes.size()<=ManifestXml.MAX_NODES);
        assertEquals(1,p.permissions.stream().filter(NotificationPolicy.BOOT_PERMISSION::equals).count());
    }
    /** Full Cartesian product of new interacting declaration axes (864 valid selections).
     * Location x BLE privacy tests location-bound coalescing; jobs/network x notification
     * scheduling tests boot coalescing; widget/NFC/incoming axes test tree composition.
     * The independent legacy permission/query axes remain exhaustively tested separately. */
    @Test public void everyF3DependencyPartitionCompositionHasExactDeclarations() throws Exception {
        int checked=0;
        for(int ble=0;ble<3;ble++) for(boolean location:new boolean[]{false,true})
        for(int jobNetwork=0;jobNetwork<3;jobNetwork++) for(int notify=0;notify<4;notify++)
        for(boolean widgets:new boolean[]{false,true}) for(boolean nfc:new boolean[]{false,true}) for(int incoming=0;incoming<3;incoming++) {
            java.util.List<String> caps=new java.util.ArrayList<>(Arrays.asList("biometrics","sensors","speech","voiceRecognition","recording","steps","secrets"));
            if(ble>0)caps.add("bluetooth");if(location)caps.add("location");if(jobNetwork>0)caps.add("network");if(jobNetwork==2)caps.add("jobs");if(notify>0)caps.add("notifications");if(widgets)caps.add("widgets");if(nfc)caps.add("nfc");
            if(incoming>0)caps.addAll(Arrays.asList("incomingShare","documents"));
            BluetoothPolicy bluetooth=ble==0?null:new BluetoothPolicy(ble==1?"full":"neverForLocation");
            NotificationPolicy notification=notify<2?null:new NotificationPolicy(true,notify==3);
            IncomingSharePolicy share=incoming==0?null:new IncomingSharePolicy(Collections.singletonList("text/plain"),incoming==2);
            ManifestPlan p=new ManifestPlan("org.example.partition","Partition",1,"1",0x7f010001,0x7f020001,caps,share,notification,bluetooth,widgets?0x7f020002:0);
            java.util.Set<String> expected=new java.util.TreeSet<>(Arrays.asList("android.permission.USE_BIOMETRIC","android.permission.HIGH_SAMPLING_RATE_SENSORS","android.permission.RECORD_AUDIO","android.permission.ACTIVITY_RECOGNITION"));
            if(jobNetwork>0)expected.add("android.permission.INTERNET");if(jobNetwork==2)expected.add("android.permission.ACCESS_NETWORK_STATE");if(jobNetwork==2||notify>=2)expected.add("android.permission.RECEIVE_BOOT_COMPLETED");
            if(notify>0)expected.add("android.permission.POST_NOTIFICATIONS");if(notify==3)expected.add("android.permission.SCHEDULE_EXACT_ALARM");
            if(nfc)expected.add("android.permission.NFC");
            if(location||ble>0)expected.addAll(Arrays.asList("android.permission.ACCESS_COARSE_LOCATION","android.permission.ACCESS_FINE_LOCATION"));
            if(ble>0)expected.addAll(Arrays.asList("android.permission.BLUETOOTH","android.permission.BLUETOOTH_ADMIN","android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"));
            assertEquals(expected,new java.util.TreeSet<>(p.permissions));assertEquals(expected.size(),p.permissions.size());
            if(ble>0){assertEquals(ble==2?0x10000:null,attr(permission(p,F3ManifestPolicy.BLUETOOTH_SCAN_PERMISSION),"usesPermissionFlags"));assertEquals(!location&&ble==2?30:null,attr(permission(p,CapabilityCatalog.FINE_LOCATION_PERMISSION),"maxSdkVersion"));}
            assertEquals((nfc?1:0)+(ble>0?1:0),p.features.size());assertEquals((notify>=2?2:0)+(widgets?2:0)+(jobNetwork==2?1:0),p.privateComponents.size());
            assertEquals(Arrays.asList("android.intent.action.TTS_SERVICE","android.speech.action.RECOGNIZE_SPEECH"),p.queryActions);
            assertEquals(Arrays.asList("com.jarvys.agent","com.jarvys.agent.recoverytest"),p.queries);
            int expectedNodes=7+expected.size()+p.features.size()+7+(incoming==0?0:4*incoming)+(notify<2?0:notify==3?6:5)+(widgets?5:0)+(jobNetwork==2?1:0);
            assertEquals(expectedNodes,p.nodes.size());ManifestAudit.read(ManifestXml.encode(p)).verify(p);checked++;
        }
        assertEquals(864,checked);
    }
    @Test public void historicalProfileBytesMatchPreF3SourceFixtures() throws Exception {
        String[] names={"V1_BEFORE_SAFE_AREA","V1_BEFORE_CLIPBOARD_BROKER","V2_BEFORE_NATIVE_OPERATIONS","V3_BEFORE_INCOMING_SHARE","V4_BEFORE_GENERIC_NATIVE"};
        // SHA-256 of bytes produced by the repository's pre-F3 HEAD source, not this implementation.
        String[] hashes={"6ef558e6b6a0ba39f2c3dc62cec217c7a830806df03ec2d34eafc9bdb9e3f946","3b011804435261c85e76de7d5165e2b9c6d488092f1404a86211715d003be96c","3b011804435261c85e76de7d5165e2b9c6d488092f1404a86211715d003be96c","f73662d75b009ffe3dd0fb3e27a608c8aa6ac2acdbd8929882f8fc5c6ac11f90","156e561c35553883a81fe8a4387f5bef8f3cecec4b25a55b42ab7fa6840010e3"};
        for(int i=0;i<names.length;i++) {
            java.util.List<String> caps=new java.util.ArrayList<>(Arrays.asList("storage","clipboard","share","export","sensors"));
            if(i>0)caps.addAll(Arrays.asList("biometrics","documents"));
            if(i>=3)caps.addAll(Arrays.asList("network","location","notifications","speech","voiceRecognition","recording","steps"));
            if(i==4)caps.addAll(Arrays.asList("incomingShare","secrets"));
            ManifestPlan p=new ManifestPlan("org.example.history","Historical",7,"7",0x7f010001,0x7f020001,caps,i==4?new IncomingSharePolicy(Arrays.asList("image/*","text/plain"),true):null,i==4?new NotificationPolicy(true,true):null,ManifestPlan.Profile.valueOf(names[i]));
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(ManifestXml.encode(p));StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b&255));assertEquals(hashes[i],hex.toString());
        }
    }
    @Test public void invalidSelectionCannotCreateAuthority() {
        assertThrows(IllegalArgumentException.class,()->plan(Collections.singletonList("jobs"),null));
        assertThrows(IllegalArgumentException.class,()->plan(Collections.singletonList("bluetooth"),null));
        assertThrows(IllegalArgumentException.class,()->plan(Collections.emptyList(),new BluetoothPolicy("full")));
        assertThrows(IllegalArgumentException.class,()->new BluetoothPolicy("false"));
        assertThrows(IllegalArgumentException.class,()->new ManifestPlan("org.example.a","A",1,"1",0x7f010001,0x7f020001,Collections.singletonList("widgets")));
    }
}
