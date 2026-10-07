package com.jarvys.agent

/** Pure per-app language selection and BCP-47 mapping. */
enum class AppLanguageChoice(val storedValue: String, val localeTag: String?) {
    ENGLISH("en", "en"),
    SPANISH("es", "es"),
    SYSTEM("system", null),
}

object AppLanguagePolicy {
    const val FIRST_LAUNCH_VALUE = "en"

    fun fromStoredValue(value: String?): AppLanguageChoice = when (value?.lowercase()) {
        "es", "es-es", "es-mx", "es-419" -> AppLanguageChoice.SPANISH
        "system", "" -> AppLanguageChoice.SYSTEM
        else -> AppLanguageChoice.ENGLISH
    }

    fun fromLocaleTags(tags: String?): AppLanguageChoice = when {
        tags.isNullOrBlank() -> AppLanguageChoice.SYSTEM
        tags.split(',').firstOrNull()?.substringBefore('-')?.equals("es", ignoreCase = true) == true -> AppLanguageChoice.SPANISH
        tags.split(',').firstOrNull()?.substringBefore('-')?.equals("en", ignoreCase = true) == true -> AppLanguageChoice.ENGLISH
        else -> AppLanguageChoice.SYSTEM
    }

    fun localeTags(choice: AppLanguageChoice): String = choice.localeTag.orEmpty()
}

/** Tiny storage contract keeps preference initialization and persistence testable on the JVM. */
interface AppLanguagePreferenceStore {
    fun read(): String?
    fun write(value: String)
}

class AppLanguagePreference(private val store: AppLanguagePreferenceStore) {
    fun current(): AppLanguageChoice {
        val saved = store.read()
        if (saved == null) {
            store.write(AppLanguagePolicy.FIRST_LAUNCH_VALUE)
            return AppLanguageChoice.ENGLISH
        }
        return AppLanguagePolicy.fromStoredValue(saved)
    }

    fun select(choice: AppLanguageChoice) = store.write(choice.storedValue)
}
