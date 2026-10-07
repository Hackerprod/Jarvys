package com.jarvys.agent.proactive

fun interface ProactiveAppBlocklist {
    fun blockedApps(): Set<String>
}

object EmptyProactiveAppBlocklist : ProactiveAppBlocklist {
    override fun blockedApps(): Set<String> = emptySet()
}

sealed interface ProactivePrefilterDecision {
    data class Candidate(val event: ProactiveEvent) : ProactivePrefilterDecision
    data class Discard(val reason: String) : ProactivePrefilterDecision
}

sealed interface ProactiveCaptureResult {
    data object Disabled : ProactiveCaptureResult
    data class CandidateStored(val category: String) : ProactiveCaptureResult
    data class DiscardedStored(val reason: String) : ProactiveCaptureResult
    data object Duplicate : ProactiveCaptureResult
}

/** Keeps only structural user-facing signals; semantic/relevance decisions are deferred to a future model turn. */
object ProactivePrefilter {
    private val structuralCategories = setOf("service", "progress", "status", "transport", "sys", "system")
    // Android framework/SystemUI own OS and screenshot/recording notices; DownloadsProvider and
    // PackageInstaller own the download-complete and APK-received/install-status notifications.
    private val systemNotificationPackages = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.apps.screenshot",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    fun evaluate(
        event: ProactiveEvent,
        ownPackage: String,
        appBlocklist: ProactiveAppBlocklist = EmptyProactiveAppBlocklist,
    ): ProactivePrefilterDecision {
        val packageName = event.appPackage.orEmpty()
        val androidCategory = event.androidCategory.orEmpty().lowercase()
        val reason = when {
            event.state == ProactiveEvent.DISCARDED -> event.discardReason ?: "already_discarded"
            event.state == ProactiveEvent.PROCESSED -> "already_processed"
            packageName == ownPackage -> "own_app"
            packageName in systemNotificationPackages -> "system_package"
            packageName in appBlocklist.blockedApps() -> "blocked_app"
            event.ongoing -> "ongoing_notification"
            event.foregroundService -> "foreground_service"
            androidCategory in structuralCategories -> when (androidCategory) {
                "progress" -> "progress_notification"
                "transport" -> "media_transport"
                "service" -> "service_notification"
                else -> "system_notification"
            }
            event.groupSummary -> "group_summary"
            event.title.isNullOrBlank() && event.body.isNullOrBlank() -> "empty_content"
            else -> null
        }
        if (reason != null) return ProactivePrefilterDecision.Discard(reason)
        // Unclassified content (including promotions) remains a candidate for later semantic relevance evaluation.
        return ProactivePrefilterDecision.Candidate(
            if (event.category == "other") event.copy(prefilterMark = "needs_model_relevance")
            else event,
        )
    }
}

object ProactiveIngestion {
    fun ingest(
        event: ProactiveEvent,
        store: ProactiveEventStore,
        ownPackage: String,
        appBlocklist: ProactiveAppBlocklist = EmptyProactiveAppBlocklist,
    ): ProactiveCaptureResult = when (val decision = ProactivePrefilter.evaluate(event, ownPackage, appBlocklist)) {
        is ProactivePrefilterDecision.Candidate -> if (store.append(decision.event)) {
            ProactiveCaptureResult.CandidateStored(decision.event.category)
        } else ProactiveCaptureResult.Duplicate
        is ProactivePrefilterDecision.Discard -> if (store.recordDiscard(event, decision.reason)) {
            ProactiveCaptureResult.DiscardedStored(decision.reason)
        } else ProactiveCaptureResult.Duplicate
    }
}
