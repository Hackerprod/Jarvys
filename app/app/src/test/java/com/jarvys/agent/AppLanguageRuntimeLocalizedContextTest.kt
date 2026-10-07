package com.jarvys.agent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class AppLanguageRuntimeLocalizedContextTest {
    @Test @Config(qualifiers = "es")
    fun android13SpanishSystemEnglishAppLocalizesApplicationContext() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, AppLanguageChoice.ENGLISH)
        val localized = AppLanguageRuntime.localizedContext(app)
        assertEquals("en", localized.resources.configuration.locales[0].language)
        assertEquals("Language", localized.getString(R.string.language_title))
    }

    @Test @Config(qualifiers = "en")
    fun android13EnglishSystemSpanishAppLocalizesApplicationContext() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, AppLanguageChoice.SPANISH)
        val localized = AppLanguageRuntime.localizedContext(app)
        assertEquals("es", localized.resources.configuration.locales[0].language)
        assertEquals("Idioma", localized.getString(R.string.language_title))
    }

    @Test @Config(qualifiers = "es")
    fun android13SystemChoiceLeavesApplicationContextOnSystemLocale() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, AppLanguageChoice.SYSTEM)
        assertSame(app, AppLanguageRuntime.localizedContext(app))
        assertEquals("es", AppLanguageRuntime.localizedContext(app).resources.configuration.locales[0].language)
    }

    @Test @Config(qualifiers = "en")
    fun android13CallerConfigurationContextIsPreservedEvenInApplicationContextWrapper() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, AppLanguageChoice.ENGLISH)
        val spanish = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("es"))
        })
        val callerContext = object : ContextWrapper(spanish) {
            override fun getApplicationContext(): Context = this
        }
        assertSame(callerContext, AppLanguageRuntime.localizedContext(callerContext))
        assertEquals("es", AppLanguageRuntime.localizedContext(callerContext).resources.configuration.locales[0].language)
        assertEquals("Idioma", AppLanguageRuntime.localizedContext(callerContext).getString(R.string.language_title))
    }

    @Test @Config(qualifiers = "en")
    fun android13ActivityContextStaysPlatformLocalized() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, AppLanguageChoice.ENGLISH)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertSame(activity, AppLanguageRuntime.localizedContext(activity))
    }

    @Test @Config(sdk = [32], qualifiers = "en")
    fun preAndroid13ApplicationContextUsesPersistedChoiceAndSystemChoiceKeepsSystemLocale() {
        verifyMismatch(AppLanguageChoice.ENGLISH, "es")
        verifyMismatch(AppLanguageChoice.SPANISH, "en")
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, AppLanguageChoice.SYSTEM)
        assertEquals("en", AppLanguageRuntime.localizedContext(app).resources.configuration.locales[0].language)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertSame(activity, AppLanguageRuntime.localizedContext(activity))
    }

    private fun verifyMismatch(choice: AppLanguageChoice, systemTag: String) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("jarvys_app_language", Context.MODE_PRIVATE).edit().clear().commit()
        AppLanguageRuntime.select(app, choice)
        val systemContext = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(systemTag))
        })

        assertEquals(choice, AppLanguageRuntime.current(systemContext))
        val localized = AppLanguageRuntime.localizedContext(systemContext)
        assertEquals(choice.localeTag ?: systemTag, localized.resources.configuration.locales[0].language)
        val expected = if (choice == AppLanguageChoice.SPANISH) "Idioma" else "Language"
        val other = if (choice == AppLanguageChoice.SPANISH) "Language" else "Idioma"
        assertEquals(expected, localized.getString(R.string.language_title))
        org.junit.Assert.assertFalse(localized.getString(R.string.language_title).contains(other))

        AppLanguageRuntime.select(app, AppLanguageChoice.SYSTEM)
        val system = AppLanguageRuntime.localizedContext(systemContext)
        assertEquals(systemTag, system.resources.configuration.locales[0].language)
        assertEquals(if (systemTag == "es") "Idioma" else "Language", system.getString(R.string.language_title))
    }
}
