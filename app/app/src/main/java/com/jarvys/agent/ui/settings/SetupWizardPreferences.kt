package com.jarvys.agent.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorStateStore

/** First-run completion and the global autonomy default share the existing connector preferences. */
class SetupWizardPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE),
    )

    init {
        ActionPolicyDefaults.bindDefaultPolicyPreferences(preferences)
    }

    fun shouldShowOnLaunch(): Boolean = !preferences.getBoolean(
        ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, false,
    )

    fun selectedPolicy(): AutonomyPolicy = ActionPolicyDefaults.selectedDefaultPolicy(preferences)

    fun hasSelectedPolicy(): Boolean = ActionPolicyDefaults.hasSelectedDefaultPolicy(preferences)

    fun complete(policy: AutonomyPolicy) {
        val saved = preferences.edit()
            .putString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, policy.name)
            .putBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, true)
            .commit()
        check(saved) { "Could not save setup wizard choices" }
        ActionPolicyDefaults.bindDefaultPolicyPreferences(preferences)
    }

    /** Skipping records the current choice, which is Allow on a fresh install. */
    fun skip() = complete(selectedPolicy())
}
