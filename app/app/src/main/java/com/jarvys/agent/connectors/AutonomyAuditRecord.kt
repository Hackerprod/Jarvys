package com.jarvys.agent.connectors

import java.util.UUID

/** Local audit facts; never authorization to execute an operation. */
data class AutonomyAuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    val connectorId: String,
    val connectorName: String,
    val operationName: String,
    val operationLabel: String,
    val summary: String,
)

