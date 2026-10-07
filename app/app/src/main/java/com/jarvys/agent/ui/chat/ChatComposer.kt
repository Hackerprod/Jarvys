package com.jarvys.agent.ui.chat

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
import androidx.compose.material3.FilterChip
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelectModel, enabled = !running,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 44.dp).weight(1f, fill = false)) {
                    Icon(LucideIcons.Boxes,
                        contentDescription = stringResource(R.string.chat_select_model_accessibility, modelLabel),
                        modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { trayOpen = !trayOpen }, enabled = !running,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = trayToggleDescription
                    }) {
                    Icon(LucideIcons.Plus, contentDescription = null,
                        modifier = Modifier.size(21.dp).graphicsLayer { rotationZ = expandedPlus.value },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { if (running) onStop() else onSend() },
                    enabled = running || goal.isNotBlank(), shape = CircleShape,
                    modifier = Modifier.size(48.dp).testTag("chat-send-stop-button"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    AnimatedContent(targetState = running, transitionSpec = {
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
        crewMode = crewMode,
        onCamera = { trayOpen = false; unavailableAction = R.string.chat_camera_unavailable },
        onPhotos = { trayOpen = false; unavailableAction = R.string.chat_photos_unavailable },
        onUpload = { trayOpen = false; onImportSkill() },
        onSkills = { trayOpen = false; skillsOpen = true },
        onInstructionInjection = { trayOpen = false; unavailableAction = R.string.chat_instructions_unavailable },
        onContextManagement = { onCaptureContext(); trayOpen = false },
        onCrewModeChange = onCrewModeChange,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ContextTray(
    skillEnabledCount: Int,
    skillTotalCount: Int,
    crewMode: CrewMode,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onUpload: () -> Unit,
    onSkills: () -> Unit,
    onInstructionInjection: () -> Unit,
    onContextManagement: () -> Unit,
    onCrewModeChange: (CrewMode) -> Unit,
    onOpenCrew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_tray_title), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.chat_tray_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.X, contentDescription = stringResource(R.string.chat_close_tray))
                }
            }
            JarvysSectionLabel(stringResource(R.string.chat_tray_context_heading))
            TrayActionRow(LucideIcons.Eye, stringResource(R.string.chat_tray_capture_screen),
                stringResource(R.string.chat_tray_capture_detail), onContextManagement)
            JarvysSectionLabel(stringResource(R.string.chat_tray_capabilities_heading))
            TrayActionRow(LucideIcons.WandSparkles, stringResource(R.string.chat_tray_skills),
                stringResource(R.string.chat_tray_skills_detail, skillEnabledCount, skillTotalCount), onSkills)
            TrayActionRow(LucideIcons.Layers2, stringResource(R.string.chat_tray_instruction_injection),
                stringResource(R.string.chat_tray_not_configured), onInstructionInjection)
            CrewModeControl(crewMode, onCrewModeChange, onOpenCrew)
            JarvysSectionLabel(stringResource(R.string.chat_tray_attach_heading))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrayTile(LucideIcons.Camera, stringResource(R.string.chat_tray_camera), onCamera)
                TrayTile(LucideIcons.Image, stringResource(R.string.chat_tray_photos), onPhotos)
                TrayTile(LucideIcons.FileUp, stringResource(R.string.chat_tray_upload_skill), onUpload)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CrewModeControl(mode: CrewMode, onChange: (CrewMode) -> Unit, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.crew_mode_title), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onOpen) { Text(stringResource(R.string.crew_open_workspace)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(CrewMode.OFF to R.string.crew_mode_off, CrewMode.AUTO to R.string.crew_mode_auto,
                CrewMode.ALWAYS to R.string.crew_mode_always).forEach { (option, label) ->
                FilterChip(selected = mode == option, onClick = { onChange(option) },
                    label = { Text(stringResource(label), maxLines = 1) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
private fun TrayActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String,
                          detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)).clickable(onClick = onClick)
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
private fun TrayTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(Modifier.size(width = 100.dp, height = 76.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
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
