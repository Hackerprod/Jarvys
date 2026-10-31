package com.jarvys.agent.coding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.runtime.FactoryUiAssets

internal object FactoryUiTestAssets {
    fun withUi(web: Map<String, ByteArray>): Map<String, ByteArray> =
        FactoryUiAssets.paths().associateWith { path ->
            ApplicationProvider.getApplicationContext<Context>().assets.open(path).use { FactoryUiAssets.read(path, it) }
        } + web
}
