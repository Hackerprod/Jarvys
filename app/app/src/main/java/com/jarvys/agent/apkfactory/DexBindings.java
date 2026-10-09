package com.jarvys.agent.apkfactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;

/** Bounded DEX metadata audit, not a bytecode verifier. Callers must authenticate the template
 * and retain exact full-DEX hashes. No reflection, class loading, or code execution is used.
 * Layout follows the AOSP DEX format (https://source.android.com/docs/core/runtime/dex-format).
 */
public final class DexBindings {
    private DexBindings() {}
    private static final int MAX_BYTES=16*1024*1024, MAX_TOTAL=32*1024*1024;
    private static final int MAX_ITEMS=250000, MAX_STRING=1024*1024;
    private static final Map<String,String> REQUIRED;
    static {
        Map<String,String> required=new LinkedHashMap<>();
        required.put("Lcom/jarvys/factory/runtime/FactoryActivity;","Landroid/app/Activity;");
        required.put("Landroidx/core/app/CoreComponentFactory;","Landroid/app/AppComponentFactory;");
        REQUIRED=Collections.unmodifiableMap(required);
    }

    /** Input contains only canonical classes.dex, classes2.dex, ... with no gaps. */
    public static Map<String,String> verify(Map<String,byte[]> dexFiles) throws IOException {
        check(dexFiles!=null && !dexFiles.isEmpty() && dexFiles.size()<=32,"DEX entry count");
        Map<String,String> evidence=new LinkedHashMap<>();
        Set<String> definitions=new HashSet<>();
        long total=0;
        for(int i=1;i<=dexFiles.size();i++) {
            String path=i==1?"classes.dex":"classes"+i+".dex";
            byte[] bytes=dexFiles.get(path);
            check(bytes!=null,"Missing canonical DEX entry: "+path);
            total+=bytes.length;
            check(total<=MAX_TOTAL,"DEX total size");
            new Reader(bytes).verify(path,definitions,evidence);
        }
        check(evidence.size()==REQUIRED.size(),"Required component class definition missing");
        return Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    private static final class Reader {
        final byte[] b;
        int data, strings, types, protos, fields, methods, classes;
        int stringOff,typeOff,protoOff,fieldOff,methodOff,classOff;
        long listItems;
        String[] names,descriptors;
        Reader(byte[] bytes) { b=bytes; }
        void verify(String path,Set<String> definitions,Map<String,String> evidence) throws IOException {
            check(b.length>=112 && b.length<=MAX_BYTES,"DEX size");
            check(b[0]=='d' && b[1]=='e' && b[2]=='x' && b[3]=='\n' && b[7]==0,"DEX magic");
            String version=""+(char)b[4]+(char)b[5]+(char)b[6];
            check(version.equals("035") || version.equals("037") || version.equals("038") ||
                    version.equals("039") || version.equals("040"),"Unsupported DEX version");
            check(u32(32)==b.length && u32(36)==112 && u32(40)==0x12345678L,"DEX header");
            check(u32(44)==0 && u32(48)==0,"DEX link section unsupported");
            try {
                MessageDigest sha=MessageDigest.getInstance("SHA-1");
                sha.update(b,32,b.length-32);
                check(Arrays.equals(Arrays.copyOfRange(b,12,32),sha.digest()),"DEX signature");
            } catch(NoSuchAlgorithmException e) { throw new IOException("SHA-1 unavailable",e); }
            Adler32 adler=new Adler32(); adler.update(b,12,b.length-12);
            check(u32(8)==adler.getValue(),"DEX checksum");
            data=integer(108); check(data>=112 && (data&3)==0 && u32(104)==b.length-data,"DEX data section");
            strings=count(56); stringOff=table(60,strings,4);
            types=count(64); typeOff=table(68,types,4); check(types<=65535,"DEX type count");
            protos=count(72); protoOff=table(76,protos,12); check(protos<=65535,"DEX proto count");
            fields=count(80); fieldOff=table(84,fields,8); check(fields<=65535,"DEX field count");
            methods=count(88); methodOff=table(92,methods,8); check(methods<=65535,"DEX method count");
            classes=count(96); classOff=table(100,classes,32);
            int end=112;
            int[] offsets={stringOff,typeOff,protoOff,fieldOff,methodOff,classOff};
            int[] sizes={strings*4,types*4,protos*12,fields*8,methods*8,classes*32};
            for(int i=0;i<offsets.length;i++) if(sizes[i]>0) {
                check(offsets[i]>=end,"Overlapping or unordered DEX tables"); end=offsets[i]+sizes[i];
            }
            map();
            names=new String[strings];
            long decoded=0;
            for(int i=0;i<strings;i++) { names[i]=string(integer(stringOff+i*4)); decoded+=names[i].length(); check(decoded<=MAX_TOTAL,"DEX decoded strings budget"); }
            descriptors=new String[types];
            Set<String> uniqueTypes=new HashSet<>();
            for(int i=0;i<types;i++) {
                descriptors[i]=name(integer(typeOff+i*4));
                check(descriptor(descriptors[i]) && uniqueTypes.add(descriptors[i]),"Invalid or duplicate DEX type");
            }
            for(int i=0;i<protos;i++) {
                int p=protoOff+i*12; name(integer(p)); type(integer(p+4)); typeList(integer(p+8),false);
            }
            for(int i=0;i<fields;i++) {
                int p=fieldOff+i*8; object(type(u16(p))); check(!type(u16(p+2)).equals("V"),"Void field"); name(integer(p+4));
            }
            for(int i=0;i<methods;i++) {
                int p=methodOff+i*8; String owner=type(u16(p));
                check(owner.startsWith("L") || owner.startsWith("["),"DEX method owner type");
                index(u16(p+2),protos); name(integer(p+4));
            }
            for(int i=0;i<classes;i++) {
                int p=classOff+i*32, cls=integer(p); String desc=type(cls); object(desc);
                check(definitions.add(desc),"Duplicate DEX class definition: "+desc);
                int flags=integer(p+4); long parent=u32(p+8);
                String superclass=parent==0xffffffffL?null:type(integer(p+8));
                if(superclass!=null) object(superclass);
                typeList(integer(p+12),true);
                if(u32(p+16)!=0xffffffffL) name(integer(p+16));
                optionalData(integer(p+20),4); optionalData(integer(p+28),1);
                String expected=REQUIRED.get(desc);
                if(expected!=null) {
                    check((flags&7)==1 && (flags&(0x200|0x400|0x2000))==0,"Component is not public concrete class: "+desc);
                    check(expected.equals(superclass),"Component superclass mismatch: "+desc);
                }
                boolean constructor=classData(integer(p+24),cls);
                if(expected!=null) {
                    check(constructor,"Component public no-arg constructor missing: "+desc);
                    evidence.put(desc.substring(1,desc.length()-1).replace('/','.'),path);
                }
            }
        }
        void map() throws IOException {
            int p=integer(52); requiredData(p,4); int n=integer(p);
            check(n>0 && n<=128,"DEX map size"); span(p+4,(long)n*12);
            Set<Integer> seen=new HashSet<>(); long last=-1;
            for(int i=0;i<n;i++) {
                int q=p+4+i*12, kind=u16(q), amount=integer(q+4), offset=integer(q+8);
                check(u16(q+2)==0 && amount>0 && offset>last && seen.add(kind),"DEX map ordering"); last=offset;
                check(offset<b.length,"DEX map offset");
                if(kind==0) check(amount==1 && offset==0,"DEX map header");
                else if(kind>=1 && kind<=6) {
                    int[] counts={strings,types,protos,fields,methods,classes};
                    int[] offsets={stringOff,typeOff,protoOff,fieldOff,methodOff,classOff};
                    check(amount==counts[kind-1] && offset==offsets[kind-1],"DEX map/table mismatch");
                } else { requiredData(offset,1); if(kind==0x1000) check(amount==1 && offset==p,"DEX map self"); }
            }
            check(seen.contains(0) && seen.contains(0x1000),"DEX map required items");
            int[] counts={strings,types,protos,fields,methods,classes};
            for(int i=0;i<6;i++) check((counts[i]>0)==seen.contains(i+1),"DEX missing map table");
        }
        boolean classData(int offset,int owner) throws IOException {
            if(offset==0) return false;
            requiredData(offset,1); Cursor c=new Cursor(offset);
            int sf=c.uleb(), inf=c.uleb(), dm=c.uleb(), vm=c.uleb();
            check((long)sf+inf<=fields && (long)dm+vm<=methods,"DEX class member count");
            Set<Integer> fieldDefinitions=new HashSet<>();
            for(int n:new int[]{sf,inf}) {
                long id=0;
                for(int j=0;j<n;j++) { int delta=c.uleb(); check(j==0 || delta>0,"Duplicate encoded field"); id+=delta; check(id<fields && fieldDefinitions.add((int)id),"Encoded field index/duplicate"); check(u16(fieldOff+(int)id*8)==owner,"Encoded field owner"); c.uleb(); }
            }
            boolean found=false;
            Set<Integer> methodDefinitions=new HashSet<>();
            for(int group=0;group<2;group++) {
                int n=group==0?dm:vm; long id=0;
                for(int j=0;j<n;j++) {
                    int delta=c.uleb(); check(j==0 || delta>0,"Duplicate encoded method"); id+=delta; check(id<methods && methodDefinitions.add((int)id),"Encoded method index/duplicate");
                    int flags=c.uleb(), code=c.uleb(), m=methodOff+(int)id*8;
                    check(u16(m)==owner,"Encoded method owner");
                    if(code!=0) { requiredData(code,4); span(code,16); span(code+16,u32(code+12)*2); }
                    if(group==0 && name(integer(m+4)).equals("<init>")) {
                        int proto=protoOff+u16(m+2)*12;
                        if(type(integer(proto+4)).equals("V") && integer(proto+8)==0 &&
                                (flags&7)==1 && (flags&0x10000)!=0 && (flags&(8|0x100|0x400))==0 && code!=0) found=true;
                    }
                }
            }
            return found;
        }
        void typeList(int p,boolean objects) throws IOException {
            if(p==0) return;
            requiredData(p,4); int n=count(p); span(p+4,(long)n*2);
            listItems+=n; check(listItems<=2*1024*1024,"DEX type-list work budget");
            for(int i=0;i<n;i++) { String t=type(u16(p+4+i*2)); if(objects) object(t); else check(!t.equals("V"),"Void parameter"); }
        }
        String string(int p) throws IOException {
            requiredData(p,1); Cursor c=new Cursor(p); int length=c.uleb(); check(length<=MAX_STRING,"DEX string length");
            StringBuilder s=new StringBuilder(length);
            for(int i=0;i<length;i++) {
                int a=c.byteValue(), value;
                check(a!=0,"Truncated DEX string");
                if(a<0x80) value=a;
                else if((a&0xe0)==0xc0) { int d=c.byteValue(); check((d&0xc0)==0x80,"DEX MUTF-8 continuation"); value=((a&31)<<6)|(d&63); check(value>=0x80 || (a==0xc0 && d==0x80),"DEX MUTF-8 overlong"); }
                else { check((a&0xf0)==0xe0,"DEX MUTF-8 lead"); int d=c.byteValue(),e=c.byteValue(); check((d&0xc0)==0x80 && (e&0xc0)==0x80,"DEX MUTF-8 continuation"); value=((a&15)<<12)|((d&63)<<6)|(e&63); check(value>=0x800,"DEX MUTF-8 overlong"); }
                s.append((char)value);
            }
            check(c.byteValue()==0,"DEX string terminator/length"); return s.toString();
        }
        String name(int i) throws IOException { index(i,strings); return names[i]; }
        String type(int i) throws IOException { index(i,types); return descriptors[i]; }
        int count(int p) throws IOException { int n=integer(p); check(n<=MAX_ITEMS,"DEX table count"); return n; }
        int table(int p,int n,int width) throws IOException { int off=integer(p); if(n==0) { check(off==0,"Empty DEX table offset"); return 0; } check(off>=112 && (off&3)==0 && (long)off+(long)n*width<=data,"DEX table span"); return off; }
        void optionalData(int p,int align) throws IOException { if(p!=0) requiredData(p,align); }
        void requiredData(int p,int align) throws IOException { check(p>=data && p<b.length && p%align==0,"DEX data offset"); }
        void span(int p,long n) throws IOException { check(p>=0 && n>=0 && (long)p+n<=b.length,"DEX bounds"); }
        int u16(int p) throws IOException { span(p,2); return (b[p]&255)|((b[p+1]&255)<<8); }
        long u32(int p) throws IOException { span(p,4); return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24); }
        int integer(int p) throws IOException { long n=u32(p); check(n<=Integer.MAX_VALUE,"DEX unsigned overflow"); return (int)n; }
        final class Cursor {
            int p; Cursor(int offset) { p=offset; }
            int byteValue() throws IOException { span(p,1); return b[p++]&255; }
            int uleb() throws IOException {
                long value=0;
                for(int i=0;i<5;i++) { int a=byteValue(); value|=(long)(a&127)<<(i*7); if((a&128)==0) { check(value<=Integer.MAX_VALUE && (i==0 || (a&127)!=0),"DEX ULEB overflow/noncanonical"); return (int)value; } }
                throw new IOException("DEX ULEB too long");
            }
        }
    }
    private static boolean descriptor(String s) {
        int p=0; while(p<s.length() && s.charAt(p)=='[') p++;
        if(p>255 || p==s.length()) return false;
        String tail=s.substring(p);
        if(tail.length()==1) return "ZBSCIJFD".contains(tail) || (p==0 && tail.equals("V"));
        if(tail.charAt(0)!='L' || tail.charAt(tail.length()-1)!=';' || tail.length()<3) return false;
        boolean segment=false;
        for(int i=1;i<tail.length()-1;i++) {
            char c=tail.charAt(i);
            if(c=='/') { if(!segment) return false; segment=false; }
            else { if(c<=32 || c=='.' || c==';' || c=='[' || c=='\\' || c=='(' || c==')') return false; segment=true; }
        }
        return segment;
    }
    private static void object(String s) throws IOException { check(s.startsWith("L"),"DEX class must be object type"); }
    private static void index(int i,int size) throws IOException { check(i>=0 && i<size,"DEX index bounds"); }
    private static void check(boolean ok,String message) throws IOException { if(!ok) throw new IOException(message); }
}
