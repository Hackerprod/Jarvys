package com.jarvys.agent.connectors

import android.content.Context

/** Play adds no flavor-only device connectors; common definitions are registered by ConnectorRegistry. */
object FlavorDeviceConnectorDefinitions {
    fun definitions(@Suppress("UNUSED_PARAMETER") context: Context): List<ConnectorDefinition> =
        emptyList()
}
