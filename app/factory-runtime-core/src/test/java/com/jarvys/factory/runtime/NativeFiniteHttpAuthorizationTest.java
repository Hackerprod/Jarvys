package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local operation binding for finite HTTP jobs; no host lease is acquired for these jobs. */
public final class NativeFiniteHttpAuthorizationTest {
    private static String hash(char value) { return new String(new char[64]).replace('\0', value); }

    private NativeOperationRequest job(String execution, boolean replay, int timeout) throws Exception {
        JSONObject request = NetworkRequest.fromRequest(new JSONObject()
                .put("url", "https://fake.invalid/job").put("timeoutMs", timeout)).authorizationDetails();
        JSONObject details = new JSONObject().put("jobId", "12345678-1234-1234-1234-123456789abc")
                .put("request", request)
                .put("constraints", new JSONObject().put("network", "any").put("requiresCharging", false).put("minimumLatencyMs", 0L))
                .put("executionMode", execution).put("replayPossible", replay);
        return NativeOperationRequest.parse("jobs.enqueueHttp", details.toString());
    }

    @Test public void scheduledJobsCannotClaimReplayOrUnboundedTime() throws Exception {
        assertThrows(FactoryException.class, () -> job("scheduled", true, 30000));
        assertThrows(FactoryException.class, () -> job("scheduled", false, 120001));
        assertThrows(FactoryException.class, () -> job("scheduled", false, 0));
        NativeOperationRequest accepted = job("scheduled", false, 120000);
        assertEquals("scheduled", accepted.details().getString("executionMode"));
        assertFalse(accepted.details().getBoolean("replayPossible"));
    }

    @Test public void allScheduledJobFieldsAreBoundAndUnknownFieldsFailClosed() throws Exception {
        NativeOperationRequest operation = job("scheduled", false, 120000);
        NativeBackgroundAuthorization.SourceIdentity source = new NativeBackgroundAuthorization.SourceIdentity(
                "org.example.generated", hash('a'), hash('b'), hash('c'), 42L);
        String nonce = hash('f'), binding = operation.binding(nonce, source.bindingEvidence());
        JSONObject details = operation.details();
        for (String field : new String[]{"jobId", "executionMode", "replayPossible"}) {
            JSONObject changed = new JSONObject(details.toString());
            if (field.equals("jobId")) changed.put(field, "12345678-1234-1234-1234-123456789abd");
            else {
                changed.put("executionMode", "foreground_now");
                changed.put("replayPossible", field.equals("replayPossible"));
            }
            assertNotEquals(binding, NativeOperationRequest.parse("jobs.enqueueHttp", changed.toString())
                    .binding(nonce, source.bindingEvidence()));
        }
        for (String section : new String[]{"request", "constraints"}) {
            JSONObject changed = new JSONObject(details.toString());
            changed.getJSONObject(section).put("unknown", true);
            assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("jobs.enqueueHttp", changed.toString()));
        }
        JSONObject changed = new JSONObject(details.toString()).put("leaseKind", "finite_http_job");
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("jobs.enqueueHttp", changed.toString()));
    }
}
