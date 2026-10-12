package com.jarvys.agent

import android.app.AlertDialog
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalPresenter
import com.jarvys.agent.connectors.ApprovalSummary

/** An exact ASK remains visible in Settings/dialog routes; it never depends on a hidden chat card. */
internal object NativeMemoryReview {
    fun request(context: android.content.Context, summary: ApprovalSummary, token: CancellationToken): ApprovalDecision {
        val activity = context.activityOrNull() ?: error(context.getString(R.string.memory_native_open_foreground))
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val lifecycle = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
            ?: error(context.getString(R.string.memory_native_lifecycle_required))
        var observer: androidx.lifecycle.LifecycleEventObserver? = null
        var dialog: AlertDialog? = null
        var surface: MemoryUiAutomationGuard.Lease? = null
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || !gate.isPending(id)) {
                        gate.resolve(id, ApprovalDecision.CANCELLED); return@runOnUiThread
                    }
                    val lease = MemoryUiAutomationGuard.enterProtectedSurface()
                    if (!lease.isReadyForUser) { lease.close(); gate.resolve(id, ApprovalDecision.CANCELLED); return@runOnUiThread }
                    surface = lease
                    observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
                            gate.resolve(id, ApprovalDecision.CANCELLED)
                    }.also { lifecycle.addObserver(it) }
                    try {
                        val content = TextView(activity).apply {
                            text = nativeMemoryReviewLines(activity, summary).joinToString("\n\n"); setPadding(24,24,24,24); setTextIsSelectable(true)
                        }
                        dialog = AlertDialog.Builder(activity).setTitle(summary.title)
                            .setView(ScrollView(activity).apply { addView(content) })
                            .setPositiveButton(R.string.memory_native_allow_once) { _, _ ->
                                gate.resolveFromUi(id) { lease.requireHumanUiInteraction(); ApprovalDecision.APPROVED }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> gate.resolve(id, ApprovalDecision.DENIED) }
                            .setOnCancelListener { gate.resolve(id, ApprovalDecision.CANCELLED) }
                            .create().also { value ->
                                value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                value.setOnDismissListener { lease.close(); if (surface === lease) surface=null }
                                value.show()
                                value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                value.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured=true
                                val window = value.window
                                val original = window?.callback
                                if (window != null && original != null) window.callback = object : android.view.Window.Callback by original {
                                    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                                        if (event.flags and android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
                                            (android.os.Build.VERSION.SDK_INT >= 29 && event.flags and android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)) return true
                                        return original.dispatchTouchEvent(event)
                                    }
                                }
                            }
                    } catch (failure: RuntimeException) { lease.close(); surface=null; gate.resolve(id, ApprovalDecision.CANCELLED) }
                }
            }
            override fun update(id: String, decision: ApprovalDecision) {
                activity.runOnUiThread { observer?.let { lifecycle.removeObserver(it) }; observer=null; dialog?.dismiss(); dialog=null; surface?.close(); surface=null }
            }
        })
        return try { gate.request(summary, token) }
        finally { activity.runOnUiThread { observer?.let { lifecycle.removeObserver(it) }; observer=null; dialog?.dismiss(); dialog=null; surface?.close(); surface=null } }
    }
}
