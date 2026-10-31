package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Parsing only: no Android permission or microphone access. */
public class RecordingRequestTest {
    @Test public void defaultsAreFiniteAndDescribeTheSameAuthorizedRequest() throws Exception {
        RecordingRequest.Record request = RecordingRequest.parseRecord("{}");
        assertEquals(10000, request.durationMs); assertEquals(2L * 1024 * 1024, request.maxBytes);
        assertEquals(16000, request.sampleRate); assertEquals(1, request.channels);
        assertEquals("wav-pcm16", request.format); assertEquals(320044, request.maximumFileBytes());
        RecordingRequest.Record roundTrip = RecordingRequest.parseRecord(request.authorizationDetails());
        assertEquals(request.authorizationDetails().toString(), roundTrip.authorizationDetails().toString());
    }
    @Test public void byteAndSampleBoundsRoundDownToCompleteFrames() throws Exception {
        RecordingRequest.Record request = RecordingRequest.parseRecord(
                "{\"durationMs\":101,\"sampleRate\":44100,\"channels\":2,\"maxBytes\":51}");
        assertEquals(48, request.maximumFileBytes());
        assertEquals(51, request.maxBytes); // Never silently changes the authorized request.
        assertEquals(17860, RecordingRequest.parseRecord(
                "{\"durationMs\":101,\"sampleRate\":44100,\"channels\":2}").maximumFileBytes());
    }
    @Test public void explicitLargeBoundsDoNotOverflowWavArithmetic() throws Exception {
        RecordingRequest.Record request = RecordingRequest.parseRecord(new JSONObject()
                .put("durationMs", RecordingRequest.MAX_DURATION_MS).put("maxBytes", RecordingRequest.MAX_BYTES)
                .put("sampleRate", 48000).put("channels", 2));
        assertEquals(RecordingRequest.MAX_BYTES, request.maximumFileBytes());
        assertTrue(request.maximumFileBytes() < 0xffffffffL);
    }
    @Test public void unknownFieldsAndArbitraryOutputLocationsAreRejected() {
        for (String field : new String[]{"path", "uri", "output", "source", "background", "engine", "bitRate", "handle"}) {
            FactoryException failure = assertThrows(FactoryException.class,
                    () -> RecordingRequest.parseRecord(new JSONObject().put(field, "anything")));
            assertEquals("INVALID_ARGUMENT", failure.code);
        }
    }
    @Test public void numbersMustBeIntegersInRangeWithNoCoercion() {
        for (String json : new String[]{
                "{\"durationMs\":99}", "{\"durationMs\":3600001}", "{\"durationMs\":1000.0}",
                "{\"durationMs\":\"1000\"}", "{\"durationMs\":true}", "{\"durationMs\":null}",
                "{\"maxBytes\":47}", "{\"maxBytes\":536870913}", "{\"maxBytes\":-1}",
                "{\"sampleRate\":7999}", "{\"sampleRate\":48001}", "{\"sampleRate\":16000.5}",
                "{\"channels\":0}", "{\"channels\":3}", "{\"format\":\"aac\"}", "{\"format\":null}"}) {
            assertThrows(json, FactoryException.class, () -> RecordingRequest.parseRecord(json));
        }
        FactoryException field = assertThrows(FactoryException.class,
                () -> RecordingRequest.parseRecord("{\"durationMs\":99}"));
        assertEquals("recording.record: durationMs must be an integer from 100 through 3600000.", field.getMessage());
    }
    @Test public void strictJsonRejectsDuplicatesAndMalformedPayloads() {
        for (String json : new String[]{"[]", "{'durationMs':1000}", "{durationMs:1000}",
                "{\"durationMs\":1000,\"durationMs\":2000}", "{} trailing", "{\"maxBytes\":NaN}"})
            assertThrows(json, FactoryException.class, () -> RecordingRequest.parseRecord(json));
    }
    @Test public void inputEnvelopeIsBoundedAndStatusStopAcceptNoOptions() throws Exception {
        assertEquals("TOO_LARGE", assertThrows(FactoryException.class,
                () -> RecordingRequest.parseRecord(new JSONObject().put("x", new String(new char[2000])))).code);
        assertThrows(FactoryException.class, () -> RecordingRequest.parseRecord((JSONObject) null));
        RecordingRequest.parseEmpty("{}");
        assertThrows(FactoryException.class, () -> RecordingRequest.parseEmpty("{\"all\":true}"));
    }
}
