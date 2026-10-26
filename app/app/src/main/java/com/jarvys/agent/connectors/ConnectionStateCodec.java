package com.jarvys.agent.connectors;

import static com.jarvys.agent.connectors.ConnectionStateCoordinator.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Exact, versioned state records. No Java object serialization or tolerant JSON parsing. */
final class ConnectionStateCodec {
    private static final int FORMAT = 1;
    private static final int EVENT = 1, MUTATION = 2;
    private ConnectionStateCodec() {}

    static byte[] event(Event event) { return encode(EVENT, out -> writeEvent(out, event)); }
    static Event event(byte[] encoded) { return decode(encoded, EVENT, ConnectionStateCodec::readEvent); }
    static byte[] mutation(Mutation mutation) {
        return encode(MUTATION, out -> {
            writeEvent(out, mutation.event);
            string(out, mutation.version);
            Command c = mutation.command;
            string(out, c.connectorId); string(out, c.definitionGeneration); string(out, c.operationId);
            string(out, c.kind.name()); out.writeLong(c.expectedRevision); out.writeLong(c.expectedIntentEpoch);
            nullableString(out, c.parentOperationId);
            out.writeLong(mutation.revision); out.writeLong(mutation.highWater);
            string(out, mutation.phase.name()); bool(out, mutation.selected); bool(out, mutation.unchanged);
            string(out, mutation.origin.name());
            nullableString(out, mutation.bootstrapRecordId);
            nullableString(out, mutation.activationOwnerProcessId);
        });
    }
    static Mutation mutation(byte[] encoded) {
        return decode(encoded, MUTATION, in -> {
            Event e = readEvent(in);
            String version = string(in);
            Command c = new Command(string(in), string(in), string(in), Kind.valueOf(string(in)),
                    in.readLong(), in.readLong(), nullableString(in));
            long revision = in.readLong(), highWater = in.readLong();
            Phase phase = Phase.valueOf(string(in));
            boolean selected = bool(in), unchanged = bool(in);
            Origin origin = Origin.valueOf(string(in));
            return new Mutation(e, version, c, revision, highWater, phase, selected, unchanged,
                    origin, nullableString(in), nullableString(in));
        });
    }
    private static void writeEvent(DataOutputStream out, Event e) throws IOException {
        string(out, e.storeEpoch); out.writeLong(e.sequence); string(out, e.connectorId);
        out.writeByte(e.previous == null ? 0 : (e.previous ? 2 : 1));
        bool(out, e.selected); string(out, e.reason); string(out, e.operationId);
        nullableString(out, e.parentOperationId); out.writeLong(e.observedAtMs);
    }
    private static Event readEvent(DataInputStream in) throws IOException {
        String epoch = string(in); long sequence = in.readLong(); String id = string(in);
        int previous = in.readUnsignedByte();
        if (previous > 2) throw new IOException("Invalid optional boolean");
        return new Event(epoch, sequence, id, previous == 0 ? null : previous == 2, bool(in),
                string(in), string(in), nullableString(in), in.readLong());
    }
    private interface Encoder { void write(DataOutputStream out) throws IOException; }
    private interface Decoder<T> { T read(DataInputStream in) throws IOException; }
    private static byte[] encode(int kind, Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FORMAT); out.writeByte(kind); encoder.write(out); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Connection state encoding failed"); }
    }
    private static <T> T decode(byte[] bytes, int kind, Decoder<T> decoder) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != FORMAT || in.readUnsignedByte() != kind) throw new IOException("Unsupported record format");
            T decoded = decoder.read(in);
            if (in.available() != 0) throw new IOException("Trailing state bytes");
            return decoded;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid connection state record");
        }
    }
    private static void bool(DataOutputStream out, boolean value) throws IOException { out.writeByte(value ? 1 : 0); }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("Invalid boolean");
        return value == 1;
    }
    private static void nullableString(DataOutputStream out, String value) throws IOException {
        bool(out, value != null); if (value != null) string(out, value);
    }
    private static String nullableString(DataInputStream in) throws IOException { return bool(in) ? string(in) : null; }
    private static void string(DataOutputStream out, String value) throws IOException {
        final ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
        } catch (CharacterCodingException failure) { throw new IOException("Invalid Unicode"); }
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("Invalid field length");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw new IOException("Invalid UTF-8"); }
    }
}
