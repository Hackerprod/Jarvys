package com.jarvys.agent.apkfactory

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.R
import java.util.concurrent.Executors

/** User authority editor. An executing agent cannot silently turn its own DENY back into ALLOW. */
open class FactoryPermissionsActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var guard: MemoryUiAutomationGuard.Lease
    private lateinit var column: LinearLayout
    private var policy: FactoryOperationPolicy? = null
    private var selected: FactoryOperationPolicy.Subject? = null
    private var resumed = false
    private var working = true
    private var error: String? = null
    private var dialog: AlertDialog? = null
    internal open fun policyStore() = FactoryOperationPolicy.get(applicationContext)
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard = MemoryUiAutomationGuard.enterProtectedSurface()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        render()
        worker.execute {
            val result = runCatching { policyStore() }
            runOnUiThread {
                if (!isDestroyed) { policy = result.getOrNull(); error = result.exceptionOrNull()?.message; working = false; render() }
            }
        }
    }
    private fun label(text: String, size: Float = 16f) { column.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, 10, 0, 10) }) }
    private fun ready() = resumed && !working && guard.isReadyForUser && !isDestroyed && !isFinishing
    private fun button(text: String, name: String, action: () -> Unit) {
        column.addView(object : Button(this) {
            private var rejectedGesture = false
            override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) rejectedGesture = false
                val allowed = super.onFilterTouchEventForSecurity(event) && unoccluded(event)
                if (!allowed && !rejectedGesture) cancelTouch(this, event)
                rejectedGesture = rejectedGesture || !allowed
                return !rejectedGesture
            }
        }.apply {
            this.text = text; tag = name; isEnabled = ready(); filterTouchesWhenObscured = true
            setOnClickListener { if (ready()) { guard.requireHumanUiInteraction(); action() } }
        })
    }
    private fun modeName(mode: FactoryOperationPolicy.Mode) = getString(when (mode) {
        FactoryOperationPolicy.Mode.ALLOW_ALWAYS -> R.string.factory_permissions_allow
        FactoryOperationPolicy.Mode.ASK -> R.string.factory_permissions_ask
        FactoryOperationPolicy.Mode.DENY -> R.string.factory_permissions_deny
    })
    private fun edit(action: () -> Unit) {
        working = true; error = null; dialog?.dismiss(); render()
        worker.execute {
            val result = runCatching(action)
            runOnUiThread { if (!isDestroyed) { working = false; error = result.exceptionOrNull()?.message; render() } }
        }
    }
    private fun chooseMode(title: String, current: FactoryOperationPolicy.Mode?, inherit: Boolean, save: (FactoryOperationPolicy.Mode?) -> Unit) {
        val options = if (inherit) listOf(null) + FactoryOperationPolicy.Mode.entries else FactoryOperationPolicy.Mode.entries
        val labels = options.map { it?.let(::modeName) ?: getString(R.string.factory_permissions_inherit) }.toTypedArray()
        dialog = AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, options.indexOf(current)) { _, which ->
            if (ready()) { guard.requireHumanUiInteraction(); edit { save(options[which]) } }
        }.setNegativeButton(android.R.string.cancel, null).show().also(::protectDialog)
    }
    private fun protectDialog(value: AlertDialog) {
        val window = value.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        value.listView?.filterTouchesWhenObscured = true
        val original = checkNotNull(window.callback)
        window.callback = object : Window.Callback by original {
            private var rejectedGesture = false
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) rejectedGesture = false
                if (!unoccluded(event)) {
                    // Intercept before ListView's platform security filter: otherwise a fully
                    // obscured MOVE can be dropped before we cancel an earlier clean DOWN.
                    if (!rejectedGesture) value.listView?.let { cancelTouch(it, event) }
                    rejectedGesture = true
                }
                return rejectedGesture || original.dispatchTouchEvent(event)
            }
        }
    }
    private fun cancelTouch(view: View, event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try { view.onTouchEvent(cancel) } finally { cancel.recycle() }
    }
    private fun unoccluded(event: MotionEvent) =
        event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED == 0 &&
            (Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0)
    private fun render() {
        if (!::column.isInitialized) return
        column.removeAllViews()
        label(getString(R.string.factory_permissions_title), 23f)
        label(getString(R.string.factory_permissions_summary))
        if (!guard.isReadyForUser) label(getString(R.string.factory_permissions_reopen))
        if (working) label(getString(R.string.factory_permissions_saving))
        error?.let { label(getString(R.string.factory_permissions_failed) + "\n" + it) }
        val value = policy
        if (value != null) {
            button(getString(R.string.factory_permissions_default, modeName(value.defaultMode())), "factory-permissions-default") {
                chooseMode(getString(R.string.factory_permissions_default_title), value.defaultMode(), false) { value.setDefaultMode(it!!) }
            }
            val subjects = value.subjects()
            if (selected !in subjects) selected = subjects.firstOrNull()
            if (subjects.isEmpty()) label(getString(R.string.factory_permissions_no_apps))
            else {
                button(selected!!.appId + "\n" + identityLabel(selected!!), "factory-permissions-app") {
                    dialog = AlertDialog.Builder(this).setTitle(R.string.factory_permissions_app)
                        .setSingleChoiceItems(subjects.map { it.appId + " · " + identityLabel(it) }.toTypedArray(), subjects.indexOf(selected)) { shown, which ->
                            if (ready()) { guard.requireHumanUiInteraction(); selected = subjects[which]; shown.dismiss(); render() }
                        }.setNegativeButton(android.R.string.cancel, null).show().also(::protectDialog)
                }
                selected?.let { subject ->
                    FactoryOperationPolicy.Operation.entries.forEach { operation ->
                        val override = value.overrideMode(subject, operation)
                        val source = if (override == null) getString(R.string.factory_permissions_inherit) else getString(R.string.factory_permissions_override)
                        button("${operation.id}\n${modeName(value.mode(subject, operation))} · $source", "factory-permissions-${operation.name}") {
                            chooseMode(operation.id, value.overrideMode(subject, operation), true) { value.setMode(subject, operation, it) }
                        }
                    }
                    button(getString(R.string.factory_permissions_revoke), "factory-permissions-revoke") { edit { value.revokeActive(subject) } }
                }
            }
        }
        column.addView(Button(this).apply { setText(android.R.string.ok); setOnClickListener { finish() } })
    }
    private fun identityLabel(subject: FactoryOperationPolicy.Subject): String =
        if (subject.certificate != null) getString(R.string.factory_permissions_certificate, subject.identity.take(16))
        else getString(R.string.factory_permissions_bootstrap, subject.identity.take(12))
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() { resumed = false; dialog?.dismiss(); dialog = null; super.onPause() }
    override fun onDestroy() { dialog?.dismiss(); worker.shutdown(); guard.close(); super.onDestroy() }
}
