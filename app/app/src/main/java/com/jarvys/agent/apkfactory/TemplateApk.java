package com.jarvys.agent.apkfactory;

import com.jarvys.factory.contract.ManifestPlan;
import com.jarvys.factory.contract.ManifestAudit;
import com.jarvys.factory.contract.CapabilityCatalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded transformer for the bundled Jarvys factory template. Not a general APK editor.
 * Binary layout: AOSP ResourceTypes.h (Apache-2.0). No third-party implementation copied.
 * A returned APK is unsigned. Callers must authenticate the bundled template and verify signing.
 */
public final class TemplateApk {
    private TemplateApk() {}
    public static final int MAX_APK_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;
    private static final int NONE = -1;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String TEMPLATE_PACKAGE = "com.jarvys.factory.template";
    private static final String ACTIVITY = "com.jarvys.factory.runtime.FactoryActivity";

    public static final class Spec {
        public final String appId, label, versionName;
        public final int versionCode;
        public final List<String> capabilities;
        public Spec(String appId, String label, int versionCode, String versionName) {
            this(appId, label, versionCode, versionName, Collections.<String>emptyList());
        }
        public Spec(String appId, String label, int versionCode, String versionName, Iterable<String> capabilities) {
            this.appId=appId; this.label=label; this.versionCode=versionCode; this.versionName=versionName;
            this.capabilities=CapabilityCatalog.select(capabilities);
        }
    }
    public static final class ManifestInfo {
        public final String appId, label, versionName, activityClass;
        public final int versionCode, iconResourceId;
        public final List<String> permissions;
        public final ManifestPlan plan;
        public final Map<String,String> dexSha256;
        private ManifestInfo(String id, String label, int code, String version, int icon,
                             String activity, List<String> permissions) {
            this(id, label, code, version, icon, activity, permissions, null, Collections.<String,String>emptyMap());
        }
        private ManifestInfo(String id, String label, int code, String version, int icon, String activity,
                             List<String> permissions, ManifestPlan plan, Map<String,String> dexSha256) {
            this.appId=id; this.label=label; this.versionCode=code; this.versionName=version;
            this.plan=plan; this.dexSha256=Collections.unmodifiableMap(new TreeMap<>(dexSha256));
            this.iconResourceId=icon; this.activityClass=activity;
            this.permissions=Collections.unmodifiableList(new ArrayList<>(permissions));
        }
    }
    public static ManifestInfo inspect(byte[] apk) throws IOException {
        return inspectFiles(readZip(apk,true), null, ManifestPlan.Profile.CURRENT);
    }
    /** Validate against caller-approved identity/capabilities, never metadata supplied by the APK alone. */
    public static ManifestInfo verify(byte[] apk, Spec expected) throws IOException {
        validateSpec(expected);
        return inspectFiles(readZip(apk,true), expected, ManifestPlan.Profile.CURRENT);
    }
    /** Only the receipt-owning service may opt in to the exact historical v1 profile. */
    public static ManifestInfo verifyExistingV1(byte[] apk, Spec expected) throws IOException {
        validateSpec(expected);
        Map<String,byte[]> files=readZip(apk,true);
        try { return inspectFiles(files,expected,ManifestPlan.Profile.CURRENT); }
        catch(IOException current) {
            try { return inspectFiles(files,expected,ManifestPlan.Profile.V1_BEFORE_SAFE_AREA); }
            catch(IOException legacy) { throw new IOException("Artifact does not match a supported v1 manifest. Rebuild the original project with the same appId and retained key; no receipt or key was replaced.",legacy); }
        }
    }
    public static ManifestInfo verifyAgainstPlan(byte[] apk, ManifestPlan plan) throws IOException {
        Map<String,byte[]> files=readZip(apk,true);
        ManifestAudit.read(required(files,"AndroidManifest.xml")).verify(plan);
        return inspectFiles(files,new Spec(plan.appId,plan.label,plan.versionCode,plan.versionName,plan.capabilities),plan.profile);
    }
    /** v2/v3 signing adds a signing block, but must not alter any ZIP entry, not even resources. */
    public static void verifyUnchangedPayload(byte[] unsigned, byte[] signed) throws IOException {
        Map<String,byte[]> before=readZip(unsigned,true),after=readZip(signed,true);
        check(before.keySet().equals(after.keySet()),"Signed APK entry set changed");
        for(String name:before.keySet()) check(Arrays.equals(before.get(name),after.get(name)),"Signed APK payload changed: "+name);
    }
    private static ManifestInfo inspectFiles(Map<String,byte[]> zip, Spec expected, ManifestPlan.Profile profile) throws IOException {
        byte[] manifest=required(zip,"AndroidManifest.xml");
        ManifestInfo info=new Xml(manifest).info();
        ManifestAudit.Document decoded=ManifestAudit.read(manifest);
        byte[] resources=required(zip,"resources.arsc");
        String iconPath=resolveIconPath(resources,info.iconResourceId);
        check(zip.containsKey(iconPath),"Referenced launcher icon is missing");
        ManifestAudit.Attribute backup=decoded.attribute("manifest/application",ManifestPlan.ANDROID,"dataExtractionRules");
        check(backup.type==1 && backup.value instanceof Integer,"Backup rules must be a resource reference");
        int backupId=(Integer)backup.value;
        String backupPath=resolveResourcePath(resources,backupId,"xml","factory_backup_rules",".xml");
        ManifestAudit.verifyBackupRules(required(zip,backupPath));
        // AAPT may palette-optimize the trusted bundled template icon. Generated icons are
        // normalized RGB/RGBA by FactoryIcon and must retain that checked representation.
        if(!TEMPLATE_PACKAGE.equals(info.appId)) checkPng(required(zip,iconPath));
        check(info.appId.equals(resourcePackageName(resources)),"Manifest/resource package names differ");
        ManifestPlan plan;
        try {
            plan=new ManifestPlan(expected==null?info.appId:expected.appId, expected==null?info.label:expected.label,
                expected==null?info.versionCode:expected.versionCode, expected==null?info.versionName:expected.versionName,
                info.iconResourceId,backupId,expected==null?Collections.<String>emptyList():expected.capabilities,profile);
        } catch(IllegalArgumentException error) { throw new IOException("Invalid manifest plan identity",error); }
        decoded.verify(plan);
        return new ManifestInfo(info.appId,info.label,info.versionCode,info.versionName,info.iconResourceId,
            info.activityClass,plan.permissions,plan,dexInventory(zip));
    }
    private static Map<String,String> dexInventory(Map<String,byte[]> files) throws IOException {
        Map<String,String> result=new TreeMap<>();
        for(Map.Entry<String,byte[]> entry:files.entrySet()) {
            String name=entry.getKey();
            if(!name.toLowerCase(java.util.Locale.ROOT).endsWith(".dex")) continue;
            check(name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"),"Unexpected DEX path");
            byte[] dex=entry.getValue();
            check(dex.length>=112 && dex[0]=='d' && dex[1]=='e' && dex[2]=='x' && dex[3]=='\n' && dex[7]==0,
                "Missing valid DEX header");
            String version=new String(dex,4,3,StandardCharsets.US_ASCII);
            check(Arrays.asList("035","037","038","039","040").contains(version),"Unsupported DEX format");
            check(u32(dex,32)==dex.length && u32(dex,36)==112 && u32(dex,40)==0x12345678L,"DEX header bounds differ");
            result.put(name,sha256(dex));
        }
        check(!result.isEmpty(),"Missing DEX inventory");
        for(int i=1;i<=result.size();i++) check(result.containsKey(i==1?"classes.dex":"classes"+i+".dex"),"Non-contiguous DEX inventory");
        return Collections.unmodifiableMap(result);
    }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result=new StringBuilder();
            for(byte value:digest) { result.append(Character.forDigit((value>>>4)&15,16)); result.append(Character.forDigit(value&15,16)); }
            return result.toString();
        } catch(java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    public static byte[] build(byte[] template, Spec spec, byte[] iconPng,
                               Map<String,byte[]> assetReplacements) throws IOException {
        validateSpec(spec);
        check(iconPng!=null && iconPng.length<=1024*1024,"Icon exceeds 1 MiB");
        checkPng(iconPng);
        Map<String,byte[]> files=readZip(template);
        check(!hasSigningBlock(template),"Template has an APK signing block");
        for (String name:files.keySet()) {
            String u=name.toUpperCase(java.util.Locale.ROOT);
            check(!(u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA") ||
                    u.endsWith(".DSA") || u.endsWith(".EC") || u.equals("META-INF/MANIFEST.MF"))),
                    "Template has signature entries");
        }
        Map<String,String> originalDex=dexInventory(files);
        Xml xml=new Xml(required(files,"AndroidManifest.xml"));
        ManifestInfo old=inspectFiles(files,null,ManifestPlan.Profile.CURRENT);
        check(TEMPLATE_PACKAGE.equals(old.appId) && "FACTORY_APP_LABEL".equals(old.label) &&
                "FACTORY_VERSION".equals(old.versionName) && old.versionCode==1,"Template markers differ");
        check(ACTIVITY.equals(old.activityClass) && old.permissions.isEmpty(),"Unexpected runtime or permissions");
        String iconPath=resolveIconPath(required(files,"resources.arsc"),old.iconResourceId);
        check(files.containsKey(iconPath),"Referenced factory icon is missing");
        files.put("AndroidManifest.xml",xml.rewrite(spec));
        files.put("resources.arsc",renamePackage(required(files,"resources.arsc"),spec.appId,old.iconResourceId));
        files.put(iconPath,iconPng.clone());
        check(assetReplacements!=null && assetReplacements.containsKey("assets/factory-app.json") &&
                assetReplacements.containsKey("assets/www/index.html"),"Config and index.html are required");
        // A generation replaces the complete website rather than leaking stale template assets.
        for(java.util.Iterator<String> i=files.keySet().iterator();i.hasNext();) { String n=i.next(); if(n.startsWith("assets/www/") || n.equals("assets/factory-app.json") || n.equals("assets/factory-provenance.json")) i.remove(); }
        for (Map.Entry<String,byte[]> e:assetReplacements.entrySet()) {
            validName(e.getKey());
            check(e.getKey().startsWith("assets/www/") || e.getKey().equals("assets/factory-app.json") || e.getKey().equals("assets/factory-provenance.json"),"Asset outside factory scope");
            check(e.getValue()!=null && e.getValue().length<=MAX_ENTRY_BYTES,"Asset exceeds limit");
            files.put(e.getKey(),e.getValue().clone());
        }
        byte[] result=writeZip(files);
        ManifestPlan expectedPlan=new ManifestPlan(spec.appId,spec.label,spec.versionCode,spec.versionName,
            old.plan.iconResourceId,old.plan.backupResourceId,spec.capabilities);
        ManifestInfo actual=verifyAgainstPlan(result,expectedPlan);
        check(actual.appId.equals(spec.appId) && actual.label.equals(spec.label) &&
                actual.versionCode==spec.versionCode && actual.versionName.equals(spec.versionName),"Output metadata differs");
        check(originalDex.equals(actual.dexSha256),"DEX inventory or bytes changed");
        Map<String,byte[]> outputFiles=readZip(result);
        for(String name:originalDex.keySet()) check(Arrays.equals(files.get(name),outputFiles.get(name)),"DEX changed: "+name);
        return result;
    }
    private static void validateSpec(Spec s) throws IOException {
        check(s!=null && s.appId!=null && s.appId.length()<=127 &&
                s.appId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"),"Invalid application ID");
        check(!s.appId.equals("com.jarvys.agent") && !s.appId.equals(TEMPLATE_PACKAGE),"Reserved application ID");
        boundedText(s.label,1,80,"label"); boundedText(s.versionName,1,64,"versionName");
        check(s.versionCode>0,"Version code must be positive");
    }
    private static void boundedText(String s,int min,int max,String field) throws IOException {
        check(s!=null && s.length()>=min && s.length()<=max, "Invalid "+field);
        for(int i=0;i<s.length();i++) check(!Character.isISOControl(s.charAt(i)),"Control in "+field);
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s)); }
        catch(CharacterCodingException e) { throw new IOException("Invalid Unicode in "+field,e); }
    }
    private static void check(boolean yes,String message) throws IOException { if(!yes) throw new IOException(message); }
    private static byte[] required(Map<String,byte[]> map,String name) throws IOException {
        byte[] b=map.get(name); check(b!=null,"Missing "+name); return b;
    }
    private static int u16(byte[] b,int p) throws IOException { bounds(b,p,2); return (b[p]&255)|((b[p+1]&255)<<8); }
    private static long u32(byte[] b,int p) throws IOException { bounds(b,p,4); return ((long)u16(b,p)|((long)u16(b,p+2)<<16)); }
    private static int integer(byte[] b,int p) throws IOException { return (int)u32(b,p); }
    private static int small(byte[] b,int p) throws IOException { long v=u32(b,p); check(v<=Integer.MAX_VALUE,"Oversized integer"); return (int)v; }
    private static void bounds(byte[] b,int p,int n) throws IOException { check(p>=0 && n>=0 && p<=b.length-n,"Truncated binary structure"); }
    private static void put32(byte[] b,int p,int v) { for(int i=0;i<4;i++) b[p+i]=(byte)(v>>>(8*i)); }
    private static void le16(ByteArrayOutputStream b,int v) { b.write(v); b.write(v>>>8); }
    private static void le32(ByteArrayOutputStream b,long v) { for(int i=0;i<4;i++) b.write((int)(v>>>(8*i))); }
    private static void bytes(ByteArrayOutputStream b,byte[] v) { b.write(v,0,v.length); }
    private static String decode(byte[] b,int p,int n,java.nio.charset.Charset cs) throws IOException {
        bounds(b,p,n);
        try { return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b,p,n)).toString(); }
        catch(CharacterCodingException e) { throw new IOException("Malformed string",e); }
    }
    private static void validName(String name) throws IOException {
        check(name!=null && name.length()>0 && name.length()<=240 &&
                name.matches("[A-Za-z0-9_.$/-]+") && !name.startsWith("/") && !name.endsWith("/"),"Invalid APK path");
        for(String part:name.split("/",-1)) check(!part.isEmpty() && !part.equals(".") && !part.equals(".."),"Unsafe APK path");
        check(!name.startsWith("lib/") && !name.endsWith(".so"),"Native libraries are outside this template contract");
    }
    private static Map<String,byte[]> readZip(byte[] apk) throws IOException { return readZip(apk,false); }
    private static Map<String,byte[]> readZip(byte[] apk,boolean aligned) throws IOException {
        check(apk!=null && apk.length>=22 && apk.length<=MAX_APK_BYTES,"Invalid APK size");
        int end=-1;
        for(int p=apk.length-22;p>=Math.max(0,apk.length-65557);p--)
            if(integer(apk,p)==0x06054b50 && p+22+u16(apk,p+20)==apk.length) { end=p; break; }
        check(end>=0,"Missing ZIP end");
        check(u16(apk,end+4)==0 && u16(apk,end+6)==0,"Split ZIP unsupported");
        int count=u16(apk,end+10),pos=small(apk,end+16),central=pos;
        check(count>0 && count<=MAX_ENTRIES && u16(apk,end+8)==count,"Invalid ZIP entry count");
        check((long)pos+u32(apk,end+12)==end,"Invalid ZIP directory bounds");
        Map<String,byte[]> result=new LinkedHashMap<>(); List<int[]> spans=new ArrayList<>(); long total=0;
        for(int i=0;i<count;i++) {
            bounds(apk,pos,46); check(integer(apk,pos)==0x02014b50,"Invalid central header");
            int flags=u16(apk,pos+8),method=u16(apk,pos+10),packed=small(apk,pos+20),size=small(apk,pos+24);
            int nl=u16(apk,pos+28),el=u16(apk,pos+30),cl=u16(apk,pos+32),local=small(apk,pos+42);
            check((flags&~0x0808)==0 && (method==0||method==8),"Unsupported ZIP encoding/encryption");
            check(u16(apk,pos+34)==0 && size<=MAX_ENTRY_BYTES && packed<=MAX_ENTRY_BYTES,"Invalid entry bounds");
            bounds(apk,pos+46,nl+el+cl); check(pos+46+nl+el+cl<=end,"Central header exceeds directory");
            String name=decode(apk,pos+46,nl,StandardCharsets.UTF_8); validName(name);
            int mode=(int)(u32(apk,pos+38)>>>16); check((mode&0170000)==0 || (mode&0170000)==0100000,"Non-regular ZIP entry");
            check(!result.containsKey(name),"Duplicate ZIP entry");
            bounds(apk,local,30); check(integer(apk,local)==0x04034b50,"Invalid local header");
            check(u16(apk,local+6)==flags && u16(apk,local+8)==method && u16(apk,local+26)==nl,"Local/central disagreement");
            check(name.equals(decode(apk,local+30,nl,StandardCharsets.UTF_8)),"Local path differs");
            int start=local+30+nl+u16(apk,local+28); bounds(apk,start,packed);
            if(aligned) {
                check(method!=0 || start%4==0,"Stored entry is not 4-byte aligned");
                check(!name.equals("resources.arsc") || method==0,"Resource table must be stored");
            }
            check(start+packed<=central,"Entry intersects directory");
            int entryEnd=start+packed;
            if((flags&8)==0) {
                check(u32(apk,local+14)==u32(apk,pos+16) && u32(apk,local+18)==packed && u32(apk,local+22)==size,"Local ZIP sizes/CRC differ");
            } else {
                int d=entryEnd; if(integer(apk,d)==0x08074b50) d+=4;
                check(u32(apk,d)==u32(apk,pos+16) && u32(apk,d+4)==packed && u32(apk,d+8)==size,"ZIP descriptor differs");
                entryEnd=d+12; check(entryEnd<=central,"Descriptor intersects directory");
            }
            spans.add(new int[]{local,entryEnd});
            byte[] data;
            if(method==0) { check(size==packed,"Stored size differs"); data=Arrays.copyOfRange(apk,start,start+packed); }
            else {
                Inflater z=new Inflater(true); z.setInput(apk,start,packed); data=new byte[size]; int at=0;
                try {
                    while(!z.finished() && at<size) { int n=z.inflate(data,at,size-at); check(n>0,"Incomplete deflate stream"); at+=n; }
                    if(size==0) { byte[] one=new byte[1]; check(z.inflate(one)==0,"Unexpected deflate bytes"); }
                    check(z.finished() && at==size && z.getRemaining()==0,"Deflate size/trailer differs");
                } catch(DataFormatException e) { throw new IOException("Invalid deflate stream",e); } finally { z.end(); }
            }
            CRC32 crc=new CRC32(); crc.update(data); check(crc.getValue()==u32(apk,pos+16),"CRC mismatch");
            total+=size; check(total<=MAX_APK_BYTES,"Expanded APK exceeds limit"); result.put(name,data); pos+=46+nl+el+cl;
        }
        check(pos==end,"Directory entry count differs"); Collections.sort(spans,new java.util.Comparator<int[]>() { public int compare(int[] a,int[] b) { return Integer.compare(a[0],b[0]); } });
        int prior=0; for(int[] s:spans) { check(s[0]>=prior,"Overlapping ZIP entries"); prior=s[1]; }
        return result;
    }
    private static boolean hasSigningBlock(byte[] apk) throws IOException {
        for(int p=apk.length-22;p>=Math.max(0,apk.length-65557);p--) {
            if(integer(apk,p)==0x06054b50 && p+22+u16(apk,p+20)==apk.length) {
                int cd=small(apk,p+16); if(cd<24) return false;
                return Arrays.equals("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII),Arrays.copyOfRange(apk,cd-16,cd));
            }
        }
        throw new IOException("Missing ZIP end");
    }
    private static byte[] writeZip(Map<String,byte[]> files) throws IOException {
        check(files.size()>0 && files.size()<=MAX_ENTRIES,"Too many APK entries");
        ByteArrayOutputStream out=new ByteArrayOutputStream(),central=new ByteArrayOutputStream();
        for(Map.Entry<String,byte[]> e:new TreeMap<>(files).entrySet()) {
            validName(e.getKey()); byte[] n=e.getKey().getBytes(StandardCharsets.UTF_8),data=e.getValue();
            check(data.length<=MAX_ENTRY_BYTES,"Entry too large"); CRC32 crc=new CRC32(); crc.update(data);
            int offset=out.size(),pad=(4-((offset+30+n.length+4)%4))%4;
            le32(out,0x04034b50); le16(out,20); le16(out,0x800); le16(out,0); le16(out,0); le16(out,33);
            le32(out,crc.getValue()); le32(out,data.length); le32(out,data.length); le16(out,n.length); le16(out,4+pad);
            bytes(out,n); le16(out,0xf17e); le16(out,pad); for(int p=0;p<pad;p++) out.write(0); bytes(out,data);
            le32(central,0x02014b50); le16(central,20); le16(central,20); le16(central,0x800); le16(central,0);
            le16(central,0); le16(central,33); le32(central,crc.getValue()); le32(central,data.length); le32(central,data.length);
            le16(central,n.length); le16(central,0); le16(central,0); le16(central,0); le16(central,0); le32(central,0); le32(central,offset); bytes(central,n);
            check(out.size()+central.size()+22<=MAX_APK_BYTES,"APK exceeds output limit");
        }
        int off=out.size(); bytes(out,central.toByteArray()); le32(out,0x06054b50); le16(out,0); le16(out,0);
        le16(out,files.size()); le16(out,files.size()); le32(out,central.size()); le32(out,off); le16(out,0); return out.toByteArray();
    }
    private static final class Chunk {
        final int type,header,size,offset;
        Chunk(byte[] b,int offset,int limit) throws IOException {
            this.offset=offset; bounds(b,offset,8); type=u16(b,offset); header=u16(b,offset+2); size=small(b,offset+4);
            check(header>=8 && size>=header && size%4==0 && offset<=limit-size,"Invalid Android chunk bounds");
        }
    }
    private static final class Pool {
        final List<String> strings=new ArrayList<>(); final boolean utf8;
        Pool(byte[] b) throws IOException {
            Chunk c=new Chunk(b,0,b.length); check(c.type==1 && c.header==28 && c.size==b.length,"Invalid string pool");
            int count=small(b,8),styleCount=small(b,12),flags=integer(b,16),start=small(b,20),styles=small(b,24);
            check(count<=4096 && styleCount==0 && styles==0 && (flags&~257)==0 && start==28+4*count,"Unsupported string pool layout"); utf8=(flags&256)!=0;
            for(int i=0;i<count;i++) {
                int p=start+small(b,28+4*i); check(p>=start && p<b.length,"Invalid string offset");
                int[] at={p}; int units=length(b,at,utf8),len=utf8?length(b,at,true):units*2;
                String s=decode(b,at[0],len,utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
                check(s.length()==units,"String character length differs"); bounds(b,at[0]+len,utf8?1:2);
                check(b[at[0]+len]==0 && (utf8||b[at[0]+len+1]==0),"String terminator missing"); strings.add(s);
            }
        }
        String get(int id) throws IOException { check(id>=0 && id<strings.size(),"Invalid string reference"); return strings.get(id); }
        int add(String s) { strings.add(s); return strings.size()-1; }
        byte[] encode() throws IOException {
            ByteArrayOutputStream data=new ByteArrayOutputStream(),offsets=new ByteArrayOutputStream();
            for(String s:strings) {
                le32(offsets,data.size()); byte[] b=s.getBytes(utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
                emitLength(data,s.length(),utf8); if(utf8) emitLength(data,b.length,true); bytes(data,b); data.write(0); if(!utf8) data.write(0);
            }
            while(data.size()%4!=0) data.write(0); ByteArrayOutputStream out=new ByteArrayOutputStream();
            le16(out,1); le16(out,28); le32(out,28+offsets.size()+data.size()); le32(out,strings.size()); le32(out,0);
            le32(out,utf8?256:0); le32(out,28+offsets.size()); le32(out,0); bytes(out,offsets.toByteArray()); bytes(out,data.toByteArray()); return out.toByteArray();
        }
        static int length(byte[] b,int[] p,boolean utf8) throws IOException {
            if(utf8) { bounds(b,p[0],1); int v=b[p[0]++]&255; if((v&128)!=0) { bounds(b,p[0],1); v=((v&127)<<8)|(b[p[0]++]&255); } return v; }
            int v=u16(b,p[0]); p[0]+=2; if((v&32768)!=0) { v=((v&32767)<<16)|u16(b,p[0]); p[0]+=2; } check(v>=0 && v<=65535,"Oversized string"); return v;
        }
        static void emitLength(ByteArrayOutputStream b,int n,boolean utf8) throws IOException {
            if(utf8) { check(n<=32767,"String too long"); if(n>=128) b.write((n>>>8)|128); b.write(n); }
            else { check(n<=65535,"String too long"); if(n>=32768) le16(b,(n>>>16)|32768); le16(b,n); }
        }
    }
    private static final class Attr {
        final byte[] node; final int pos,type,data,nameIndex; final String ns,name; final Pool pool;
        Attr(byte[] node,int pos,Pool pool) throws IOException {
            this.node=node; this.pos=pos; this.pool=pool; int nsIndex=integer(node,pos); nameIndex=integer(node,pos+4);
            ns=nsIndex==NONE?"":pool.get(nsIndex); name=pool.get(nameIndex);
            int raw=integer(node,pos+8); if(raw!=NONE) pool.get(raw);
            check(u16(node,pos+12)==8 && node[pos+14]==0,"Invalid typed attribute"); type=node[pos+15]&255; data=integer(node,pos+16);
            if(type==3) { String text=pool.get(data); check(raw==NONE || text.equals(pool.get(raw)),"Raw/typed string disagreement"); }
        }
        String text() throws IOException { check(type==3,"Expected string attribute "+name); return pool.get(data); }
        int number() throws IOException { check(type==16||type==17||type==18,"Expected integer attribute "+name); return data; }
        void string(int index) { put32(node,pos+8,index); node[pos+15]=3; put32(node,pos+16,index); }
        void number(int value) { put32(node,pos+8,NONE); node[pos+15]=16; put32(node,pos+16,value); }
    }
    private static final class Xml {
        final List<byte[]> chunks=new ArrayList<>(); Pool pool; int poolIndex=-1;
        int[] resourceIds=null;
        Attr appId,label,versionCode,versionName,icon,activity;
        final List<String> permissions=new ArrayList<>();
        Xml(byte[] b) throws IOException {
            check(b.length<=1024*1024,"Manifest too large"); Chunk outer=new Chunk(b,0,b.length);
            check(outer.type==3 && outer.header==8 && outer.size==b.length,"Invalid binary XML");
            List<String> stack=new ArrayList<>(); Set<String> seen=new HashSet<>(); int namespaces=0; boolean rootClosed=false;
            for(int p=8;p<b.length;) {
                Chunk c=new Chunk(b,p,b.length); byte[] n=Arrays.copyOfRange(b,p,p+c.size); chunks.add(n); p+=c.size;
                if(c.type==1) { check(pool==null && chunks.size()==1,"Duplicate/misplaced pool"); pool=new Pool(n); poolIndex=chunks.size()-1; continue; }
                check(pool!=null,"Missing XML pool");
                if(c.type==0x180) {
                    check(resourceIds==null && stack.isEmpty() && !rootClosed && c.header==8 && c.size<=8+4*pool.strings.size(),"Invalid resource map");
                    resourceIds=new int[(c.size-8)/4]; for(int r=0;r<resourceIds.length;r++) resourceIds[r]=integer(n,8+4*r); continue;
                }
                check(c.header==16 && c.size>=24,"Invalid XML node");
                if(c.type==0x100 || c.type==0x101) {
                    check("android".equals(pool.get(integer(n,16))) && ANDROID.equals(pool.get(integer(n,20))),"Unexpected namespace");
                    namespaces+=(c.type==0x100?1:-1); check(namespaces>=0 && namespaces<=1,"Namespace mismatch"); continue;
                }
                check(c.type==0x102||c.type==0x103,"Unsupported XML node");
                check(integer(n,16)==NONE,"Element namespace unsupported"); String name=pool.get(integer(n,20));
                check(name.matches("[a-z][a-z-]*"),"Invalid template element name");
                if(c.type==0x103) {
                    check(c.size==24 && !stack.isEmpty() && name.equals(stack.get(stack.size()-1)),"XML end mismatch");
                    stack.remove(stack.size()-1); if(stack.isEmpty()) rootClosed=true; continue;
                }
                check(!rootClosed && namespaces==1 && c.size>=36,"Invalid element order"); stack.add(name); StringBuilder pathBuilder=new StringBuilder(); for(String part:stack) { if(pathBuilder.length()>0) pathBuilder.append('/'); pathBuilder.append(part); } String path=pathBuilder.toString();
                check(Arrays.asList("manifest","manifest/uses-sdk","manifest/uses-permission","manifest/application",
                    "manifest/application/activity","manifest/application/activity/intent-filter",
                    "manifest/application/activity/intent-filter/action","manifest/application/activity/intent-filter/category").contains(path),"Unsupported template component "+path);
                check(seen.add(path),"Duplicate template element "+path);
                check(u16(n,24)==20 && u16(n,26)==20 && c.size==36+20*u16(n,28),"Unsupported attribute layout");
                Map<String,Attr> attrs=new LinkedHashMap<>();
                for(int a=36;a<n.length;a+=20) { Attr v=new Attr(n,a,pool); check(attrs.put(v.ns+"|"+v.name,v)==null,"Duplicate attribute"); }
                if(path.equals("manifest")) { appId=attr(attrs,"","package"); versionCode=attr(attrs,ANDROID,"versionCode"); versionName=attr(attrs,ANDROID,"versionName"); }
                if(path.equals("manifest/uses-sdk")) { check(attr(attrs,ANDROID,"minSdkVersion").number()>=24,"Unsupported min SDK"); }
                if(path.equals("manifest/uses-permission")) permissions.add(attr(attrs,ANDROID,"name").text());
                if(path.equals("manifest/application")) {
                    label=attr(attrs,ANDROID,"label"); icon=attr(attrs,ANDROID,"icon"); check(icon.type==1,"Icon must be a resource reference");
                    check(!attrs.containsKey(ANDROID+"|roundIcon"),"Unexpected round icon");
                    Attr debug=attrs.get(ANDROID+"|debuggable"); check(debug==null||attr(attrs,ANDROID,"debuggable").number()==0,"Debuggable template");
                    check(attr(attrs,ANDROID,"allowBackup").number()==0,"Backup-enabled template");
                    check(attr(attrs,ANDROID,"usesCleartextTraffic").number()==0,"Cleartext-enabled template");
                }
                if(path.equals("manifest/application/activity")) { activity=attr(attrs,ANDROID,"name"); check(attr(attrs,ANDROID,"exported").number()!=0,"Missing exported launcher"); }
                if(path.endsWith("/action")) check("android.intent.action.MAIN".equals(attr(attrs,ANDROID,"name").text()),"Unexpected action");
                if(path.endsWith("/category")) check("android.intent.category.LAUNCHER".equals(attr(attrs,ANDROID,"name").text()),"Unexpected category");
            }
            check(stack.isEmpty() && namespaces==0 && rootClosed && appId!=null && label!=null && icon!=null && activity!=null &&
                    seen.contains("manifest/uses-sdk") && seen.contains("manifest/application/activity/intent-filter/category") &&
                    seen.contains("manifest/application/activity/intent-filter/action"),"Incomplete factory manifest");
        }
        ManifestInfo info() throws IOException { return new ManifestInfo(appId.text(),label.text(),versionCode.number(),versionName.text(),icon.data,activity.text(),permissions); }
        byte[] rewrite(Spec s) throws IOException {
            appId.string(pool.add(s.appId)); label.string(pool.add(s.label)); versionName.string(pool.add(s.versionName)); versionCode.number(s.versionCode);
            chunks.set(poolIndex,pool.encode()); ByteArrayOutputStream out=new ByteArrayOutputStream(); int len=8; for(byte[] b:chunks) len+=b.length;
            le16(out,3); le16(out,8); le32(out,len); for(byte[] b:chunks) bytes(out,b); return out.toByteArray();
        }
        Attr attr(Map<String,Attr> a,String ns,String name) throws IOException {
            Attr r=a.get(ns+"|"+name); check(r!=null,"Missing attribute "+name);
            if(ANDROID.equals(ns)) {
                int expected;
                switch(name) {
                    case "label": expected=0x01010001; break;
                    case "icon": expected=0x01010002; break;
                    case "name": expected=0x01010003; break;
                    case "exported": expected=0x01010010; break;
                    case "debuggable": expected=0x0101000f; break;
                    case "versionCode": expected=0x0101021b; break;
                    case "versionName": expected=0x0101021c; break;
                    case "minSdkVersion": expected=0x0101020c; break;
                    case "allowBackup": expected=0x01010280; break;
                    case "usesCleartextTraffic": expected=0x010104ec; break;
                    default: throw new IOException("Unknown required Android attribute");
                }
                check(resourceIds!=null && r.nameIndex<resourceIds.length && resourceIds[r.nameIndex]==expected,"Android attribute resource ID mismatch");
            } else check(resourceIds==null || r.nameIndex>=resourceIds.length || resourceIds[r.nameIndex]==0,"Unexpected non-Android attribute ID");
            return r;
        }
    }
    /** Resolve the template's single drawable/factory_icon through ARSC, even after AAPT path shortening. */
    private static String resolveIconPath(byte[] b,int iconId) throws IOException {
        return resolveResourcePath(b,iconId,"drawable","factory_icon",".png");
    }
    private static String resolveResourcePath(byte[] b,int iconId,String resourceType,String resourceName,String suffix) throws IOException {
        check(b.length<=8*1024*1024,"Resource table too large"); Chunk root=new Chunk(b,0,b.length);
        check(root.type==2 && root.header==12 && root.size==b.length && u32(b,8)==1,"Expected one resource package");
        Pool values=null; String path=null; int packages=0,matches=0;
        for(int p=12;p<b.length;) {
            Chunk c=new Chunk(b,p,b.length);
            if(c.type==1) { check(values==null,"Duplicate resource value pool"); values=new Pool(Arrays.copyOfRange(b,p,p+c.size)); }
            else if(c.type==0x200) {
                check(values!=null && ++packages==1 && (c.header==288 || c.header==284),"Unsupported resource package");
                int packageId=small(b,p+8); check(packageId==(iconId>>>24),"Icon package differs");
                if(c.header==288) check(u32(b,p+284)==0,"Resource type ID offset unsupported");
                int typeOffset=small(b,p+268),keyOffset=small(b,p+276);
                check(typeOffset>=c.header && keyOffset>=c.header && typeOffset<c.size && keyOffset<c.size,"Invalid package pools");
                Chunk tc=new Chunk(b,p+typeOffset,p+c.size),kc=new Chunk(b,p+keyOffset,p+c.size);
                check(tc.type==1 && kc.type==1,"Missing package string pools");
                Pool types=new Pool(Arrays.copyOfRange(b,tc.offset,tc.offset+tc.size));
                Pool keys=new Pool(Arrays.copyOfRange(b,kc.offset,kc.offset+kc.size));
                int wantedType=(iconId>>>16)&255,wantedEntry=iconId&65535;
                check(resourceType.equals(types.get(wantedType-1)),"Launcher resource is not drawable");
                for(int q=p+c.header;q<p+c.size;) {
                    Chunk child=new Chunk(b,q,p+c.size);
                    if(child.type==0x201) check(child.header>=24,"Invalid resource type header");
                    if(child.type==0x201 && (b[q+8]&255)==wantedType) {
                        check(child.header>=24 && (b[q+9]&255)==0 && u16(b,q+10)==0,"Unsupported launcher type layout");
                        int count=small(b,q+12),start=small(b,q+16);
                        check(count<=65536 && start>=child.header+(long)count*4 && start<=child.size,"Invalid launcher type bounds");
                        if(wantedEntry<count) {
                            long relative=u32(b,q+child.header+4*wantedEntry);
                            if(relative!=0xffffffffL) {
                                check(relative<=child.size-start-16,"Invalid launcher entry bounds"); int e=q+start+(int)relative;
                                int configSize=small(b,q+20);
                                check(configSize>=28 && configSize<=64 && child.header==20+configSize,"Unsupported resource configuration");
                                for(int r=4;r<configSize;r++) {
                                    int expected=(resourceType.equals("drawable") && (r==14 || r==15))?255:0;
                                    check((b[q+20+r]&255)==expected,"Resource lacks its unqualified default/nodpi configuration");
                                }
                                int size=u16(b,e),flags=u16(b,e+2);
                                check(size==8 && (flags&~6)==0,"Complex/compact launcher resource unsupported");
                                check(resourceName.equals(keys.get(small(b,e+4))),"Launcher resource name differs");
                                check(u16(b,e+size)==8 && b[e+size+2]==0 && b[e+size+3]==3,"Launcher resource is not a file string");
                                path=values.get(small(b,e+size+4)); validName(path);
                                check(path.startsWith("res/") && path.endsWith(suffix) && !path.endsWith(".9.png"),"Invalid launcher resource path");
                                check(++matches==1,"Multiple launcher resource configurations unsupported");
                            }
                        }
                    }
                    q+=child.size;
                }
            } else throw new IOException("Unexpected resource root chunk");
            p+=c.size;
        }
        check(packages==1 && matches==1 && path!=null,"Launcher resource not resolved"); return path;
    }
    private static String resourcePackageName(byte[] b) throws IOException {
        for(int p=12;p<b.length;) {
            Chunk c=new Chunk(b,p,b.length);
            if(c.type==0x200) {
                check(c.header==284 || c.header==288,"Unsupported resource package header");
                int len=0; while(len<256 && u16(b,p+12+len)!=0) len+=2;
                check(len<256,"Unterminated resource package name"); return decode(b,p+12,len,StandardCharsets.UTF_16LE);
            }
            p+=c.size;
        }
        throw new IOException("Missing resource package");
    }
    private static byte[] renamePackage(byte[] source,String name,int iconId) throws IOException {
        check(source.length<=8*1024*1024,"Resource table too large"); byte[] b=source.clone(); Chunk root=new Chunk(b,0,b.length);
        check(root.type==2 && root.header==12 && root.size==b.length && u32(b,8)==1,"Expected one resource package");
        int packages=0;
        for(int p=12;p<b.length;) {
            Chunk c=new Chunk(b,p,b.length);
            if(c.type==0x200) {
                packages++; check(c.header==288 || c.header==284,"Unsupported package header");
                int id=small(b,p+8); check(id>0 && id<256 && (iconId>>>24)==id,"Icon/package ID mismatch");
                int len=0; while(len<256 && u16(b,p+12+len)!=0) len+=2;
                check(len<256 && TEMPLATE_PACKAGE.equals(decode(b,p+12,len,StandardCharsets.UTF_16LE)),"Unexpected resource package name");
                byte[] encoded=name.getBytes(StandardCharsets.UTF_16LE); check(encoded.length<256,"Resource package name too long");
                Arrays.fill(b,p+12,p+268,(byte)0); System.arraycopy(encoded,0,b,p+12,encoded.length);
                int typePool=small(b,p+268),keyPool=small(b,p+276);
                check(typePool>=c.header && keyPool>=c.header && typePool<c.size && keyPool<c.size,"Invalid resource pool offset");
                check(u16(b,p+typePool)==1 && u16(b,p+keyPool)==1,"Missing resource pools");
                for(int q=p+c.header;q<p+c.size;) { Chunk child=new Chunk(b,q,p+c.size); check(child.type==1||child.type==0x201||child.type==0x202,"Unsupported resource child"); q+=child.size; }
            } else check(c.type==1,"Unexpected resource root chunk");
            p+=c.size;
        }
        check(packages==1,"Resource package count differs"); return b;
    }
    private static void checkPng(byte[] png) throws IOException {
        byte[] sig={(byte)137,80,78,71,13,10,26,10}; check(png.length>=45 && Arrays.equals(sig,Arrays.copyOf(png,8)),"Invalid PNG header");
        int pos=8,width=0,height=0,channels=0; boolean ihdr=false,idat=false,iend=false;
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        while(pos<png.length) {
            bounds(png,pos,12); long len=((png[pos]&255L)<<24)|((png[pos+1]&255L)<<16)|((png[pos+2]&255L)<<8)|(png[pos+3]&255L);
            check(len<=1024*1024,"PNG chunk too large"); int n=(int)len; bounds(png,pos+8,n+4);
            String type=decode(png,pos+4,4,StandardCharsets.US_ASCII); CRC32 crc=new CRC32(); crc.update(png,pos+4,n+4);
            int cp=pos+8+n; long expected=((png[cp]&255L)<<24)|((png[cp+1]&255L)<<16)|((png[cp+2]&255L)<<8)|(png[cp+3]&255L);
            check(crc.getValue()==expected,"PNG CRC differs");
            if(!ihdr) {
                check(type.equals("IHDR") && n==13,"PNG IHDR missing"); ihdr=true;
                width=(int)(((png[pos+8]&255L)<<24)|((png[pos+9]&255L)<<16)|((png[pos+10]&255L)<<8)|(png[pos+11]&255L));
                height=(int)(((png[pos+12]&255L)<<24)|((png[pos+13]&255L)<<16)|((png[pos+14]&255L)<<8)|(png[pos+15]&255L));
                check(width>=1 && height>=1 && width<=1024 && height<=1024,"Icon dimensions out of bounds");
                check(png[pos+16]==8 && (png[pos+17]==6||png[pos+17]==2) && png[pos+18]==0 && png[pos+19]==0 && png[pos+20]==0,"Icon must be 8-bit noninterlaced RGB/RGBA PNG");
                            channels=png[pos+17]==6?4:3;
            } else check(!type.equals("IHDR"),"Duplicate PNG IHDR");
            if(type.equals("IDAT")) { idat=true; compressed.write(png,pos+8,n); }
            if(type.equals("IEND")) { check(n==0 && pos+12==png.length,"Invalid PNG IEND"); iend=true; }
            check(!type.equals("acTL") && !type.equals("npTc"),"Animated/nine-patch icons unsupported"); pos+=12+n;
        }
        check(ihdr && idat && iend,"Incomplete PNG");
        int row=width*channels+1,expectedSize=row*height;
        Inflater inflater=new Inflater(); inflater.setInput(compressed.toByteArray()); byte[] scanlines=new byte[expectedSize]; int at=0;
        try {
            while(at<expectedSize && !inflater.finished()) { int n=inflater.inflate(scanlines,at,expectedSize-at); check(n>0,"Incomplete PNG pixels"); at+=n; }
            check(at==expectedSize && inflater.finished() && inflater.getRemaining()==0,"PNG pixel stream size differs");
            for(int y=0;y<height;y++) check((scanlines[y*row]&255)<=4,"Invalid PNG scanline filter");
        } catch(DataFormatException e) { throw new IOException("Invalid PNG compression",e); } finally { inflater.end(); }
    }
}
