package com.jarvys.agent

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.connectors.AutonomyPolicy
import java.util.concurrent.Executors

/** User-controlled native effect choices. Operating under ALLOW never grants policy-edit authority. */
open class NativePermissionsActivity : ComponentActivity() {
    private val authorityLock = Any()
    private var editLease: MemoryUiAutomationGuard.Lease? = null
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var column: LinearLayout
    private var policy: NativeActionPolicy? = null
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private var working = true
    private var error: String? = null
    private var dialog: AlertDialog? = null
    internal open fun policyStore() = NativeActionPolicy(applicationContext)
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom); insets
        }
        worker.execute {
            val result = runCatching { policyStore() }
            runOnUiThread { if (!isDestroyed) { policy=result.getOrNull(); error=result.exceptionOrNull()?.message; working=false; render() } }
        }
        render()
    }
    private fun ready() = resumed && !working && !isFinishing && !isDestroyed
    private fun requireActive(ticket: Long) {
        check(resumed && generation == ticket && !isFinishing && !isDestroyed)
    }
    private fun modeName(mode: AutonomyPolicy) = getString(when(mode) {
        AutonomyPolicy.ALLOW -> R.string.factory_permissions_allow
        AutonomyPolicy.ASK -> R.string.factory_permissions_ask
        AutonomyPolicy.DENY -> R.string.factory_permissions_deny
    })
    private fun originName(origin: String) = getString(when(origin) {
        "CATALOG_DEFAULT" -> R.string.native_permission_origin_default
        "USER" -> R.string.native_permission_origin_user
        "LEGACY_UNKNOWN" -> R.string.native_permission_origin_legacy
        else -> R.string.native_permission_origin_invalid
    })
    private fun label(text: String, size: Float = 16f) {
        column.addView(TextView(this).apply { this.text=text; textSize=size; setPadding(0,12,0,12) })
    }
    private fun render() {
        if (!::column.isInitialized) return
        column.removeAllViews()
        label(getString(R.string.native_permissions_title),22f)
        label(getString(R.string.native_permissions_summary))
        error?.let { label(it) }
        val store = policy
        if (store != null) NativeActionPolicy.Operation.entries.forEach { operation ->
            val snapshot = runCatching { store.snapshot(operation) }.getOrElse { label(getString(operationLabel(operation)) + ": " + getString(R.string.native_permission_origin_invalid)); return@forEach }
            column.addView(object : Button(this) {
                override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean =
                    super.onFilterTouchEventForSecurity(event) && (android.os.Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0)
            }.apply {
                text = getString(operationLabel(operation)) + " · " + modeName(snapshot.policy) + "\n" + originName(snapshot.origin.name)
                tag = "native-permissions-${operation.id}"
                isEnabled = ready(); filterTouchesWhenObscured = true
                setOnClickListener { if (ready()) { choose(operation) } }
            })
        }
        column.addView(Button(this).apply { setText(R.string.factory_identity_close); setOnClickListener { finish() } })
    }
    private fun choose(operation: NativeActionPolicy.Operation) {
        val store = policy ?: return
        val modes = listOf(AutonomyPolicy.ALLOW,AutonomyPolicy.ASK,AutonomyPolicy.DENY)
        val current = runCatching { store.snapshot(operation) }.getOrElse { error=it.message; render(); return }
        // Reading choices is ordinary UI. The choice dialog is the actual human policy-edit review.
        val acquired = MemoryUiAutomationGuard.enterProtectedSurface()
        if (!acquired.isReadyForUser) {
            acquired.close()
            error = getString(R.string.memory_ui_reopen_manually)
            render()
            return
        }
        editLease = acquired
        try { dialog = AlertDialog.Builder(this).setTitle(operationLabel(operation))
            .setSingleChoiceItems(modes.map(::modeName).toTypedArray(), modes.indexOf(current.policy)) { _, index ->
                if (!ready()) return@setSingleChoiceItems
                val ticket = generation
                if (runCatching { requireActive(ticket); acquired.requireHumanUiInteraction() }.isFailure) return@setSingleChoiceItems
                working=true; dialog?.dismiss(); dialog=null; render()
                worker.execute {
                    val result = runCatching { synchronized(authorityLock) {
                        requireActive(ticket); acquired.requireHumanUiInteraction(); store.set(operation,modes[index])
                    } }
                    runOnUiThread { acquired.close(); if (editLease === acquired) editLease=null; if (!isDestroyed) { working=false; error=result.exceptionOrNull()?.message; render() } }
                }
            }.setNegativeButton(android.R.string.cancel,null).show().also { value ->
                value.setOnDismissListener {
                    if (!working) { acquired.close(); if (editLease === acquired) editLease=null }
                }
                value.listView.filterTouchesWhenObscured=true
                val target = value.window
                val original = target?.callback
                if (target != null && original != null) target.callback = object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                            (android.os.Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)) return true
                        return original.dispatchTouchEvent(event)
                    }
                }
            }
        } catch (failure: RuntimeException) { acquired.close(); editLease=null; error=failure.message; render() }
    }
    override fun onResume() { super.onResume(); resumed=true; render() }
    override fun onPause() { synchronized(authorityLock) { resumed=false; generation++ }; dialog?.dismiss(); dialog=null; super.onPause() }
    override fun onDestroy() { synchronized(authorityLock) { resumed=false; generation++ }; editLease?.close(); editLease=null; worker.shutdown(); super.onDestroy() }
    companion object {
        internal fun operationLabel(operation: NativeActionPolicy.Operation): Int = when(operation) {
            NativeActionPolicy.Operation.MEMORY_VIEW -> R.string.native_permission_memory_view
            NativeActionPolicy.Operation.MEMORY_MODIFY -> R.string.native_permission_memory_modify
            NativeActionPolicy.Operation.MEMORY_DELETE -> R.string.native_permission_memory_delete
            NativeActionPolicy.Operation.MEMORY_RESTORE -> R.string.native_permission_memory_restore
            NativeActionPolicy.Operation.MEMORY_CLEAR -> R.string.native_permission_memory_clear
            NativeActionPolicy.Operation.MEMORY_EXPORT -> R.string.native_permission_memory_export
            NativeActionPolicy.Operation.MEMORY_SHARE_PERSONAL -> R.string.native_permission_memory_share
            NativeActionPolicy.Operation.MEMORY_REFLECTION -> R.string.native_permission_memory_reflection
            NativeActionPolicy.Operation.TASK_CREATE -> R.string.native_permission_task_create
            NativeActionPolicy.Operation.TASK_UPDATE -> R.string.native_permission_task_update
            NativeActionPolicy.Operation.TASK_CANCEL -> R.string.native_permission_task_cancel
            NativeActionPolicy.Operation.TASK_VIEW -> R.string.native_permission_task_view
            NativeActionPolicy.Operation.BOT_CREATE -> R.string.native_permission_bot_create
            NativeActionPolicy.Operation.BOT_MASCOT -> R.string.native_permission_bot_mascot
            NativeActionPolicy.Operation.IDENTITY_CREATE -> R.string.native_permission_identity_create
            NativeActionPolicy.Operation.IDENTITY_EXPORT -> R.string.native_permission_identity_export
            NativeActionPolicy.Operation.IDENTITY_IMPORT -> R.string.native_permission_identity_import
            NativeActionPolicy.Operation.IDENTITY_RECONCILE -> R.string.native_permission_identity_reconcile
        }
    }
}
