package com.jarvys.factory.contract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only AXML decoder. Deliberately shares no parsing or rewriting helpers with TemplateApk. */
public final class ManifestAudit {
    private ManifestAudit() {}
    public static final class Attribute {
        public final String namespace, name;
        public final int resourceId, type;
        public final Object value;
        private Attribute(String ns, String name, int id, int type, Object value) {
            namespace = ns; this.name = name; resourceId = id; this.type = type; this.value = value;
        }
    }
    public static final class Node {
        public final String path;
        public final int parentIndex;
        public final Map<String, Attribute> attributes;
        private Node(String path, int parentIndex, Map<String, Attribute> attributes) {
            this.path = path; this.parentIndex = parentIndex; this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }
    public static final class Document {
        public final List<Node> nodes;
        private Document(List<Node> nodes) { this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes)); }
        public Attribute attribute(String path, String namespace, String name) throws IOException {
            Attribute found = null;
            int matches = 0;
            for (Node node : nodes) if (node.path.equals(path)) {
                require(++matches == 1, "Ambiguous audited manifest path " + path);
                Attribute value = node.attributes.get(namespace + "|" + name);
                if (value != null) found = value;
            }
            if (found != null) return found;
            throw new IOException("Missing audited manifest attribute " + name);
        }
        public void verify(ManifestPlan plan) throws IOException {
            require(plan != null && nodes.size() == plan.nodes.size(), "Manifest node set differs from plan");
            for (int i = 0; i < nodes.size(); i++) {
                Node actual = nodes.get(i); ManifestPlan.Node expected = plan.nodes.get(i);
                require(actual.path.equals(expected.path) && actual.parentIndex == expected.parentIndex && actual.attributes.keySet().equals(expected.attributes.keySet()),
                    "Manifest tree or attribute set differs from plan: " + actual.path);
                for (Map.Entry<String, ManifestPlan.Attribute> entry : expected.attributes.entrySet()) {
                    Attribute value = actual.attributes.get(entry.getKey()); ManifestPlan.Attribute wanted = entry.getValue();
                    require(value.resourceId == wanted.resourceId && value.type == wanted.type && value.value.equals(wanted.value),
                        "Manifest typed value differs from plan: " + actual.path + "/" + wanted.name);
                }
            }
        }
    }
    /** Construction evidence only. Production authorization requires verify(ManifestPlan). */
    public static void verifyTree(byte[] bytes, ManifestNodes.Element expected) throws IOException {
        Document document = read(bytes); int[] cursor = {0};
        verifyTreeNode(document, expected, "", -1, cursor);
        require(cursor[0] == document.nodes.size(), "Unexpected constructed manifest nodes");
    }
    private static void verifyTreeNode(Document doc, ManifestNodes.Element wanted, String parentPath, int parent, int[] cursor) throws IOException {
        require(wanted != null && cursor[0] < doc.nodes.size(), "Missing constructed manifest node");
        int index = cursor[0]++; Node actual = doc.nodes.get(index);
        String path = parentPath.isEmpty() ? wanted.name : parentPath + "/" + wanted.name;
        require(actual.parentIndex == parent && actual.path.equals(path) && actual.attributes.size() == wanted.attributes.size(), "Constructed manifest tree differs");
        for (ManifestPlan.Attribute attr : wanted.attributes) {
            Attribute value = actual.attributes.get(attr.key());
            require(value != null && value.resourceId == attr.resourceId && value.type == attr.type && value.value.equals(attr.value), "Constructed manifest attribute differs");
        }
        for (ManifestNodes.Element child : wanted.children) verifyTreeNode(doc, child, path, index, cursor);
    }
    public static Document read(byte[] source) throws IOException { return new Reader(source,false).read(); }
    public static void verifyBackupRules(byte[] source) throws IOException {
        Document rules = new Reader(source,true).read();
        String[] domains = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"};
        require(rules.nodes.size() == 21, "Backup exclusion set differs");
        require(rules.nodes.get(0).path.equals("data-extraction-rules") && rules.nodes.get(0).attributes.isEmpty(), "Invalid backup root");
        int at = 1;
        for (String group : new String[]{"cloud-backup", "device-transfer"}) {
            String path = "data-extraction-rules/" + group;
            Node node = rules.nodes.get(at++);
            require(node.path.equals(path) && node.attributes.isEmpty(), "Invalid backup group");
            for (String domain : domains) {
                Node exclude = rules.nodes.get(at++);
                require(exclude.path.equals(path + "/exclude") && exclude.attributes.size() == 2, "Invalid backup exclusion");
                for (String name : new String[]{"domain", "path"}) {
                    Attribute attr = exclude.attributes.get("|" + name);
                    require(attr != null && attr.type == 3 && attr.resourceId == 0 && attr.value.equals(name.equals("domain") ? domain : "."),
                        "Backup domain/path differs");
                }
            }
        }
    }
    private static void require(boolean ok, String message) throws IOException { if (!ok) throw new IOException(message); }
    private static final class Reader {
        private final ByteBuffer b;
        private final boolean backup;
        private String[] strings;
        private int[] resources;
        private final List<String> stack = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();
        private final List<Node> nodes = new ArrayList<>();
        private boolean namespace, endedNamespace, closed;
        private int attributeCount;
        Reader(byte[] source, boolean backup) throws IOException {
            this.backup = backup;
            require(source != null && source.length >= 8 && source.length <= 1024 * 1024, "Invalid audited XML length");
            b = ByteBuffer.wrap(source).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }
        int u16(int at) throws IOException { limit(at, 2, b.limit()); return b.getShort(at) & 65535; }
        int i32(int at) throws IOException { limit(at, 4, b.limit()); return b.getInt(at); }
        int nonnegative(int at) throws IOException { int value = i32(at); require(value >= 0, "Oversized audited integer"); return value; }
        void limit(int at, int size, int end) throws IOException { require(at >= 0 && size >= 0 && end <= b.limit() && at <= end - size, "Truncated audited XML"); }
        String string(int index) throws IOException { require(strings != null && index >= 0 && index < strings.length, "Invalid audited string index"); return strings[index]; }
        Document read() throws IOException {
            require(u16(0) == 3 && u16(2) == 8 && i32(4) == b.limit(), "Invalid audited XML header");
            for (int at = 8; at < b.limit();) {
                limit(at, 8, b.limit()); int kind = u16(at), header = u16(at + 2), size = nonnegative(at + 4);
                require(header >= 8 && size >= header && (size & 3) == 0, "Invalid audited chunk size"); limit(at, size, b.limit());
                int end = at + size;
                if (kind == 1) {
                    require(at == 8 && strings == null, "Misplaced audited string pool"); pool(at, end, header);
                } else if (kind == 0x180) {
                    require(strings != null && resources == null && !namespace && !endedNamespace && nodes.isEmpty() && header == 8,
                        "Misplaced audited resource map");
                    require((size - 8) / 4 <= strings.length, "Oversized audited resource map");
                    resources = new int[(size - 8) / 4];
                    for (int i = 0; i < resources.length; i++) resources[i] = i32(at + 8 + 4 * i);
                } else {
                    require(strings != null && (backup || resources != null) && header == 16 && size >= 24, "Invalid audited XML node");
                    require(i32(at + 12) == -1, "Unexpected audited XML comment");
                    if (kind == 0x100 || kind == 0x101) {
                        require(!backup && size == 24 && "android".equals(string(i32(at + 16))) && ManifestPlan.ANDROID.equals(string(i32(at + 20))),
                            "Unexpected audited namespace");
                        if (kind == 0x100) { require(!namespace && !endedNamespace && nodes.isEmpty(), "Misplaced namespace start"); namespace = true; }
                        else { require(namespace && closed && stack.isEmpty(), "Misplaced namespace end"); namespace = false; endedNamespace = true; }
                    } else {
                        require((backup || namespace) && !endedNamespace && i32(at + 16) == -1, "Unexpected element namespace");
                        String name = string(i32(at + 20));
                        require(name.matches("[a-z][a-z0-9-]*"), "Invalid audited element name");
                        if (kind == 0x103) {
                            require(size == 24 && !stack.isEmpty() && name.equals(stack.get(stack.size() - 1)), "Audited end tag mismatch");
                            stack.remove(stack.size() - 1); parents.remove(parents.size() - 1); if (stack.isEmpty()) closed = true;
                        } else {
                            require(kind == 0x102 && !closed && size >= 36 && stack.size() < 8 && nodes.size() < 128, "Unexpected audited element");
                            require(u16(at + 24) == 20 && u16(at + 26) == 20 && size == 36 + 20 * u16(at + 28), "Invalid audited attribute layout");
                            require(u16(at + 28) <= 32 && (attributeCount += u16(at + 28)) <= 512, "Audited attribute limit exceeded");
                            require(u16(at + 30) == 0 && u16(at + 32) == 0 && u16(at + 34) == 0, "Unexpected special attribute index");
                            stack.add(name); StringBuilder path = new StringBuilder();
                            for (String part : stack) { if (path.length() > 0) path.append('/'); path.append(part); }
                            int parentIndex = parents.isEmpty() ? -1 : parents.get(parents.size() - 1);
                            parents.add(nodes.size());
                            Map<String, Attribute> attrs = new LinkedHashMap<>();
                            for (int pos = at + 36; pos < end; pos += 20) {
                                int nsIndex = i32(pos), nameIndex = i32(pos + 4), raw = i32(pos + 8);
                                String ns = nsIndex == -1 ? "" : string(nsIndex), attrName = string(nameIndex);
                                require(ns.isEmpty() || ns.equals(ManifestPlan.ANDROID), "Unknown audited attribute namespace");
                                require(u16(pos + 12) == 8 && b.get(pos + 14) == 0, "Invalid audited typed value");
                                int type = b.get(pos + 15) & 255, data = i32(pos + 16);
                                require(type == 1 || type == 3 || type == 16 || type == 17 || type == 18, "Unsupported audited attribute type");
                                Object value = type == 3 ? string(data) : Integer.valueOf(data);
                                if (type == 3) require(raw == -1 || value.equals(string(raw)), "Audited raw/string mismatch");
                                else require(raw == -1, "Unexpected raw numeric/reference value");
                                if (type == 18) require(data == 0 || data == -1, "Noncanonical audited boolean");
                                int id = resources != null && nameIndex < resources.length ? resources[nameIndex] : 0;
                                require(ns.isEmpty() ? id == 0 : id != 0, "Audited attribute resource namespace mismatch");
                                Attribute valueObject = new Attribute(ns, attrName, id, type, value);
                                require(attrs.put(ns + "|" + attrName, valueObject) == null, "Duplicate audited attribute");
                            }
                            nodes.add(new Node(path.toString(), parentIndex, attrs));
                        }
                    }
                }
                at = end;
            }
            require(closed && (backup || endedNamespace) && !namespace && stack.isEmpty() && !nodes.isEmpty(), "Incomplete audited XML");
            return new Document(nodes);
        }
        void pool(int at, int end, int header) throws IOException {
            require(header == 28, "Invalid audited pool header");
            int count = nonnegative(at + 8), flags = i32(at + 16), start = nonnegative(at + 20);
            require(count > 0 && count <= 4096 && i32(at + 12) == 0 && i32(at + 24) == 0 && (flags & ~257) == 0 && start == 28 + count * 4,
                "Unsupported audited pool layout");
            limit(at, start, end); strings = new String[count]; boolean utf8 = (flags & 256) != 0;
            for (int i = 0; i < count; i++) {
                int offset = nonnegative(at + 28 + 4 * i);
                require(offset < end - at - start, "Invalid audited pool offset");
                int[] pos = {at + start + offset};
                int units = length(pos, utf8, end), byteCount = utf8 ? length(pos, true, end) : units * 2;
                limit(pos[0], byteCount + (utf8 ? 1 : 2), end);
                require(b.get(pos[0] + byteCount) == 0 && (utf8 || b.get(pos[0] + byteCount + 1) == 0), "Unterminated audited string");
                ByteBuffer part = b.duplicate(); part.position(pos[0]); part.limit(pos[0] + byteCount);
                try {
                    strings[i] = (utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(part).toString();
                } catch (CharacterCodingException error) { throw new IOException("Malformed audited string", error); }
                require(strings[i].length() == units, "Audited string length differs");
            }
        }
        int length(int[] pos, boolean utf8, int end) throws IOException {
            int value;
            if (utf8) {
                limit(pos[0], 1, end); value = b.get(pos[0]++) & 255;
                if ((value & 128) != 0) { limit(pos[0], 1, end); value = ((value & 127) << 8) | (b.get(pos[0]++) & 255); }
            } else {
                limit(pos[0], 2, end); value = u16(pos[0]); pos[0] += 2;
                if ((value & 32768) != 0) { limit(pos[0], 2, end); value = ((value & 32767) << 16) | u16(pos[0]); pos[0] += 2; }
            }
            require(value >= 0 && value <= 65535, "Oversized audited string"); return value;
        }
    }
}
