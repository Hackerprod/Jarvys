package com.jarvys.agent.modules

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap

/** Process-wide visibility gate for module handlers that require an interactive app host. */
object ModuleHostVisibility {
    private val lock = Any()
    private val resumedActivities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val installedApplications = Collections.newSetFromMap(IdentityHashMap<Application, Boolean>())
    @Volatile private var testVisibility: Boolean? = null

    @JvmStatic fun isVisible(): Boolean = testVisibility ?: synchronized(lock) { resumedActivities.isNotEmpty() }

    internal fun setVisibleForTests(visible: Boolean?) {
        testVisibility = visible
    }

    fun install(application: Application, onVisible: () -> Unit = {}) {
        synchronized(lock) {
            if (!installedApplications.add(application)) return
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                synchronized(lock) { resumedActivities.add(activity) }
                onVisible()
            }

            override fun onActivityPaused(activity: Activity) {
                synchronized(lock) { resumedActivities.remove(activity) }
            }

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(lock) { resumedActivities.remove(activity) }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }
}
