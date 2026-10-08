package com.jarvys.agent.ui.chat

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.approvalIntent as buildApprovalIntent
import com.jarvys.agent.AssistantMarkdown
import com.jarvys.agent.ChatMessageList
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysChoiceOption
import com.jarvys.agent.JarvysChoiceSheet
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.MessageReactionEmoji
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.skills.SkillFileLink
import com.jarvys.agent.WebSearchFailurePresentation
import com.jarvys.agent.WebSearchFailureKind
import com.jarvys.agent.WebSearchTools
import com.jarvys.agent.WebSearchUrlPolicy
import com.jarvys.agent.UserDecisionRequests
import com.jarvys.agent.UserDecisionResult
import com.jarvys.agent.UserDecisionRole
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalOutcomeTone
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.FlavorAutonomyUi
import com.jarvys.agent.connectors.approvalPrimaryActionResourceId
import com.jarvys.agent.connectors.approvalStatusPresentation
import com.jarvys.agent.connectors.approveAndAllowAlways
import com.jarvys.agent.crew.CrewApprovalAttribution
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.proactive.ProactiveSuggestedReply

/** Jarvys transcript renderer: a readable response stream with a narrow intent trail. */
@Composable
fun ConversationTimeline(
    conversationKey: String,
    generatedImageSessionId: String = conversationKey,
    events: List<AgentRunUiEvent>,
    isRunning: Boolean,
    connectorRegistry: ConnectorRegistry,
    onOpenPreview: (String) -> Unit,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    chatWithoutMemory: Boolean,
    targetMessageId: String? = null,
    onProactiveSuggestedReply: (String, Int) -> Unit = { _, _ -> },
    onUndoMemoryRevision: (Long) -> Unit = {},
    onUndoReflectionGroup: (String) -> Unit = {},
    onTranslateAssistant: (AgentRunUiEvent, String) -> Unit = { _, _ -> },
    onCancelTranslation: (String) -> Unit = {},
    onHideTranslation: (String) -> Unit = {},
    onDeleteAssistant: (AgentRunUiEvent) -> Unit = {},
    onRegenerateAssistant: (AgentRunUiEvent) -> Unit = {},
    onSpeakAssistant: (String, String) -> Unit = { _, _ -> },
    speakingMessageId: String? = null,
    activeTranslationMessageId: String? = null,
    translationErrorMessageId: String? = null,
    translationError: String? = null,
    emptyReport: String? = null,
    allowRegeneration: Boolean = true,
    isCompacting: Boolean = false,
    compactionStatus: String? = null,
    isReflecting: Boolean = false,
    reflectionStatus: String? = null,
    onOpenCrewMission: (String) -> Unit = {},
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    runSnapshot: AgentRunUiSnapshot? = null,
    allowMessageEntrance: Boolean = true,
) {
    val latestAnswerId = events.lastOrNull { it.kind == "assistant" && it.stage == null }?.messageId
    val entryKeys = events.map(ChatMessageArrivalPolicy::key)
    val snapshotAllowsArrival = runSnapshot?.let { it.running || it.outcome != null } ?: isRunning
    val canAnimateArrival = allowMessageEntrance && snapshotAllowsArrival
    val seenEntryKeys = remember(conversationKey) { entryKeys.toMutableSet() }
    val newestArrivalKey = remember(conversationKey, entryKeys, canAnimateArrival) {
        val added = ChatMessageArrivalPolicy.newestUnseen(seenEntryKeys, entryKeys, canAnimateArrival)
        seenEntryKeys.addAll(entryKeys)
        added
    }
    androidx.compose.runtime.key(conversationKey) {
        ChatMessageList(
            conversationKey = conversationKey,
            messages = events,
            targetMessage = { targetMessageId != null && it.messageId == targetMessageId },
            isRunning = isRunning,
            messageKey = { it.id },
            isUserMessage = { it.kind == "user" },
            modifier = Modifier.fillMaxWidth(),
            itemContent = { event ->
                JarvysConversationEvent(
                    event = event,
                    generatedImageSessionId = generatedImageSessionId,
                    animateEntry = ChatMessageArrivalPolicy.key(event) == newestArrivalKey,
                    connectorRegistry = connectorRegistry,
                    onOpenPreview = onOpenPreview,
                    onOpenSkillFile = onOpenSkillFile,
                    onUndoMemoryRevision = onUndoMemoryRevision,
                    onUndoReflectionGroup = onUndoReflectionGroup,
                    onTranslateAssistant = onTranslateAssistant,
                    onCancelTranslation = onCancelTranslation,
                    onHideTranslation = onHideTranslation,
                    onDeleteAssistant = onDeleteAssistant,
                    onRegenerateAssistant = onRegenerateAssistant,
                    onSpeakAssistant = onSpeakAssistant,
                    speakingMessageId = speakingMessageId,
                    activeTranslationMessageId = activeTranslationMessageId,
                    translationErrorMessageId = translationErrorMessageId,
                    translationError = translationError,
                    isLastAssistant = event.messageId.isNotEmpty() && event.messageId == latestAnswerId
                            && !isRunning && allowRegeneration,
                    streamActive = isRunning,
                    onOpenCrewMission = onOpenCrewMission,
                    onSaveGeneratedImage = onSaveGeneratedImage,
                    onShareGeneratedImage = onShareGeneratedImage,
                    onProactiveSuggestedReply = onProactiveSuggestedReply,
                )
            },
            newestItems = {
                if (isRunning) item(key = "assistant-working") {
                    AssistantWorkingMarker(AgentRunUiSnapshot(running = true, events = events))
                }
            },
            fixedItems = {
                emptyReport?.let { report -> item(key = "run-report") {
                    JarvysConversationEvent(AgentRunUiEvent(0L, "result", stringResource(R.string.chat_latest_run_title), report),
                        connectorRegistry, onOpenPreview, onOpenSkillFile)
                } }
                if (isReflecting) item(key = "reflection-status") {
                    BackgroundStatusMarker(reflectionStatus ?: stringResource(R.string.reflection_status_running))
                }
                if (isCompacting) item(key = "compaction-status") {
                    BackgroundStatusMarker(compactionStatus ?: stringResource(R.string.compaction_running))
                }
                if (chatWithoutMemory) item(key = "memory-disabled") {
                    JarvysGroup { Text(stringResource(R.string.memory_chat_indicator), Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                }
            },
        )
    }
}

@Composable
private fun AssistantWorkingMarker(snapshot: AgentRunUiSnapshot) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentPresenceIndicator(snapshot, showLabel = true)
        StreamingTextIndicator()
    }
}

