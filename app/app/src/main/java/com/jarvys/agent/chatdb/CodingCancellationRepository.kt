package com.jarvys.agent.chatdb

import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.coding.CodingCancellation
import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobEvidenceCodec
import org.json.JSONObject

/** Deterministic technical admission/result. No semantic toggle, command dispatch or receipt from JSON. */
internal class CodingCancellationRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql
    suspend fun admit(request: CodingCancellation.Request, admission: EffectInvocation.Admission) = database.withChatWriteTransaction {
        request.requireCaptureCurrent()
        request.requireDispatchLive()
        require(admission.authorization == EffectInvocation.Authorization.ALLOW || admission.authorization == EffectInvocation.Authorization.ASK_APPROVED)
        require(admission.connectionObservation == null) { "Coding cancellation cannot borrow connector authority" }
        verifyTarget(request)
        val existed=exists(request.requestId())
        val args = intentArguments(request, admission).dropLast(1).toTypedArray()
        sql.execSQL("INSERT OR IGNORE INTO coding_cancel_requests(request_id,source_key,technical_generation,target_job_id,source_json,target_binding_json,admission_json,result_json,record_kind) VALUES(?,?,?,?,?,?,?,NULL,'ADMITTED_INTENT')", args)
        requireIntent(request, admission)
        if(!existed) CodingCancellationSemanticRepository(database).capture(request.requestId(),request.reservation().binding(),
            request.semanticSource(),request.semanticTicket().enabledAtCapture())
    }

    suspend fun finish(result: CodingCancellation.Result) = database.withChatWriteTransaction {
        val request = result.request()
        val intent = result.intentCheckpoint()
        intent.requireIntent(request)
        result.requireIssuer()
        verifyTarget(request)
        requireIntent(request, intent.admission())
        val encoded = resultJson(result)
        val old = sql.query("SELECT result_json FROM coding_cancel_requests WHERE request_id=?", arrayOf(request.requestId())).use { c ->
            check(c.moveToFirst()); if (c.isNull(0)) null else c.getString(0)
        }
        if (old != null) {
            check(old == encoded) { "Cancellation result identity conflict" }
            return@withChatWriteTransaction
        }
        sql.execSQL("UPDATE coding_cancel_requests SET result_json=? WHERE request_id=? AND result_json IS NULL", arrayOf(encoded, request.requestId()))
        sql.query("SELECT result_json FROM coding_cancel_requests WHERE request_id=?", arrayOf(request.requestId())).use { c ->
            check(c.moveToFirst() && !c.isNull(0) && c.getString(0) == encoded) { "Cancellation result CAS did not persist" }
        }
    }

    /** No admission is manufactured: a complete observation has no pending INTENT state. */
    suspend fun observe(observation: CodingCancellation.Observation) = database.withChatWriteTransaction {
        observation.requireIssuer()
        observation.requireCaptureCurrent()
        val binding=observation.reservation().binding()
        verifyTarget(binding)
        val existed=exists(observation.observationId())
        val expected=observationArguments(observation)
        sql.execSQL("INSERT OR IGNORE INTO coding_cancel_requests(request_id,source_key,technical_generation,target_job_id,source_json,target_binding_json,admission_json,result_json,record_kind) VALUES(?,?,?,?,?,?,?,?,?)",expected)
        requireObserved(observation)
        if(!existed) CodingCancellationSemanticRepository(database).capture(observation.observationId(),binding,
            observation.semanticSource(),observation.semanticTicket().enabledAtCapture())
    }

    private fun exists(id:String)=sql.query("SELECT 1 FROM coding_cancel_requests WHERE request_id=?",arrayOf(id)).use {it.moveToFirst()}
    /** Read-only revalidation for an already-issued checkpoint; never fills a missing result. */
    fun requireCompleted(result:CodingCancellation.Result) {
        result.requireIssuer();verifyTarget(result.request());requireIntent(result.request(),result.intentCheckpoint().admission())
        sql.query("SELECT result_json FROM coding_cancel_requests WHERE request_id=?",arrayOf(result.request().requestId())).use {c ->
            check(c.moveToFirst() && !c.isNull(0) && c.getString(0)==resultJson(result)){"Exact cancellation result no longer present"}
        }
    }
    fun requireObserved(observation:CodingCancellation.Observation) {
        observation.requireIssuer();verifyTarget(observation.reservation().binding())
        val expected=observationArguments(observation)
        sql.query("SELECT request_id,source_key,technical_generation,target_job_id,source_json,target_binding_json,admission_json,result_json,record_kind FROM coding_cancel_requests WHERE request_id=?",arrayOf(observation.observationId())).use {c ->
            check(c.moveToFirst() && expected.indices.all {i -> (if(c.isNull(i))null else c.getString(i))==expected[i]}) {
                "Immutable no-admission observation conflict"
            }
        }
    }
    private fun observationArguments(observation:CodingCancellation.Observation):Array<String?> {
        val binding=observation.reservation().binding()
        val result=JSONObject().put("schema",1).put("disposition",observation.disposition().name)
            .put("completion_semantics","existing_reference").put("outcome","not_executed")
            .put("goal_verified",false).put("observed_state",observation.observedState().name)
            .put("observed_exit_code",observation.observedExitCode() ?: JSONObject.NULL).toString()
        return arrayOf(observation.observationId(),observation.sourceKey(),binding.technicalStoreGeneration,
            binding.jobId,observation.sourceJson(),bindingJson(binding),null,result,"NO_ADMISSION_OBSERVATION")
    }

    private fun verifyTarget(request: CodingCancellation.Request) = verifyTarget(request.reservation().binding())
    private fun verifyTarget(binding: CodingJobEvidence.Binding) {
        val generation = sql.query("SELECT value FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        check(generation == binding.technicalStoreGeneration) { "Cancellation destination changed" }
        sql.query("SELECT submission_id,technical_generation,binding_json FROM coding_job_bindings WHERE job_id=?", arrayOf(binding.jobId)).use { c ->
            check(c.moveToFirst() && c.getString(0) == binding.submissionId && c.getString(1) == generation && c.getString(2) == bindingJson(binding)) {
                "Exact existing cancellation target binding is required"
            }
        }
    }
    private fun requireIntent(request: CodingCancellation.Request, admission: EffectInvocation.Admission) {
        val expected = intentArguments(request, admission)
        sql.query("SELECT request_id,source_key,technical_generation,target_job_id,source_json,target_binding_json,admission_json,record_kind FROM coding_cancel_requests WHERE request_id=?", arrayOf(request.requestId())).use { c ->
            check(c.moveToFirst() && expected.indices.all { c.getString(it) == expected[it] }) { "Immutable cancellation intent conflict" }
        }
    }
    private fun intentArguments(request: CodingCancellation.Request, admission: EffectInvocation.Admission): Array<String> {
        val binding = request.reservation().binding()
        return arrayOf(request.requestId(), request.sourceKey(), binding.technicalStoreGeneration, binding.jobId,
            request.sourceJson(), bindingJson(binding), JSONObject().put("schema", 1).put("authorization", admission.authorization.name)
                .put("authority", admission.authority ?: JSONObject.NULL).put("action", admission.actionId ?: JSONObject.NULL)
                .put("revision", admission.revision ?: JSONObject.NULL).put("origin", admission.origin ?: JSONObject.NULL).toString(), "ADMITTED_INTENT")
    }
    private fun resultJson(result: CodingCancellation.Result): String {
        val commit = result.cancellingCommit()
        val confirmed = result.disposition() == CodingCancellation.Disposition.NEW_REQUEST && commit != null
        return JSONObject().put("schema", 1).put("disposition", result.disposition().name)
            .put("completion_semantics", if (confirmed) "cancel_request_accepted" else "existing_reference")
            .put("outcome", if (confirmed) "ok" else "uncertain").put("goal_verified", false)
            .put("cancelling_revision", commit?.record()?.revision ?: JSONObject.NULL)
            .put("cancelling_payload_sha256", commit?.payloadSha256() ?: JSONObject.NULL).toString()
    }
    private fun bindingJson(binding: CodingJobEvidence.Binding) = CodingJobEvidenceCodec.encode(
        CodingJobEvidence.Record(binding, 1, CodingJobEvidence.Phase.SUBMISSION_INTENT, false, null, null))
}
