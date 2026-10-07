package com.jarvys.agent.ui.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.jarvys.agent.ui.shell.ModelEffortAppearance
import com.jarvys.agent.ui.shell.ModelEffortBackground
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.skills.SkillEntry

@Composable
fun ChatComposer(
    goal: String,
    onGoalChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: () -> Unit,
    modelLabel: String,
    running: Boolean,
    captureContextRequested: Boolean,
    onCaptureContext: () -> Unit,
    onImportSkill: () -> Unit,
    availableSkills: List<SkillEntry>,
    selectedSkillIds: Set<String>,
    onToggleRunSkill: (String) -> Unit,
    onManageSkills: () -> Unit,
    skillEnabledCount: Int,
    skillTotalCount: Int,
    crewMode: CrewMode,
    onCrewModeChange: (CrewMode) -> Unit,
    onOpenCrew: () -> Unit,
    pendingAttachments: List<PendingChatAttachment> = emptyList(),
    attachmentSessionId: String = "",
    attachmentSending: Boolean = false,
    onRemoveAttachment: (String) -> Unit = {},
    onAttachmentCamera: () -> Unit = {},
    onAttachmentPhotos: () -> Unit = {},
    onAttachmentFiles: () -> Unit = {},
    effortLabel: String = "",
    effortAppearance: ModelEffortAppearance? = null,
    effortMotionActive: Boolean = true,
) {
    var trayOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    var unavailableAction by remember { mutableStateOf<Int?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val reducedMotion = LocalReducedMotion.current
    val composerSizeMotion = if (reducedMotion) Modifier else Modifier.animateContentSize(JarvysMotion.expand())
    val expandedPlus = animateFloatAsState(if (trayOpen) 45f else 0f,
        animationSpec = if (reducedMotion) snap() else JarvysMotion.feedback(), label = "tray-mark")
    val trayToggleDescription = stringResource(if (trayOpen) R.string.chat_close_tray else R.string.chat_open_tray)
    val stopDescription = stringResource(R.string.chat_stop_run)

    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            .then(composerSizeMotion),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = FLOATING_COMPOSER_SURFACE_ALPHA),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 9.dp, end = 8.dp, bottom = 7.dp)) {
            if (pendingAttachments.isNotEmpty()) PendingChatAttachments(pendingAttachments, attachmentSessionId, onRemoveAttachment, Modifier.padding(bottom = 7.dp))
            if (captureContextRequested) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 3.dp)) {
                    Icon(LucideIcons.Eye, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_current_screen_capture),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Box {
                TextField(
                    value = goal,
                    onValueChange = onGoalChange,
                    modifier = Modifier.fillMaxWidth().heightIn(max = configuration.screenHeightDp.dp * 0.42f),
                    placeholder = { Text(stringResource(R.string.chat_message_placeholder)) },
                    minLines = 1,
                    maxLines = if (expanded) 22 else 5,
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                if (goal.count { it == '\n' } >= 2) IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                ) {
                    Icon(if (expanded) LucideIcons.ChevronDown else LucideIcons.ChevronUp,
                        contentDescription = stringResource(if (expanded) R.string.chat_composer_collapse else R.string.chat_composer_expand),
                        modifier = Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { trayOpen = !trayOpen }, enabled = !running && !attachmentSending,
                    modifier = Modifier.size(48.dp).testTag("chat-plus-button").semantics {
                        contentDescription = trayToggleDescription
                    }) {
                    Box(Modifier.size(40.dp).testTag("chat-plus-visual").clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (running || attachmentSending) 0.38f else 0.72f)),
                        contentAlignment = Alignment.Center) {
                        Icon(LucideIcons.Plus, contentDescription = null,
                            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = expandedPlus.value },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (running || attachmentSending) 0.38f else 1f))
                    }
                }
                ComposerModelPill(
                    modelLabel = modelLabel,
                    effortLabel = effortLabel,
                    effortAppearance = effortAppearance,
                    enabled = !running && !attachmentSending,
                    motionActive = effortMotionActive && !trayOpen && !skillsOpen && unavailableAction == null,
                    onClick = onSelectModel,
                    modifier = Modifier.weight(1f),
                )
                val microphoneDescription = stringResource(R.string.chat_microphone_unavailable)
                Box(Modifier.size(48.dp).testTag("chat-microphone-button").semantics {
                    contentDescription = microphoneDescription
                    role = Role.Button
                    disabled()
                }, contentAlignment = Alignment.Center) {
                    Icon(LucideIcons.Mic, contentDescription = null, modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
                Button(onClick = { if (running || attachmentSending) onStop() else onSend() },
                    enabled = running || attachmentSending || canSendWithAttachments(goal, pendingAttachments), shape = CircleShape,
                    modifier = Modifier.size(48.dp).testTag("chat-send-stop-button"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    AnimatedContent(targetState = running || attachmentSending, transitionSpec = {
                        if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                        else fadeIn(JarvysMotion.feedback()) togetherWith fadeOut(JarvysMotion.feedback())
                    }, label = "send-stop-control") { isActive ->
                        if (isActive) Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onPrimary).testTag("chat-stop-run-glyph")
                            .semantics { contentDescription = stopDescription })
                        else Icon(LucideIcons.ArrowUp,
                            contentDescription = stringResource(R.string.chat_send_message), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (trayOpen) ContextTray(
        skillEnabledCount = skillEnabledCount,
        skillTotalCount = skillTotalCount,
        onCamera = { trayOpen = false; onAttachmentCamera() },
        onPhotos = { trayOpen = false; onAttachmentPhotos() },
        onFiles = { trayOpen = false; onAttachmentFiles() },
        onSkills = { trayOpen = false; skillsOpen = true },
        onContextManagement = { onCaptureContext(); trayOpen = false },
        onOpenCrew = { trayOpen = false; onOpenCrew() },
        onDismiss = { trayOpen = false },
    )
    if (skillsOpen) RunCapabilityPicker(
        skills = availableSkills,
        selected = selectedSkillIds,
        onToggle = onToggleRunSkill,
        onManage = { skillsOpen = false; onManageSkills() },
        onImport = { skillsOpen = false; onImportSkill() },
        onDismiss = { skillsOpen = false },
    )
    unavailableAction?.let { explanation ->
        AlertDialog(
            onDismissRequest = { unavailableAction = null },
            title = { Text(stringResource(R.string.chat_capability_not_available)) },
            text = { ScrollableDialogContent { Text(stringResource(explanation)) } },
            confirmButton = { TextButton(onClick = { unavailableAction = null }) { Text(stringResource(R.string.chat_ok)) } },
        )
    }
}

private const val FLOATING_COMPOSER_SURFACE_ALPHA = 0.94f

@Composable
private fun ComposerModelPill(
    modelLabel: String,
    effortLabel: String,
    effortAppearance: ModelEffortAppearance?,
    enabled: Boolean,
    motionActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fullLabel = if (effortLabel.isBlank()) modelLabel else "$modelLabel · $effortLabel"
    val description = stringResource(R.string.chat_select_model_accessibility, fullLabel)
    val labelStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).testTag("chat-model-button")
            .semantics { contentDescription = description },
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelLayout = textMeasurer.measure(
                modelLabel, style = labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (constraints.maxWidth - with(density) { 12.dp.roundToPx() } * 2).coerceAtLeast(0)),
            )
            val paddingPixels = with(density) {
                if (labelLayout.lineCount == 1) (40.dp.roundToPx() - labelLayout.size.height)
                    .coerceIn(4.dp.roundToPx() * 2, 10.dp.roundToPx() * 2)
                else 10.dp.roundToPx() * 2
            }
            val topPadding = with(density) { (paddingPixels / 2).toDp() }
            val bottomPadding = with(density) { (paddingPixels - paddingPixels / 2).toDp() }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
                    .testTag("chat-model-visual"),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (effortAppearance != null) ModelEffortBackground(
                    effortAppearance, active = enabled && motionActive,
                    modifier = Modifier.matchParentSize().testTag("chat-model-effort-background"),
                    verticalTextPadding = topPadding,
                )
                Text(modelLabel,
                    modifier = Modifier.padding(start = 12.dp, top = topPadding, end = 12.dp, bottom = bottomPadding)
                        .testTag("chat-model-label"),
                    color = if (effortAppearance != null) Color.White else LocalContentColor.current,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = labelStyle)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextTray(
    skillEnabledCount: Int,
    skillTotalCount: Int,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onSkills: () -> Unit,
    onContextManagement: () -> Unit,
    onOpenCrew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        ContextTrayContent(skillEnabledCount, skillTotalCount, onCamera, onPhotos, onFiles,
            onSkills, onContextManagement, onOpenCrew, onDismiss)
    }
}

@Composable
internal fun ContextTrayContent(
    skillEnabledCount: Int,
    skillTotalCount: Int,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onSkills: () -> Unit,
    onContextManagement: () -> Unit,
    onOpenCrew: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
        .padding(horizontal = 20.dp, vertical = 8.dp).testTag("chat-context-tray"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_tray_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(LucideIcons.X, contentDescription = stringResource(R.string.chat_close_tray))
            }
        }
        JarvysSectionLabel(stringResource(R.string.chat_tray_attach_heading))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayTile(LucideIcons.Camera, stringResource(R.string.chat_tray_camera), onCamera, Modifier.weight(1f), "camera")
            TrayTile(LucideIcons.Image, stringResource(R.string.chat_tray_photos), onPhotos, Modifier.weight(1f), "photos")
            TrayTile(LucideIcons.FileUp, stringResource(R.string.chat_tray_files), onFiles, Modifier.weight(1f), "files")
        }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
            Column {
                TrayActionRow(LucideIcons.Eye, stringResource(R.string.chat_tray_capture_screen),
                    stringResource(R.string.chat_tray_capture_detail), onContextManagement)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TrayActionRow(LucideIcons.WandSparkles, stringResource(R.string.chat_tray_skills),
                    stringResource(R.string.chat_tray_skills_detail, skillEnabledCount, skillTotalCount), onSkills)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TrayActionRow(LucideIcons.Boxes, stringResource(R.string.crew_mode_title),
                    stringResource(R.string.crew_open_workspace), onOpenCrew)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TrayActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String,
                          detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = onClick)
        .padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(LucideIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TrayTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
                     onClick: () -> Unit, modifier: Modifier, key: String) {
    val height = if (LocalDensity.current.fontScale >= 1.5f) 108.dp else 88.dp
    Surface(modifier.height(height).testTag("attach-tile-$key").clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp).testTag("attach-icon-$key"))
            Spacer(Modifier.height(6.dp))
            Text(label, Modifier.testTag("attach-label-$key"), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RunCapabilityPicker(
    skills: List<SkillEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onManage: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.84f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_run_skills_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.chat_run_skills_count,
                selected.count { id -> skills.any { it.metadata.id == id } }, skills.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (skills.isEmpty()) Text(stringResource(R.string.chat_run_skills_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(skills, key = { it.metadata.id }) { skill ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable { onToggle(skill.metadata.id) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(skill.metadata.name, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text(skill.metadata.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = skill.metadata.id in selected,
                            onCheckedChange = { onToggle(skill.metadata.id) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.skills_import))
                }
                Button(onClick = onManage, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.chat_manage_skills))
                }
            }
        }
    }
}