@Composable
private fun BackgroundStatusMarker(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun JarvysConversationEvent(
    event: AgentRunUiEvent,
    connectorRegistry: ConnectorRegistry,
    onOpenPreview: (String) -> Unit,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    generatedImageSessionId: String = "",
    onUndoMemoryRevision: (Long) -> Unit = {},
    onUndoReflectionGroup: (String) -> Unit = {},
    onTranslateAssistant: (AgentRunUiEvent, String) -> Unit = { _, _ -> },
    onCancelTranslation: (String) -> Unit = {},
    onHideTranslation: (String) -> Unit = {},
    onDeleteAssistant: (AgentRunUiEvent) -> Unit = {},
    onRegenerateAssistant: (AgentRunUiEvent) -> Unit = {},
    onSpeakAssistant: (String, String) -> Unit = { _, _ -> },
    speakingMessageId: String? = null,
    activeTranslationMessageId: String? = null,
    translationErrorMessageId: String? = null,
    translationError: String? = null,
    isLastAssistant: Boolean = false,
    streamActive: Boolean = false,
    onOpenCrewMission: (String) -> Unit = {},
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit = {},
    onProactiveSuggestedReply: (String, Int) -> Unit = { _, _ -> },
    animateEntry: Boolean = false,
) {
    when (event.kind) {
        "user" -> TimelineArrival(animateEntry) { IntentMessage(event, generatedImageSessionId) }
        "assistant" -> TimelineArrival(animateEntry) {
            AssistantReplyView(event, onOpenSkillFile, event.stage == "FAILED",
                onTranslateAssistant, onRegenerateAssistant, onSpeakAssistant, speakingMessageId, isLastAssistant,
                onCancelTranslation, onDeleteAssistant, activeTranslationMessageId, translationErrorMessageId,
                translationError, streamActive, onProactiveSuggestedReply)
        }
        "assistant_translation" -> TranslationNote(event, onOpenSkillFile, onHideTranslation)
        "progress" -> RunProgressLine(event)
        "tool" -> TimelineArrival(animateEntry) { ConnectorActivityLine(event, connectorRegistry, onOpenPreview, onOpenSkillFile) }
        "memory" -> MemoryRevisionLine(event, onUndoMemoryRevision, onUndoReflectionGroup)
        "compaction" -> CompactionLine(event)
        "approval" -> TimelineArrival(animateEntry) { ApprovalDecisionCard(event, connectorRegistry) }
        "user_decision" -> TimelineArrival(animateEntry) { UserDecisionEventCard(event) }
        "crew_mission" -> event.crewMissionSnapshot?.let { mission ->
            CrewMissionCard(mission, onOpen = { onOpenCrewMission(mission.missionId) })
        }
        "delivered_file" -> TimelineArrival(animateEntry) {
            DeliveredArtifactEventCard(event, generatedImageSessionId, onOpenPreview)
        }
        "generated_image" -> TimelineArrival(animateEntry) {
            GeneratedImageEventCard(event, generatedImageSessionId, onSaveGeneratedImage, onShareGeneratedImage)
        }
        else -> ResultNote(event, onOpenSkillFile)
    }
}

@Composable
private fun TimelineArrival(animateEntry: Boolean, content: @Composable () -> Unit) {
    if (!animateEntry || LocalReducedMotion.current) {
        content()
    } else AnimatedVisibility(
        visible = true,
        enter = fadeIn(JarvysMotion.feedback()) +
            androidx.compose.animation.slideInVertically(JarvysMotion.messageArrival()) { it / 12 },
        exit = ExitTransition.None,
        label = "new-timeline-entry",
    ) { content() }
}

@Composable
internal fun IntentMessage(event: AgentRunUiEvent, sessionId: String = "") {
    val context = LocalContext.current
    val hasReaction = event.kind == "user" && event.proactiveThreadKey.isNullOrEmpty() && !event.proactiveNotice &&
        MessageReactionEmoji.isValid(event.reactionEmoji)
    // Measure the actual badge, including accessibility font scaling, before reserving its two halves.
    // Stable slots keep selection and attachment state intact when the reaction changes or disappears.
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val badge = subcompose("reaction") {
            if (hasReaction) {
                val description = stringResource(R.string.chat_message_reaction_accessibility, event.reactionEmoji)
                Surface(
                    modifier = Modifier.testTag("user-message-reaction-${event.id}")
                        .clearAndSetSemantics { contentDescription = description },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        .compositeOver(MaterialTheme.colorScheme.surface),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)),
                ) {
                    Text(event.reactionEmoji, Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        fontSize = 16.sp, lineHeight = 20.sp)
                }
            }
        }.singleOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val insideHalf = (badge?.height ?: 0) / 2
        val outsideHalf = (badge?.height ?: 0) - insideHalf
        val contentBottom = if (badge == null) 12.dp else maxOf(12.dp, insideHalf.toDp() + 4.dp)
        val bubble = subcompose("bubble") {
            Row(Modifier.fillMaxWidth().semantics {
                contentDescription = context.getString(R.string.chat_user_message_accessibility)
            }, horizontalArrangement = Arrangement.End) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.widthIn(min = (badge?.width ?: 0).toDp())
                        .testTag("user-message-bubble-${event.id}")) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = contentBottom),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (event.attachments.isNotEmpty()) UserChatAttachments(event.attachments, sessionId, Modifier.widthIn(max = 320.dp))
                        if (event.text.isNotBlank()) SelectionContainer {
                            AssistantMarkdown(markdown = event.text, onOpenSkillFile = {},
                                textColor = MaterialTheme.colorScheme.onPrimaryContainer, userMessage = true)
                        }
                    }
                }
            }
        }.single().measure(constraints.offset(vertical = -outsideHalf).copy(minHeight = 0))
        layout(bubble.width, bubble.height + outsideHalf) {
            bubble.placeRelative(0, 0)
            // The badge stays inside this layout's bounds, so the outside half cannot clip into the next row.
            badge?.placeRelative(bubble.width - badge.width, bubble.height - insideHalf)
        }
    }
}

