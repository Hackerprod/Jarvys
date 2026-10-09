package com.jarvys.factory.contract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic typed AXML encoder. Encoding a fragment does not authorize an APK or a capability. */
public final class ManifestXml {
    private ManifestXml() {}
    public static final int MAX_NODES = 128, MAX_DEPTH = 8, MAX_ATTRIBUTES = 512, MAX_STRINGS = 4096;
    public static byte[] encode(ManifestPlan plan) throws IOException {
        if (plan == null) throw new IOException("Missing manifest plan");
        return encodeTree(plan.root);
    }
    /** Compiled construction vocabulary only; never called with project-defined nodes. */
    public static byte[] encodeTree(ManifestNodes.Element root) throws IOException {
        return new Writer(root).write();
    }
    private static final class Writer {
        final ManifestNodes.Element root;
        final List<String> strings = new ArrayList<>();
        final Map<String, Integer> text = new LinkedHashMap<>(), attrs = new LinkedHashMap<>();
        final TreeMap<Integer, String> resourceNames = new TreeMap<>();
        int nodes, attributeCount;
        Writer(ManifestNodes.Element root) throws IOException {
            this.root = root;
            collect(root, 1);
            // Keep Android attribute slots distinct from equal element names or string values.
            for (Map.Entry<Integer,String> e : resourceNames.entrySet()) {
                attrs.put(e.getKey() + "|" + e.getValue(), strings.size()); strings.add(e.getValue());
            }
            intern("android"); intern(ManifestPlan.ANDROID); gather(root);
            check(strings.size() <= MAX_STRINGS, "Manifest string limit exceeded");
        }
        void collect(ManifestNodes.Element node, int depth) throws IOException {
            check(node != null && depth <= MAX_DEPTH && ++nodes <= MAX_NODES, "Manifest tree limit exceeded");
            check(node.attributes.size() <= 32 && (attributeCount += node.attributes.size()) <= MAX_ATTRIBUTES, "Manifest attribute limit exceeded");
            for (ManifestPlan.Attribute a : node.attributes) if (a.resourceId != 0) {
                check(a.namespace.equals(ManifestPlan.ANDROID), "Invalid attribute namespace");
                String previous = resourceNames.put(a.resourceId, a.name);
                check(previous == null || previous.equals(a.name), "Conflicting Android resource ID");
            }
            for (ManifestNodes.Element child : node.children) collect(child, depth + 1);
        }
        void gather(ManifestNodes.Element node) throws IOException {
            intern(node.name);
            for (ManifestPlan.Attribute a : node.attributes) {
                if (a.resourceId == 0) intern(a.name);
                if (a.type == 3) intern((String)a.value);
            }
            for (ManifestNodes.Element child : node.children) gather(child);
        }
        int intern(String value) throws IOException {
            Integer old = text.get(value); if (old != null) return old;
            check(value != null && value.length() <= 1024 && strings.size() < MAX_STRINGS, "Manifest string limit exceeded");
            int index = strings.size(); strings.add(value); text.put(value, index); return index;
        }
        int string(String value) { return text.get(value); }
        byte[] write() throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream(); bytes(body, pool());
            header(body, 0x180, 8, 8 + 4 * resourceNames.size()); for (int id : resourceNames.keySet()) i32(body, id);
            namespace(body, 0x100); node(body, root); namespace(body, 0x101);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); header(out, 3, 8, 8 + body.size()); bytes(out, body.toByteArray());
            check(out.size() <= 1024 * 1024, "Manifest byte limit exceeded"); return out.toByteArray();
        }
        byte[] pool() throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream(), offsets = new ByteArrayOutputStream();
            for (String value : strings) {
                i32(offsets, data.size()); byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                length(data, value.length()); length(data, encoded.length); bytes(data, encoded); data.write(0);
            }
            while ((data.size() & 3) != 0) data.write(0);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); header(out, 1, 28, 28 + offsets.size() + data.size());
            i32(out, strings.size()); i32(out, 0); i32(out, 256); i32(out, 28 + offsets.size()); i32(out, 0);
            bytes(out, offsets.toByteArray()); bytes(out, data.toByteArray()); return out.toByteArray();
        }
        void namespace(ByteArrayOutputStream out, int kind) {
            xmlHeader(out, kind, 24); i32(out, string("android")); i32(out, string(ManifestPlan.ANDROID));
        }
        void node(ByteArrayOutputStream out, ManifestNodes.Element node) {
            xmlHeader(out, 0x102, 36 + 20 * node.attributes.size()); i32(out, -1); i32(out, string(node.name));
            i16(out, 20); i16(out, 20); i16(out, node.attributes.size()); i16(out, 0); i16(out, 0); i16(out, 0);
            for (ManifestPlan.Attribute a : node.attributes) {
                i32(out, a.resourceId == 0 ? -1 : string(ManifestPlan.ANDROID));
                i32(out, a.resourceId == 0 ? string(a.name) : attrs.get(a.resourceId + "|" + a.name));
                int value = a.type == 3 ? string((String)a.value) : (Integer)a.value;
                i32(out, a.type == 3 ? value : -1); i16(out, 8); out.write(0); out.write(a.type); i32(out, value);
            }
            for (ManifestNodes.Element child : node.children) node(out, child);
            xmlHeader(out, 0x103, 24); i32(out, -1); i32(out, string(node.name));
        }
    }
    private static void check(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    private static void length(ByteArrayOutputStream out, int value) throws IOException {
        check(value <= 32767, "Manifest string too long"); if (value >= 128) out.write((value >>> 8) | 128); out.write(value);
    }
    private static void xmlHeader(ByteArrayOutputStream out, int kind, int size) { header(out, kind, 16, size); i32(out, 0); i32(out, -1); }
    private static void header(ByteArrayOutputStream out, int kind, int header, int size) { i16(out, kind); i16(out, header); i32(out, size); }
    private static void i16(ByteArrayOutputStream out, int value) { out.write(value); out.write(value >>> 8); }
    private static void i32(ByteArrayOutputStream out, int value) { i16(out, value); i16(out, value >>> 16); }
    private static void bytes(ByteArrayOutputStream out, byte[] value) { out.write(value, 0, value.length); }
}
