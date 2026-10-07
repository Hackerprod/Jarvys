package com.jarvys.agent.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.JarvysUiPreferences
import com.jarvys.agent.R
import java.util.Locale

/** The same palette for native authentication dialogs and callback pages as Compose. */
object JarvysNativeTheme {
    @JvmStatic
    fun isDark(context: Context): Boolean = when (JarvysUiPreferences(context).themeMode()) {
        JarvysThemeMode.DARK -> true
        JarvysThemeMode.LIGHT -> false
        JarvysThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    @JvmStatic
    fun dialogContext(context: Context): Context = ContextThemeWrapper(
        context, if (isDark(context)) R.style.JarvysNativeDialogDark else R.style.JarvysNativeDialogLight,
    )

    @JvmStatic
    fun applyDialog(context: Context, dialog: AlertDialog, explanation: TextView) {
        val colors = jarvysColorScheme(isDark(context))
        val window = dialog.window ?: return
        window.decorView.background?.mutate()?.setTint(colors.surfaceContainerHigh.toArgb())
        fun style(view: View) {
            when (view) {
                is EditText -> {
                    view.setTextColor(colors.onSurface.toArgb())
                    view.setHintTextColor(colors.onSurfaceVariant.toArgb())
                    view.highlightColor = colors.primary.copy(alpha = 0.3f).toArgb()
                    view.backgroundTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(colors.primary.toArgb(), colors.outline.toArgb()),
                    )
                }
                is Button -> {
                    view.setTextColor(ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf(colors.primary.toArgb(), colors.onSurface.copy(alpha = 0.38f).toArgb()),
                    ))
                    (view.background as? RippleDrawable)?.setColor(
                        ColorStateList.valueOf(colors.primary.copy(alpha = 0.16f).toArgb()),
                    )
                }
                is TextView -> view.setTextColor(colors.onSurface.toArgb())
            }
            if (view is ViewGroup) repeat(view.childCount) { style(view.getChildAt(it)) }
        }
        style(window.decorView)
        explanation.setTextColor(colors.onSurfaceVariant.toArgb())
    }

    @JvmStatic
    fun styleInputError(context: Context, input: EditText) {
        val error = jarvysColorScheme(isDark(context)).error.toArgb()
        input.compoundDrawablesRelative.filterNotNull().forEach { it.mutate().setTint(error) }
    }

    @JvmStatic
    fun callbackPageCss(): String {
        fun Color.css() = String.format(Locale.ROOT, "#%06x", toArgb() and 0xFFFFFF)
        fun variables(dark: Boolean): String = jarvysColorScheme(dark).run {
            "--canvas:${background.css()};--surface:${surface.css()};--ink:${onSurface.css()};" +
                "--secondary:${onSurfaceVariant.css()};--accent:${primary.css()};"
        }
        return ":root{${variables(false)}}" +
            "body{font:16px system-ui,sans-serif;line-height:1.5;margin:0;padding:24px;color:var(--ink);background:var(--canvas)}" +
            "main{max-width:420px;margin:12vh auto;padding:28px;border-radius:24px;background:var(--surface)}" +
            "h1{font-size:24px;line-height:1.25;margin:12px 0}p{color:var(--secondary)}" +
            "small{letter-spacing:.12em;color:var(--accent)}@media(prefers-color-scheme:dark){:root{${variables(true)}}}"
    }
}
