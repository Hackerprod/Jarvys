package com.jarvys.agent

import java.util.Locale

/** Request defaults for always-available, anonymous local Bing search. */
object WebSearchRequestPolicy {
    const val DEFAULT_RESULT_COUNT = 5

    fun resolveAcceptLanguage(setting: String, deviceLocale: Locale = Locale.getDefault()): String {
        if (setting.isNotBlank() && setting != "auto") return setting.take(100)
        val tag = deviceLocale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en-US"
        val base = tag.substringBefore('-')
        return if (base.equals(tag, true)) "$tag,en;q=0.7" else "$tag,$base;q=0.9,en;q=0.7"
    }
}
