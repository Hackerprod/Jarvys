package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ConnectorArtifactAccess
import org.json.JSONObject

/** An immutable-by-ownership copy, captured before the write approval is shown. */
data class GoogleWorkspaceArtifact(val bytes: ByteArray, val name: String, val mime: String, val sha256: String)

interface GoogleWorkspaceArtifactSink {
    fun publish(bytes: ByteArray, name: String, mime: String, token: CancellationToken): JSONObject
    fun read(artifactId: String, token: CancellationToken): GoogleWorkspaceArtifact
}

/** Uses the current CoreConnectorTool invocation's chat; it never accepts a session or path from the model. */
object GoogleWorkspaceArtifacts : GoogleWorkspaceArtifactSink {
    override fun publish(bytes: ByteArray, name: String, mime: String, token: CancellationToken): JSONObject =
        ConnectorArtifactAccess.publish(bytes, name, mime, token)
    override fun read(artifactId: String, token: CancellationToken): GoogleWorkspaceArtifact {
        val snapshot = ConnectorArtifactAccess.read(artifactId, token)
        return GoogleWorkspaceArtifact(snapshot.bytes, snapshot.name, snapshot.mime, snapshot.sha256)
    }
}
