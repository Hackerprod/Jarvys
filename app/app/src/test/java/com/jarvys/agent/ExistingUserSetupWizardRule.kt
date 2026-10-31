package com.jarvys.agent

import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorStateStore
import com.jarvys.agent.ui.settings.SetupWizardPreferences
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Runs MainActivity integration tests as an existing user and restores first-run state afterward. */
class ExistingUserSetupWizardRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val preferences = context.getSharedPreferences(ConnectorStateStore.PREFERENCES, android.content.Context.MODE_PRIVATE)
            val previousPolicyWasSet = preferences.contains(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
            val previousPolicy = preferences.getString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, null)
            val previousCompletionWasSet = preferences.contains(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY)
            val previousCompletion = preferences.getBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, false)

            try {
                SetupWizardPreferences(preferences).complete(AutonomyPolicy.ALLOW)
                base.evaluate()
            } finally {
                preferences.edit().apply {
                    if (previousPolicyWasSet) putString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, previousPolicy)
                    else remove(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
                    if (previousCompletionWasSet) putBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, previousCompletion)
                    else remove(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY)
                }.commit()
                ActionPolicyDefaults.bindDefaultPolicyPreferences(null)
            }
        }
    }
}
