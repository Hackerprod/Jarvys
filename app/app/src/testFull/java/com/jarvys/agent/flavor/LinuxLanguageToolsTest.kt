package com.jarvys.agent.flavor

import android.content.Context
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.R
import com.jarvys.agent.UserDecisionGate
import com.jarvys.agent.UserDecisionPresenter
import com.jarvys.agent.UserDecisionResult
import com.jarvys.agent.UserDecisionSpec
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalPresenter
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.linux.LinuxExecResult
import com.jarvys.agent.linux.LinuxInstallPhase
import com.jarvys.agent.linux.LinuxInstallState
import com.jarvys.agent.linux.LinuxOutputCallback
import com.jarvys.agent.linux.LinuxProbeResult
import com.jarvys.agent.linux.LinuxProbeStatus
import com.jarvys.agent.linux.LinuxRuntime
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinuxLanguageToolsTest {
    @Test @Config(qualifiers = "es")
    fun englishAppLanguageOverridesSpanishSystemLocaleForEveryLinuxSurface() {
        verifyLocale(AppLanguageChoice.ENGLISH, "Install the optional Linux environment?", "This installs Ubuntu Base", "Install", "Not now",
            "Linux is not installed", "Installation was not approved")
    }

    @Test @Config(qualifiers = "en")
    fun spanishAppLanguageOverridesEnglishSystemLocaleForEveryLinuxSurface() {
        verifyLocale(AppLanguageChoice.SPANISH, "¿Instalar el entorno Linux opcional?", "Se instalará Ubuntu Base", "Instalar", "Ahora no",
            "Linux no está instalado", "No se aprobó la instalación")
    }

    private fun verifyLocale(
        choice: AppLanguageChoice,
        expectedConsent: String,
        expectedBody: String,
        expectedInstall: String,
        expectedLater: String,
        expectedStatus: String,
        expectedDismiss: String,
    ) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        AppLanguageRuntime.select(app, choice)
        val gate = UserDecisionGate()
        val decisionSpecs = mutableListOf<UserDecisionSpec>()
        val presenter = object : UserDecisionPresenter {
            override fun isAvailable() = true
            override fun show(id: String, spec: UserDecisionSpec) {
                decisionSpecs += spec
                val optionId = if (spec.options.any { it.id == "later" }) "later" else "cancel"
                gate.resolve(id, UserDecisionResult.Selected(spec.options.first { it.id == optionId }))
            }
            override fun update(id: String, result: UserDecisionResult) = Unit
        }
        val runtime = NotInstalledRuntime()
        val approvalGate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) = Unit
            override fun update(id: String, decision: com.jarvys.agent.connectors.ApprovalDecision) = Unit
        })
        val tools = FlavorLinuxTools.createForRuntime(app, "linux-language-test", 0,
            CorePromptBudget.standard(), runtime, approvalGate, gate, presenter, { true }).associateBy {
            it.declaration().name
        }
        val localized = AppLanguageRuntime.localizedContext(app)
        val languageTitle = localized.getString(R.string.language_title)
        assertTrue(languageTitle == if (choice == AppLanguageChoice.ENGLISH) "Language" else "Idioma")

        val status = JSONObject(tools.getValue("linux_status").execute(emptyMap(), CancellationToken.cancellable()).content)
        val exec = tools.getValue("linux_exec").execute(mapOf("command" to "id"), CancellationToken.cancellable()).content
        val setup = tools.getValue("linux_setup").execute(emptyMap(), CancellationToken.cancellable()).content
        val uninstall = tools.getValue("linux_uninstall").execute(emptyMap(), CancellationToken.cancellable()).content
        val consent = decisionSpecs.first { it.options.any { option -> option.id == "later" } }
        val uninstallChoice = decisionSpecs.first { it.options.any { option -> option.id == "keep_workspace" } }
        val installLabel = consent.options.first { it.id == "install" }.label
        val laterLabel = consent.options.first { it.id == "later" }.label
        val uninstallLabels = uninstallChoice.options.map { it.label }
        val prompt = FlavorLinuxTools.systemPromptSection(app, "linux-language-test", 0).orEmpty()

        assertTrue(consent.title, consent.title.startsWith(expectedConsent))
        assertTrue(consent.body, consent.body.contains(expectedBody))
        assertTrue(installLabel, installLabel.contains(expectedInstall))
        assertTrue(laterLabel, laterLabel.contains(expectedLater))
        assertTrue(uninstallLabels.toString(), uninstallLabels.any { it.contains(if (choice == AppLanguageChoice.ENGLISH) "keep /workspace" else "conservar /workspace") })
        assertTrue(status.toString(), status.getString("message").contains(expectedStatus))
        assertTrue(exec, exec.contains(expectedStatus))
        assertTrue(setup, setup.contains(expectedDismiss))
        assertTrue(uninstall, uninstall.contains(if (choice == AppLanguageChoice.ENGLISH) "cancelled" else "canceló"))
        assertTrue(prompt, prompt.contains(if (choice == AppLanguageChoice.ENGLISH) "optional" else "opcional"))

        if (choice == AppLanguageChoice.ENGLISH) {
            listOf(consent.title, installLabel, laterLabel, status.getString("message"), exec, setup, uninstall)
                .forEach { text -> assertFalse("Spanish leaked into English app text: $text",
                    text.contains("Instalar") || text.contains("Ahora no") || text.contains("¿")) }
        } else {
            listOf(consent.title, installLabel, laterLabel, status.getString("message"), exec, setup, uninstall)
                .forEach { text -> assertFalse("English leaked into Spanish app text: $text",
                    text.contains("Install the optional") || text.contains("Not now") || text.contains("Linux is not installed")) }
        }
    }

    private class NotInstalledRuntime : LinuxRuntime {
        override fun state() = LinuxInstallState()
        override fun availableBytes() = 8_000_000_000L
        override fun supportsArm64() = true
        override fun workspacePath() = "/workspace"
        override fun observe(observer: (LinuxInstallState) -> Unit) = AutoCloseable { }
        override fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken) = error("Not now must not install")
        override fun probe() = LinuxProbeResult(LinuxProbeStatus.NOT_INSTALLED)
        override fun markProbeFailure(code: String, detail: String) = Unit
        override fun markProbeSuccess() = Unit
        override fun uninstall() = Unit
        override fun deleteWorkspace() = Unit
        override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                          token: CancellationToken) = LinuxExecResult(127)
    }
}
