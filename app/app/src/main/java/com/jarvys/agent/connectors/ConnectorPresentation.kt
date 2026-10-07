package com.jarvys.agent.connectors

/** Small immutable list model; deliberately has no fields for endpoint, risk notes, or body text. */
data class ConnectorRowPresentation(
    val name: String,
    val status: ConnectorShortStatus?,
    val hasChevron: Boolean = true,
)

enum class ConnectorShortStatus { CONNECTED, CONNECTING, REAUTHORIZE, ERROR, PERMISSION_PENDING }

enum class ConnectorPrimaryAction { CONNECT, REAUTHORIZE, NONE }

data class ConnectorDetailPresentation(
    val name: String,
    val subtitle: String,
    val summary: String,
    val connected: Boolean,
    val primaryAction: ConnectorPrimaryAction,
    val canDisconnect: Boolean,
    val advancedCollapsedByDefault: Boolean = true,
)

object ConnectorPresentation {
    fun row(name: String, status: ConnectorShortStatus?): ConnectorRowPresentation =
        ConnectorRowPresentation(name = name, status = status)

    fun detail(
        name: String,
        subtitle: String,
        summary: String,
        connected: Boolean,
        reauthorize: Boolean = false,
    ) = ConnectorDetailPresentation(
        name = name,
        subtitle = subtitle,
        summary = summary,
        connected = connected,
        primaryAction = when {
            reauthorize -> ConnectorPrimaryAction.REAUTHORIZE
            connected -> ConnectorPrimaryAction.NONE
            else -> ConnectorPrimaryAction.CONNECT
        },
        canDisconnect = connected,
    )
}
