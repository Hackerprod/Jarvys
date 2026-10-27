package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodingEpisodePayloadTest {
    private fun row(job: String, submission: Boolean, size: Int = 0) = JSONObject()
        .put("kind", if (submission) "coding_job_submission" else "coding_job_execution")
        .put("ref_id", job).put("tool", "project_exec").put("connector", JSONObject.NULL)
        .put("outcome", if (submission) JSONObject.NULL else "ok")
        .put("authorization", "allow").put("at_ms", 42)
        .put("facts", JSONObject().put("job_id", job).put("goal_verified", false)
            .put("historical_root_is_exclusive_cause", false).put("detail", "x".repeat(size)))

    @Test fun submissionAndExecutionCountAsOneJobNotTwoCommands() {
        val rows = JSONArray().put(row("job-a", true)).put(row("job-a", false))
        val body = JSONObject(EpisodeNarrationPayload.render("Synthetic request", "", 42, rows, 4096))
        assertEquals(1, body.getInt("coding_job_count"))
        assertFalse(body.getBoolean("coding_goal_verified"))
        assertTrue(body.getJSONArray("evidence").getJSONObject(0).isNull("outcome"))
        assertEquals("ok", body.getJSONArray("evidence").getJSONObject(1).getString("outcome"))
    }

    @Test fun boundedAggregationPreservesRolesUniqueJobsAndNoGoalVerification() {
        val rows = JSONArray().put(row("job-a", true, 3000)).put(row("job-a", false, 3000))
            .put(row("job-a", false, 3000)).put(row("job-b", false, 3000))
        val rendered = EpisodeNarrationPayload.render("Synthetic request", "", 42, rows, 2200)
        assertTrue(rendered.length <= 2200)
        val body = JSONObject(rendered)
        assertTrue(body.getBoolean("evidence_aggregated"))
        assertEquals(2, body.getInt("coding_job_count"))
        val evidence = body.getJSONArray("evidence")
        val submission = evidence.getJSONObject(0)
        val execution = evidence.getJSONObject(1)
        assertEquals("async_submission", submission.getString("completion_semantics"))
        assertTrue(submission.isNull("outcome"))
        assertEquals(1, submission.getInt("job_count"))
        assertEquals("durable_process_terminal", execution.getString("completion_semantics"))
        assertEquals(2, execution.getInt("job_count"))
        assertEquals(3, execution.getInt("evidence_count"))
        assertFalse(execution.getBoolean("goal_verified"))
        assertFalse(execution.getBoolean("historical_root_is_exclusive_cause"))
    }

    @Test fun suppressedJobsRemainExplicitEvenWhenOnlyOtherEvidenceIsVisible() {
        val other = JSONObject().put("kind", "tool_call").put("tool", "synthetic_local_write")
            .put("outcome", "ok").put("at_ms", 42).put("facts", JSONObject())
        val body = JSONObject(EpisodeNarrationPayload.render("Synthetic request", "", 42,
            JSONArray().put(other), 1600, suppressedCodingJobs = 2))
        assertEquals(2, body.getInt("coding_jobs_suppressed"))
        assertTrue(body.getString("coding_coverage").contains("cannot establish overall goal completion"))
        assertFalse(body.has("coding_job_count"))
    }

    @Test fun missingJobIdentityCannotBecomeAnAffectedItemCount() {
        val invalid = row("", false)
        assertThrows(IllegalStateException::class.java) {
            EpisodeNarrationPayload.render("", "", 42, JSONArray().put(invalid), 4096)
        }
    }
}
