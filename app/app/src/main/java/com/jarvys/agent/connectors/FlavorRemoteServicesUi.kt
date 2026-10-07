package com.jarvys.agent.connectors

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Flavor-only service panels use a generic contract so Play has no provider API/client references. */
interface FlavorRemoteServicesPanel {
    @Composable fun Content(context: Context, selectedId: String?, onSelect: (String, String) -> Unit,
                            onDetailExit: () -> Unit = {})
    fun supportsService(serviceId: String): Boolean
}

@Composable
internal fun FlavorRemoteServicesContent(selectedId: String?, onSelect: (String, String) -> Unit,
                                         onDetailExit: () -> Unit = {}) {
    val context = LocalContext.current
    val panel = remember(context.applicationContext) { flavorPanel(context.applicationContext) }
    panel?.Content(context, selectedId, onSelect, onDetailExit)
}

internal fun flavorSupportsRemoteService(context: Context, serviceId: String): Boolean =
    flavorPanel(context.applicationContext)?.supportsService(serviceId) == true

private fun flavorPanel(context: Context): FlavorRemoteServicesPanel? = runCatching {
    Class.forName("com.jarvys.agent.flavor.FullRemoteServicesPanel")
        .getDeclaredConstructor().newInstance() as FlavorRemoteServicesPanel
}.getOrNull()
