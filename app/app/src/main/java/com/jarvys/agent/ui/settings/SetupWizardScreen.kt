package com.jarvys.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.connectors.AutonomyPolicy

/** Two short Compose steps with the required, user-editable default action policy. */
@Composable
fun SetupWizardScreen(
    preferences: SetupWizardPreferences,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(preferences.selectedPolicy().name) }
    val policy = remember(selected) { runCatching { AutonomyPolicy.valueOf(selected) }.getOrDefault(AutonomyPolicy.ALLOW) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (step == 0) stringResource(R.string.setup_wizard_welcome_title)
                    else stringResource(R.string.setup_wizard_permissions_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("setup-wizard-title"),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (step == 0) stringResource(R.string.setup_wizard_welcome_body)
                    else stringResource(R.string.setup_wizard_permissions_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (step == 1) {
                Spacer(Modifier.height(20.dp))
                listOf(
                    AutonomyPolicy.ALLOW to Pair(R.string.setup_wizard_allow_title, R.string.setup_wizard_allow_body),
                    AutonomyPolicy.ASK to Pair(R.string.setup_wizard_ask_title, R.string.setup_wizard_ask_body),
                    AutonomyPolicy.DENY to Pair(R.string.setup_wizard_deny_title, R.string.setup_wizard_deny_body),
                ).forEach { (choice, copy) ->
                    val isSelected = policy == choice
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .clickable { selected = choice.name }
                            .testTag("setup-wizard-policy-${choice.name.lowercase()}"),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.primary,
                        ) else null,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = { selected = choice.name })
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(stringResource(copy.first), fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(copy.second),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        preferences.skip()
                        onFinish()
                    },
                    modifier = Modifier.testTag("setup-wizard-skip"),
                ) { Text(stringResource(R.string.setup_wizard_skip)) }
                if (step == 0) {
                    Button(onClick = { step = 1 }, modifier = Modifier.testTag("setup-wizard-continue")) {
                        Text(stringResource(R.string.setup_wizard_continue))
                    }
                } else {
                    TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.setup_wizard_back)) }
                    Button(
                        onClick = {
                            preferences.complete(policy)
                            onFinish()
                        },
                        modifier = Modifier.testTag("setup-wizard-finish"),
                    ) { Text(stringResource(R.string.setup_wizard_finish)) }
                }
            }
        }
    }
}
