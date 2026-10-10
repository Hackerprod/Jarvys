package com.jarvys.agent.apkfactory

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.webkit.WebViewCompat
import com.jarvys.agent.R
import com.jarvys.agent.coding.FactoryPreviewRegistry
import com.jarvys.factory.runtime.FactoryRuntime
import com.jarvys.factory.runtime.FactoryWindowPolicy
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/** Dedicated, private Factory harness. Never attaches native handlers to the ordinary HTML viewer. */
open class FactoryPreviewActivity : Activity() {
    private var session: FactoryPreviewRegistry.Session? = null
    private var runtime: RuntimeHandle? = null
    private lateinit var status: TextView
    private lateinit var reset: Button
    private var closed = false

    internal interface RuntimeHandle {
        fun start(): Boolean
        fun reset(): Boolean
        fun close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The preview can contain project content and user-entered test data. No automatic capture.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = FactoryWindowPolicy.createRoot(this)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val dark = FactoryWindowPolicy.isDark(this)
        val foreground = if (dark) FactoryWindowPolicy.DARK_TEXT else FactoryWindowPolicy.LIGHT_TEXT
        val padding = (12 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            tag = "factory-preview-status"
            setTextColor(foreground)
            textSize = 14f
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this)
        reset = Button(this).apply {
            tag = "factory-preview-reset"
            setText(R.string.factory_preview_reset)
            isEnabled = false
            filterTouchesWhenObscured = true
            setOnClickListener {
                val current = session ?: return@setOnClickListener
                try {
                    current.validate()
                    reset.isEnabled = runtime?.reset() == true
                    if (!reset.isEnabled) current.fail()
                    current.record("lifecycle", "reset")
                } catch (_: Exception) { expire() }
            }
        }
        controls.addView(reset, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            tag = "factory-preview-close"
            setText(R.string.factory_preview_close)
            filterTouchesWhenObscured = true
            setOnClickListener { closePreview(); finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(controls, LinearLayout.LayoutParams(-1, -2))
        val content = FrameLayout(this).apply { tag = "factory-preview-content" }
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        // Tokens are process-memory-only, one-shot and never restored from saved state.
        val launchToken = intent?.getStringExtra(EXTRA_TOKEN)
        intent?.removeExtra(EXTRA_TOKEN)
        if (savedInstanceState != null || launchToken == null) { expire(); return }
        val active = FactoryPreviewRegistry.consume(launchToken)
        if (active == null) { expire(); return }
        session = active
        try {
            active.validate()
            val metadata = active.snapshot.metadata()
            status.text = getString(R.string.factory_preview_disclosure,
                metadata.getString("app_id"), metadata.getInt("version_code"), metadata.getString("apk_sha256"))
            active.setOnRevoked { runOnUiThread { expire() } }
            if (closed || !active.isActive()) { expire(); return }
            val packageInfo = WebViewCompat.getCurrentWebViewPackage(this)
            active.setWebViewVersion(packageInfo?.versionName ?: "unavailable")
            val created = createRuntime(content, active)
            if (closed || !active.isActive()) { created.close(); expire(); return }
            runtime = created
            active.record("lifecycle", "created")
            val started = created.start()
            if (closed || !active.isActive()) { closePreview(); return }
            reset.isEnabled = started
            if (!started) active.fail()
        } catch (_: Exception) { active.fail(); expire() }
    }

    internal open fun createRuntime(content: FrameLayout, active: FactoryPreviewRegistry.Session): RuntimeHandle {
        val base = FactoryRuntime.previewHost(String(active.snapshot.configBytes(), Charsets.UTF_8),
            FactoryRuntime.AssetSource { path ->
                ByteArrayInputStream(active.snapshot.asset(path) ?: throw FileNotFoundException("Unavailable preview asset"))
            })
        val host = object : FactoryRuntime.Host {
            override fun configuration() = base.configuration()
            override fun open(path: String) = base.open(path)
            override fun isPreview() = true
            override fun storage() = base.storage()
            override fun resetStorage() = base.resetStorage()
            override fun isActive() = active.isActive()
            override fun isSessionOpen() = active.isOpen()
            override fun lifecycleLock(): Any = active
            override fun onTrace(operation: String, outcome: String) = active.record(operation, outcome)
        }
        val controller = FactoryRuntime(this, content, host)
        return object : RuntimeHandle {
            override fun start() = controller.start()
            override fun reset() = controller.reset()
            override fun close() = controller.close()
        }
    }

    private fun expire() {
        closePreview()
        status.setText(R.string.factory_preview_expired)
        reset.isEnabled = false
    }

    private fun closePreview() {
        if (closed) return
        closed = true
        try { runtime?.close() } finally {
            runtime = null
            session?.close()
            session = null
        }
    }

    override fun onStop() {
        // No background harness, retained WebView or silent recreation after a lifecycle boundary.
        closePreview()
        reset.isEnabled = false
        status.setText(R.string.factory_preview_expired)
        super.onStop()
    }

    override fun onDestroy() { closePreview(); super.onDestroy() }

    companion object { const val EXTRA_TOKEN = "factory_preview_token" }
}
