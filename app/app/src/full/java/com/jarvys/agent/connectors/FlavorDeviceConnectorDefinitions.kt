package com.jarvys.agent.connectors

import android.content.Context

/** Direct-install-only capabilities whose manifests contain restricted SMS/Call Log permissions. */
object FlavorDeviceConnectorDefinitions {
    fun definitions(context: Context): List<ConnectorDefinition> = listOf(
        FullSmsConnector.definition(context),
        CallLogConnector.definition(context),
        GmailConnector.definition(context),
        DriveConnector.definition(context),
    )
}
