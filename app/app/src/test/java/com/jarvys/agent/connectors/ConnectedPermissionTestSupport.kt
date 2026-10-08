package com.jarvys.agent.connectors

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.jarvys.agent.BuildConfig
import java.io.File

/** Observable policies make duplicate dispatches visible without invoking a real connector. */
internal class RecordingPermissionStore : ConnectorAutonomyStore {
    private val delegate = InMemoryConnectorAutonomyStore()
    val changes = mutableListOf<Triple<String, String, AutonomyPolicy>>()
    override fun policy(connectorId: String, operationName: String) = delegate.policy(connectorId, operationName)
    override fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy) {
        changes += Triple(connectorId, operationName, policy)
        delegate.setPolicy(connectorId, operationName, policy)
    }
    override fun clearPolicies(connectorId: String) = delegate.clearPolicies(connectorId)
    override fun appendAudit(record: AutonomyAuditRecord) = delegate.appendAudit(record)
    override fun recentAudit(connectorId: String, limit: Int) = delegate.recentAudit(connectorId, limit)
}

internal class PermissionConnectionPreferences : ConnectorConnectionPreferences {
    private val connected = mutableSetOf<String>()
    override fun isConnected(id: String) = id in connected
    override fun setConnected(id: String, connected: Boolean) {
        if (connected) this.connected += id else this.connected -= id
    }
}

/** Optional evidence from the actual Activity and native popup windows, never a redrawn fixture. */
internal fun captureConnectedPermissionWindows(activity: Activity, name: String) {
    val baseDirectory = (System.getProperty("jarvys.permissions.captureDir") ?: System.getenv("JARVYS_UX21_CAPTURE_DIR"))?.let(::File) ?: return
    val directory = File(baseDirectory, BuildConfig.FLAVOR)
    check(directory.exists() || directory.mkdirs())
    fun save(view: View, suffix: String) {
        check(view.width > 0 && view.height > 0) { "A capture needs a laid-out native window" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File(directory, "$name$suffix.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    val decor = activity.window.decorView
    save(decor, "")
    // DropdownMenu owns a separate Android window. Preserve it separately at its real size.
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getDeclaredMethod("getInstance").invoke(null)
    val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
    roots.filterIsInstance<View>().filter { it !== decor && it.isShown }.forEachIndexed { index, view ->
        save(view, "-popup-$index")
    }
}
