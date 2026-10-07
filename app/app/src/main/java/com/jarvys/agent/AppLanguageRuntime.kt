package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Platform integration: LocaleManager on API 33+, configuration wrapping below API 33. */
object AppLanguageRuntime {
    private const val PREFS = "jarvys_app_language"
    private const val KEY_LANGUAGE = "choice"
    private const val KEY_PLATFORM_INITIALIZED = "platform_initialized"

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun preference(context: Context) = AppLanguagePreference(object : AppLanguagePreferenceStore {
        override fun read(): String? = preferences(context).getString(KEY_LANGUAGE, null)
        override fun write(value: String) { preferences(context).edit().putString(KEY_LANGUAGE, value).commit() }
    })

    fun current(context: Context): AppLanguageChoice {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) return AppLanguagePolicy.fromLocaleTags(locales.toLanguageTags())
        }
        return preference(context).current()
    }

    fun attachBaseContext(base: Context): Context {
        val selected = preference(base).current()
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = preferences(base)
            if (!prefs.getBoolean(KEY_PLATFORM_INITIALIZED, false)) {
                val localeManager = base.getSystemService(android.app.LocaleManager::class.java)
                localeManager.applicationLocales = LocaleList.forLanguageTags(AppLanguagePolicy.localeTags(selected))
                prefs.edit().putBoolean(KEY_PLATFORM_INITIALIZED, true).commit()
            }
            return base
        }
        return localeContext(base, selected, updateDefaultLocale = Build.VERSION.SDK_INT < 33)
    }

    @JvmStatic
    fun localizedContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            if (hasActivityBase(base)) return base
            val baseLocales = base.resources.configuration.locales.toLanguageTags()
            val systemLocales = android.content.res.Resources.getSystem()
                .configuration.locales.toLanguageTags()
            if (baseLocales != systemLocales) return base
            return localeContext(base, current(base), updateDefaultLocale = false)
        }
        return localeContext(base, current(base), updateDefaultLocale = true)
    }

    private fun hasActivityBase(context: Context): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        var current: Context? = context
        while (current != null && seen.add(current)) {
            if (current is android.app.Activity) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun localeContext(base: Context, selected: AppLanguageChoice, updateDefaultLocale: Boolean): Context {
        val localeTag = selected.localeTag
        val locale = localeTag?.let(Locale::forLanguageTag) ?: base.resources.configuration.locales.get(0)
        if (updateDefaultLocale) Locale.setDefault(locale)
        if (localeTag == null) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(localeTag))
        return base.createConfigurationContext(configuration)
    }

    fun select(context: Context, choice: AppLanguageChoice) {
        preference(context).select(choice)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(AppLanguagePolicy.localeTags(choice))
        } else {
            Locale.setDefault(choice.localeTag?.let(Locale::forLanguageTag)
                ?: context.resources.configuration.locales.get(0))
        }
    }

    /** Android's app-language screen is authoritative on API 33+, so mirror its latest choice. */
    fun synchronizeSystemChoice(context: Context): AppLanguageChoice {
        val selected = if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            AppLanguagePolicy.fromLocaleTags(locales.toLanguageTags())
        } else preference(context).current()
        preference(context).select(selected)
        return selected
    }
}
