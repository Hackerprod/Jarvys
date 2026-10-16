package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import java.util.UUID

/** In-memory SecretStore for Robolectric tests, which does not provide AndroidKeyStore. */
class TestSecretStoreRule : ExternalResource() {
    private var singleton: java.lang.reflect.Field? = null
    private var previous: Any? = null

    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("test-secret-store-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        singleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        previous = singleton!!.get(null)
        singleton!!.set(null, SecretStore(preferences))
    }

    override fun after() {
        singleton?.set(null, previous)
        singleton = null
        previous = null
    }
}