@Composable
internal fun AssistantReplyView(
    event: AgentRunUiEvent,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    isError: Boolean,
    onTranslate: (AgentRunUiEvent, String) -> Unit = { _, _ -> },
    onRegenerate: (AgentRunUiEvent) -> Unit = {},
    onSpeak: (String, String) -> Unit = { _, _ -> },
    speakingMessageId: String? = null,
    isLastAssistant: Boolean = false,
    onCancelTranslation: (String) -> Unit = {},
    onDelete: (AgentRunUiEvent) -> Unit = {},
    activeTranslationMessageId: String? = null,
    translationErrorMessageId: String? = null,
    translationError: String? = null,
    streamActive: Boolean = false,
    onProactiveSuggestedReply: (String, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    var languageDialog by remember(event.id) { mutableStateOf(false) }
    var actionSheet by remember(event.id) { mutableStateOf(false) }
    var confirmRegenerate by remember(event.id) { mutableStateOf(false) }
    var confirmDelete by remember(event.id) { mutableStateOf(false) }
    var showSelection by remember(event.id) { mutableStateOf(false) }
    val speaking = speakingMessageId == event.messageId
    val replySizeMotion = if (reducedMotion) Modifier else Modifier.animateContentSize(JarvysMotion.expand())
    Column(Modifier.fillMaxWidth().then(replySizeMotion)) {
        Row(Modifier.fillMaxWidth().semantics {
            contentDescription = context.getString(
                if (isError) R.string.chat_assistant_error_accessibility else R.string.chat_assistant_message_accessibility,
            )
        }) {
            // One selection scope per body; actions and neighboring messages stay outside it.
            SelectionContainer {
                AssistantMarkdown(event.text, onOpenSkillFile = onOpenSkillFile,
                    textColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
            }
        }
        if (event.proactiveReplies.isNotEmpty()) SuggestedReplyActions(event, onProactiveSuggestedReply, !streamActive)
        AnimatedVisibility(
            visible = !isError && event.stage == null && !streamActive,
            enter = if (reducedMotion) EnterTransition.None else
                expandVertically(JarvysMotion.expand()) + fadeIn(JarvysMotion.feedback()),
            exit = if (reducedMotion) ExitTransition.None else
                shrinkVertically(JarvysMotion.expand()) + fadeOut(JarvysMotion.feedback()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReplyAction(LucideIcons.Copy, stringResource(R.string.chat_footer_copy_description)) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            context.getString(R.string.chat_footer_clip_label), event.text))
                        Toast.makeText(context, R.string.chat_footer_copied, Toast.LENGTH_SHORT).show()
                    }
                    if (isLastAssistant) ReplyAction(LucideIcons.RefreshCw, stringResource(R.string.chat_footer_regenerate)) {
                        confirmRegenerate = true
                    }
                    ReplyAction(if (speaking) LucideIcons.CircleStop else LucideIcons.Volume2,
                        stringResource(if (speaking) R.string.chat_footer_stop_speaking else R.string.chat_footer_speak)) {
                        onSpeak(event.messageId, if (speaking) "" else event.text)
                    }
                    ReplyAction(LucideIcons.Languages, stringResource(R.string.chat_footer_translate)) {
                        if (activeTranslationMessageId == event.messageId) onCancelTranslation(event.messageId)
                        else languageDialog = true
                    }
                    ReplyAction(LucideIcons.Ellipsis, stringResource(R.string.chat_footer_more)) { actionSheet = true }
                }
                if (activeTranslationMessageId == event.messageId) BackgroundStatusMarker(stringResource(R.string.chat_footer_translating))
                if (translationErrorMessageId == event.messageId && translationError != null) {
                    Text(translationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (languageDialog) JarvysChoiceSheet(
        title = stringResource(R.string.chat_footer_language),
        choices = listOf(
            JarvysChoiceOption("English", stringResource(R.string.chat_footer_lang_english)),
            JarvysChoiceOption("Spanish", stringResource(R.string.chat_footer_lang_spanish)),
            JarvysChoiceOption("Simplified Chinese", stringResource(R.string.chat_footer_lang_simplified_chinese)),
            JarvysChoiceOption("Traditional Chinese", stringResource(R.string.chat_footer_lang_traditional_chinese)),
            JarvysChoiceOption("Japanese", stringResource(R.string.chat_footer_lang_japanese)),
            JarvysChoiceOption("Korean", stringResource(R.string.chat_footer_lang_korean)),
            JarvysChoiceOption("French", stringResource(R.string.chat_footer_lang_french)),
            JarvysChoiceOption("German", stringResource(R.string.chat_footer_lang_german)),
            JarvysChoiceOption("Italian", stringResource(R.string.chat_footer_lang_italian)),
        ),
        selected = null,
        onClose = { languageDialog = false },
        onSelect = { onTranslate(event, it) },
    )
    if (actionSheet) JarvysChoiceSheet(
        title = stringResource(R.string.chat_footer_more),
        choices = listOf(
            JarvysChoiceOption("share", stringResource(R.string.chat_footer_share)),
            JarvysChoiceOption("select_copy", stringResource(R.string.chat_footer_select_copy)),
            JarvysChoiceOption("delete", stringResource(R.string.chat_footer_delete)),
        ),
        selected = null,
        onClose = { actionSheet = false },
        onSelect = { action -> when (action) {
            "share" -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, event.text), context.getString(R.string.chat_footer_share)))
            "select_copy" -> showSelection = true
            "delete" -> confirmDelete = true
        } },
    )
    if (showSelection) AlertDialog(
        onDismissRequest = { showSelection = false },
        title = { Text(stringResource(R.string.chat_footer_select_copy)) },
        text = { ScrollableDialogContent { SelectionContainer { Text(event.text) } } },
        confirmButton = { TextButton(onClick = { showSelection = false }) { Text(stringResource(R.string.chat_footer_cancel)) } },
    )
    if (confirmRegenerate) AlertDialog(
        onDismissRequest = { confirmRegenerate = false },
        title = { Text(stringResource(R.string.chat_footer_regenerate_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.chat_footer_regenerate_warning)) } },
        confirmButton = { TextButton(onClick = { confirmRegenerate = false; onRegenerate(event) }) { Text(stringResource(R.string.chat_footer_regenerate)) } },
        dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(R.string.chat_footer_cancel)) } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.chat_footer_delete_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.chat_footer_delete_warning)) } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(event) }) { Text(stringResource(R.string.chat_footer_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.chat_footer_cancel)) } },
    )
}

@Composable
private fun ReplyAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SuggestedReplyActions(event: AgentRunUiEvent, onSelect: (String, Int) -> Unit, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
        event.proactiveReplies.forEachIndexed { index, reply ->
            AssistChip(onClick = { if (!event.proactiveRepliesUsed && enabled) onSelect(event.messageId, index) },
                enabled = !event.proactiveRepliesUsed && enabled,
                label = { Text(if (event.proactiveRepliesUsed) "${reply.label} · ${stringResource(R.string.proactive_suggested_reply_used)}" else reply.label) })
        }
    }
}

@Composable
private fun RunProgressLine(event: AgentRunUiEvent) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
            Icon(LucideIcons.Clock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(5.dp).size(14.dp))
        }
        Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            event.stage?.let { Text(it, color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
            Text(event.text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConnectorActivityLine(
    event: AgentRunUiEvent,
    registry: ConnectorRegistry,
    onOpenPreview: (String) -> Unit,
    onOpenSkillFile: (SkillFileLink) -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val searchQuery = WebSearchTools.searchLabelQuery(event.toolDisplayName)
    val connectorOperation = registry.definitions.value.asSequence().flatMap { it.operations.asSequence() }
        .firstOrNull { it.displayLabel == event.toolDisplayName }
    val localizedToolName = connectorOperation?.let { operation ->
        if (operation.displayLabelResourceId != 0) context.getString(operation.displayLabelResourceId) else operation.displayLabel
    }
    val localizedToolStatus = when {
        event.stage == "tool_progress" -> event.toolDisplayName ?: event.text
        event.stage == "tool_interrupted" -> context.getString(R.string.connector_tool_unconfirmed,
            localizedToolName ?: event.toolDisplayName ?: event.text)
        event.stage == "tool_not_started" -> context.getString(R.string.connector_tool_not_started,
            localizedToolName ?: event.toolDisplayName ?: event.text)
        WebSearchTools.isSearchLabel(event.toolDisplayName) && event.stage == "tool_error" ->
            context.getString(R.string.web_search_event_failed, searchQuery)
        WebSearchTools.isSearchLabel(event.toolDisplayName) -> context.getString(R.string.web_search_event, searchQuery)
        WebSearchTools.isFetchLabel(event.toolDisplayName) -> context.getString(R.string.web_fetch_event,
            event.toolDisplayName.orEmpty().substringAfter(" · "))
        localizedToolName == null && event.toolStatusResourceId != null ->
            context.getString(event.toolStatusResourceId, event.toolDisplayName ?: event.text)
        localizedToolName == null -> event.text
        event.stage == "approval_waiting" -> context.getString(R.string.connector_tool_waiting_for_approval, localizedToolName)
        event.stage == "tool_call" -> context.getString(R.string.connector_tool_using, localizedToolName)
        event.stage == "tool_error" -> context.getString(R.string.connector_tool_failed, localizedToolName)
        event.stage == "tool_result" -> context.getString(R.string.connector_tool_used, localizedToolName)
        else -> event.text
    }
    val inlineLinuxFailureDetail = event.detail?.takeIf {
        event.stage == "tool_error" && event.toolDisplayName?.contains("linux", ignoreCase = true) == true
            && it.contains("phase=")
    }?.removePrefix("Tool error: ")
    var expanded by remember(event.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("conversation-activity-${event.id}")
        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(LucideIcons.Sparkles, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp))
                Text(localizedToolStatus, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (inlineLinuxFailureDetail == null && !event.detail.isNullOrBlank()) IconButton(onClick = { expanded = !expanded },
                    modifier = Modifier.size(44.dp).testTag("tool-output-toggle-${event.toolCallId ?: event.id}")) {
                    Icon(if (expanded) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                        contentDescription = stringResource(if (expanded) R.string.tool_output_collapse else R.string.tool_output_expand),
                        modifier = Modifier.size(17.dp))
                }
            }
            event.toolAuditDetail?.takeIf(String::isNotBlank)?.let { literalDetail ->
                SelectionContainer {
                    Text(literalDetail, Modifier.fillMaxWidth().padding(start = 26.dp, top = 6.dp).testTag("tool-audit-${event.id}"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            inlineLinuxFailureDetail?.let { detail ->
                SelectionContainer {
                    Text(detail, Modifier.fillMaxWidth().padding(start = 26.dp, top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            AnimatedVisibility(expanded && inlineLinuxFailureDetail == null && !event.detail.isNullOrBlank(),
                enter = if (reducedMotion) EnterTransition.None else
                    expandVertically(JarvysMotion.expand()) + fadeIn(JarvysMotion.feedback()),
                exit = if (reducedMotion) ExitTransition.None else
                    shrinkVertically(JarvysMotion.expand()) + fadeOut(JarvysMotion.feedback())) {
                if (WebSearchTools.isSearchLabel(event.toolDisplayName)) WebSearchResultsContent(event.detail.orEmpty(), context)
                else AssistantMarkdown(event.detail.orEmpty(), Modifier.fillMaxWidth().padding(start = 26.dp, top = 8.dp),
                    onOpenSkillFile)
            }
            event.previewId?.let { preview ->
                TextButton(onClick = { onOpenPreview(preview) }, modifier = Modifier.align(Alignment.End)
                    .testTag("tool-preview-${event.toolCallId ?: event.id}")) {
                    Text(stringResource(if (event.previewIsCurrent) R.string.chat_open_current_preview else R.string.chat_open_preview))
                }
            }
    }
}

@Composable
private fun WebSearchResultsContent(detail: String, context: android.content.Context) {
    val rows = remember(detail) { runCatching { JSONObject(detail).optJSONArray("items") }.getOrNull() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.web_search_results_title), fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge)
        if (rows == null || rows.length() == 0) {
            val failure = remember(detail) { WebSearchFailurePresentation.parse(detail) }
            val resource = failure?.messageResourceId() ?: R.string.web_search_no_results
            Text(if (failure?.kind == WebSearchFailureKind.HTTP) stringResource(resource, failure.httpStatus.orEmpty())
                else stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        } else for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val title = row.optString("title").take(300)
            val url = WebSearchUrlPolicy.decodeBingRedirect(row.optString("url"))
            val snippet = row.optString("text").take(800)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (url != null) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { Toast.makeText(context, R.string.web_search_open_link_failed, Toast.LENGTH_SHORT).show() }
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 2.dp)) {
                    Text("${row.optInt("index", index + 1)}. $title", modifier = Modifier.fillMaxWidth(),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else Text("${row.optInt("index", index + 1)}. $title", fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(url.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (snippet.isNotBlank()) Text(snippet, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResultNote(event: AgentRunUiEvent, onOpenSkillFile: (SkillFileLink) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("conversation-result-${event.id}")
        .padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        event.stage?.let { JarvysSectionLabel(it) }
        AssistantMarkdown(event.text, onOpenSkillFile = onOpenSkillFile)
    }
}

@Composable
private fun TranslationNote(event: AgentRunUiEvent, onOpenSkillFile: (SkillFileLink) -> Unit,
                            onHide: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(event.stage.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
            ReplyAction(LucideIcons.Ellipsis, stringResource(R.string.chat_footer_hide_translation)) {
                onHide(event.detail.orEmpty())
            }
        }
        SelectionContainer {
            AssistantMarkdown(event.text, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), onOpenSkillFile)
        }
    }
}

@Composable
private fun MemoryRevisionLine(event: AgentRunUiEvent, onUndo: (Long) -> Unit,
                               onUndoReflection: (String) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    var expanded by remember(event.id) { mutableStateOf(false) }
    val reflectionId = event.memoryReflectionGroupId
    val undone = reflectionId != null && event.detail == "undone"
    JarvysGroup(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LucideIcons.Brain, contentDescription = null, tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp))
                Text(when {
                    undone -> stringResource(R.string.reflection_undone)
                    reflectionId != null && event.detail == "partial" -> stringResource(R.string.reflection_partial_learned, event.text)
                    reflectionId != null -> stringResource(R.string.reflection_learned, event.text)
                    else -> stringResource(R.string.memory_chat_updated, event.stage.orEmpty())
                }, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                JarvysTag(if (expanded) stringResource(R.string.tool_output_collapse) else stringResource(R.string.tool_output_expand))
            }
            if (reflectionId == null) Text(event.memoryPath ?: event.text, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            AnimatedVisibility(expanded,
                enter = if (reducedMotion) EnterTransition.None else expandVertically(JarvysMotion.expand()) + fadeIn(JarvysMotion.feedback()),
                exit = if (reducedMotion) ExitTransition.None else shrinkVertically(JarvysMotion.expand()) + fadeOut(JarvysMotion.feedback())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    event.memoryBefore?.let { Text("${stringResource(R.string.memory_before)}\n${it.ifBlank { stringResource(R.string.memory_empty_version) }}") }
                    event.memoryAfter?.let { Text("${stringResource(R.string.memory_after)}\n${it.ifBlank { stringResource(R.string.memory_empty_version) }}") }
                    if (reflectionId != null && !undone) TextButton(onClick = { onUndoReflection(reflectionId) }) {
                        Text(stringResource(R.string.memory_chat_undo))
                    } else event.memoryRevisionId?.let { id ->
                        TextButton(onClick = { onUndo(id) }) { Text(stringResource(R.string.memory_chat_undo)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactionLine(event: AgentRunUiEvent) {
    var show by remember(event.id) { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = Modifier.testTag("compaction-row-${event.id}")) {
        Icon(LucideIcons.History, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.compaction_row, event.compactionMessageCount))
    }
    if (show) AlertDialog(onDismissRequest = { show = false },
        title = { Text(stringResource(R.string.compaction_details_title)) },
        text = { ScrollableDialogContent { Text(event.text) } },
        confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun UserDecisionEventCard(
    event: AgentRunUiEvent,
    onResolve: (String, UserDecisionResult) -> Boolean = UserDecisionRequests::resolve,
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val decisionId = event.decisionId.orEmpty()
    val pending = event.decisionStatus == "PENDING"
    val optionLabel = event.decisionOptionLabel.orEmpty()
    val resolvedStatus = when (event.decisionStatus) {
        "SELECTED" -> context.getString(R.string.user_decision_selected, optionLabel)
        "DISMISSED" -> context.getString(R.string.user_decision_dismissed)
        "UNANSWERED", "CANCELLED", "UNAVAILABLE" -> context.getString(R.string.user_decision_unanswered)
        else -> context.getString(R.string.user_decision_unanswered)
    }
    var expanded by remember(event.id) { mutableStateOf(false) }
    JarvysGroup(Modifier.fillMaxWidth().testTag("user-decision-card-$decisionId"),
        containerColor = if (pending) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
            else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.46f)) {
        AnimatedContent(targetState = pending, transitionSpec = {
            if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
            else expandVertically(JarvysMotion.expand()) + fadeIn(JarvysMotion.feedback()) togetherWith
                shrinkVertically(JarvysMotion.expand()) + fadeOut(JarvysMotion.feedback())
        }, label = "user-decision-resolution") { isPending ->
            if (isPending) Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                JarvysTag(stringResource(R.string.user_decision_heading))
                Text(event.text, color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                event.decisionBody?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                event.decisionOptions.forEach { option ->
                    val roleDescription = when (option.role) {
                        UserDecisionRole.PRIMARY -> context.getString(R.string.user_decision_recommended)
                        UserDecisionRole.DESTRUCTIVE -> context.getString(R.string.user_decision_destructive)
                        UserDecisionRole.DEFAULT -> null
                    }
                    val accessible = listOfNotNull(option.label, roleDescription,
                        option.description.takeIf(String::isNotBlank)).joinToString(". ")
                    val modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = accessible
                        role = Role.Button
                    }.testTag("user-decision-option-$decisionId-${option.id}")
                    when (option.role) {
                        UserDecisionRole.PRIMARY -> Button(
                            onClick = { onResolve(decisionId, UserDecisionResult.Selected(option)) },
                            modifier = modifier,
                        ) { Text(option.label, softWrap = true) }
                        UserDecisionRole.DESTRUCTIVE -> OutlinedButton(
                            onClick = { onResolve(decisionId, UserDecisionResult.Selected(option)) },
                            modifier = modifier,
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(option.label, softWrap = true) }
                        UserDecisionRole.DEFAULT -> OutlinedButton(
                            onClick = { onResolve(decisionId, UserDecisionResult.Selected(option)) },
                            modifier = modifier,
                        ) { Text(option.label, softWrap = true) }
                    }
                    if (option.description.isNotBlank()) Text(option.description,
                        Modifier.fillMaxWidth().padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (event.decisionAllowDismiss) TextButton(
                    onClick = { onResolve(decisionId, UserDecisionResult.Dismissed) },
                    modifier = Modifier.fillMaxWidth().testTag("user-decision-dismiss-$decisionId")) {
                    Text(stringResource(R.string.user_decision_dismiss))
                }
            } else {
                val selected = event.decisionStatus == "SELECTED"
                val resultLabel = if (selected && optionLabel.isNotBlank()) optionLabel else resolvedStatus
                val statusIcon = when (event.decisionStatus) {
                    "SELECTED" -> LucideIcons.CircleCheck
                    "DISMISSED" -> LucideIcons.CircleX
                    else -> LucideIcons.Clock
                }
                val expandLabel = stringResource(if (expanded) R.string.user_decision_collapse else R.string.user_decision_expand)
                val expansionState = stringResource(if (expanded) R.string.user_decision_expanded else R.string.user_decision_collapsed)
                Column(Modifier.fillMaxWidth().animateContentSize(
                    if (reducedMotion) androidx.compose.animation.core.snap() else JarvysMotion.expand())) {
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = expandLabel) { expanded = !expanded }
                        .semantics(mergeDescendants = true) {
                            contentDescription = context.getString(R.string.user_decision_resolved_accessibility,
                                resultLabel, event.text)
                            stateDescription = resolvedStatus
                            role = Role.Button
                            stateDescription = "$resolvedStatus, $expansionState"
                        }.testTag("user-decision-resolved-$decisionId").padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(statusIcon, contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(resultLabel, Modifier.fillMaxWidth().testTag("user-decision-resolution-$decisionId"),
                                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(event.text, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        JarvysTag(expandLabel)
                    }
                    AnimatedVisibility(expanded,
                        enter = if (reducedMotion) EnterTransition.None else expandVertically(JarvysMotion.expand()) + fadeIn(JarvysMotion.feedback()),
                        exit = if (reducedMotion) ExitTransition.None else shrinkVertically(JarvysMotion.expand()) + fadeOut(JarvysMotion.feedback())) {
                        event.decisionBody?.takeIf(String::isNotBlank)?.let { body ->
                            Text(body, Modifier.fillMaxWidth().padding(start = 42.dp, end = 16.dp, bottom = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalDecisionCard(event: AgentRunUiEvent, connectorRegistry: ConnectorRegistry) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val permission = event.approvalPermission
    val pending = event.approvalStatus == "PENDING"
    var expanded by remember(event.approvalId, event.approvalStatus) { mutableStateOf(false) }
    val title = event.approvalLocalizedTitle?.resolve(context) ?: event.text
    val lines = event.approvalLocalizedLines?.map { it.resolve(context) } ?: event.approvalLines
    val decisionStatus = approvalStatusPresentation(event.approvalStatus.orEmpty())
    var permissionDecision by remember(event.approvalId) { mutableStateOf(ApprovalDecision.APPROVED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ApprovalGate.INSTANCE.resolveFromUi(event.approvalId.orEmpty()) {
            if (granted) permissionDecision
            else if (event.approvalPermissionDeniedIntent != null) {
                if (runCatching { context.startActivity(buildApprovalIntent(event.approvalPermissionDeniedIntent)) }.isSuccess)
                    ApprovalDecision.PERMISSION_FALLBACK_LAUNCHED else ApprovalDecision.ACTION_FAILED
            } else ApprovalDecision.PERMISSION_DENIED
        }
    }
    fun approve(decisionProvider: () -> ApprovalDecision = { ApprovalDecision.APPROVED }) {
        if (permission != null && androidx.core.content.ContextCompat.checkSelfPermission(context, permission)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching {
                ApprovalGate.INSTANCE.beginUiAction(event.approvalId.orEmpty()) {
                    permissionDecision = decisionProvider()
                    permissionLauncher.launch(permission)
                }
            }.onFailure { ApprovalGate.INSTANCE.resolve(event.approvalId.orEmpty(), ApprovalDecision.ACTION_FAILED) }
        } else ApprovalGate.INSTANCE.resolveFromUi(event.approvalId.orEmpty()) {
            val decision = decisionProvider()
            event.approvalIntent?.let { spec ->
                if (runCatching { context.startActivity(buildApprovalIntent(spec)) }.isSuccess) decision
                else ApprovalDecision.ACTION_FAILED
            } ?: decision
        }
    }
    JarvysGroup(
        Modifier.fillMaxWidth().then(if (pending) Modifier else Modifier.clickable { expanded = !expanded })
            .testTag("approval-card-${event.approvalId}"),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.46f),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            event.approvalRequester?.let { CrewApprovalAttribution(it, event.approvalRequesterColorKey) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(when (decisionStatus.tone) {
                    ApprovalOutcomeTone.SUCCESS -> LucideIcons.CircleCheck
                    ApprovalOutcomeTone.FAILURE -> LucideIcons.CircleX
                    ApprovalOutcomeTone.WARNING -> LucideIcons.Clock
                    ApprovalOutcomeTone.NEUTRAL -> LucideIcons.Circle
                }, contentDescription = null, tint = approvalToneColor(decisionStatus.tone), modifier = Modifier.size(18.dp))
                Text(context.getString(if (pending) R.string.approval_required else decisionStatus.labelResourceId),
                    Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall)
                if (!pending) JarvysTag(if (expanded) stringResource(R.string.tool_output_collapse)
                    else stringResource(R.string.tool_output_expand))
            }
            AnimatedVisibility(pending || expanded,
                enter = if (reducedMotion) EnterTransition.None else expandVertically(JarvysMotion.expand()) + fadeIn(JarvysMotion.feedback()),
                exit = if (reducedMotion) ExitTransition.None else shrinkVertically(JarvysMotion.expand()) + fadeOut(JarvysMotion.feedback())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium) }
                    if (permission != null) Text(context.getString(R.string.approval_permission_explanation,
                        event.approvalPermissionLabel?.resolve(context) ?: permission),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (pending) {
                        Button(onClick = { approve() }, modifier = Modifier.fillMaxWidth()) {
                            val label = approvalPrimaryActionResourceId(permissionRequired = permission != null)
                            Text(if (permission == null) context.getString(label) else context.getString(label,
                                event.approvalPermissionLabel?.resolve(context) ?: permission), softWrap = true)
                        }
                        if (event.approvalAllowAlwaysAvailable && FlavorAutonomyUi.handlesApproval(event)) {
                            FlavorAutonomyUi.ApprovalAction(event) { detail -> AgentRunUiState.updateApprovalDetail(event.approvalId.orEmpty(), detail) }
                        } else if (event.approvalAllowAlwaysAvailable) OutlinedButton(onClick = {
                            approve {
                                var decision = ApprovalDecision.APPROVED
                                approveAndAllowAlways(
                                    registry = connectorRegistry,
                                    connectorId = event.approvalAutonomyConnectorId,
                                    operationName = event.approvalAutonomyOperationName,
                                    onStatusDetail = { detail -> AgentRunUiState.updateApprovalDetail(event.approvalId.orEmpty(), detail) },
                                    onResolve = { decision = it },
                                )
                                decision
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.approval_approve_allow_always), softWrap = true) }
                        TextButton(onClick = { ApprovalGate.INSTANCE.resolve(event.approvalId.orEmpty(), ApprovalDecision.DENIED) },
                            modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.approval_reject)) }
                    } else {
                        (event.approvalStatusDetailText?.resolve(context) ?: event.approvalStatusDetail)?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun approvalToneColor(tone: ApprovalOutcomeTone): Color = when (tone) {
    ApprovalOutcomeTone.SUCCESS -> MaterialTheme.colorScheme.primary
    ApprovalOutcomeTone.FAILURE -> MaterialTheme.colorScheme.error
    ApprovalOutcomeTone.WARNING -> MaterialTheme.colorScheme.tertiary
    ApprovalOutcomeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}
