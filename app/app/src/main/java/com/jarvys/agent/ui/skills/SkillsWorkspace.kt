package com.jarvys.agent.ui.skills

import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.JarvysSheetDragHandle
import com.jarvys.agent.jarvysSheetOptionRow
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.jarvys.agent.skills.*
import com.jarvys.agent.ui.motion.LocalReducedMotion

@Composable
fun SkillsCatalog(repository: SkillRepository) {
    val skills by repository.skills.collectAsState()
    var deleteTarget by remember { mutableStateOf<SkillEntry?>(null) }
    var query by remember { mutableStateOf("") }
    val filteredSkills = skills.filter {
        query.isBlank() || it.metadata.name.contains(query.trim(), true) || it.metadata.description.contains(query.trim(), true)
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        if (skills.size > 8) {
            JarvysTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                label = { Text(stringResource(R.string.skills_search_hint)) },
                leadingIcon = { Icon(LucideIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                placeholder = { Text(stringResource(R.string.skills_search_hint)) },
                singleLine = true,
            )
        }
        if (skills.isEmpty()) {
            SkillCardSurface {
                Text(stringResource(R.string.skills_empty_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.skills_empty_import_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        if (skills.isNotEmpty() && filteredSkills.isEmpty()) {
            Text(stringResource(R.string.skills_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        if (filteredSkills.isNotEmpty()) {
            JarvysGroup(contentPadding = PaddingValues(vertical = 4.dp)) {
                Column {
                    filteredSkills.forEachIndexed { index, skill ->
                        if (index > 0) HorizontalDivider(
                            modifier = Modifier.padding(start = 54.dp, end = 12.dp),
                            thickness = 0.6.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                        )
                        CapabilityLineItem(
                            skill = skill,
                            onEnabled = { enabled -> repository.setEnabled(skill.metadata.id, enabled) },
                            onDelete = { deleteTarget = skill },
                        )
                    }
                }
            }
        }
    }
    deleteTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.skills_delete_body, skill.metadata.name)) } },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteImported(skill.metadata.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.skills_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.skills_cancel)) } },
        )
    }
}

@Composable
private fun CapabilityLineItem(skill: SkillEntry, onEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    var expanded by remember(skill.metadata.id) { mutableStateOf(false) }
    val sizeMotion = if (LocalReducedMotion.current) Modifier else Modifier.animateContentSize(JarvysMotion.expand())
    Column(modifier = Modifier.fillMaxWidth().then(sizeMotion)) {
        val usage = androidx.compose.ui.res.pluralStringResource(R.plurals.skills_used_times, skill.usageCount, skill.usageCount)
        val summary = listOf(skill.metadata.description, usage).filter(String::isNotBlank).joinToString(" · ")
        JarvysListRow(
            title = skill.metadata.name,
            subtitle = summary,
            icon = LucideIcons.WandSparkles,
            onClick = { expanded = !expanded },
            trailing = { Switch(checked = skill.enabled,
                enabled = skill.validationError == null || skill.enabled, onCheckedChange = onEnabled) },
        )
        skill.validationError?.let { Text(it, modifier = Modifier.padding(start = 72.dp, end = 12.dp), color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        if (expanded) {
            Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(skill.metadata.id, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            if (skill.metadata.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    skill.metadata.tags.forEach { tag -> JarvysTag(tag) }
                }
            }
            if (skill.metadata.allowedTools.isNotEmpty()) {
                Text(stringResource(R.string.skills_allowed_tools_metadata, skill.metadata.allowedTools.joinToString()), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            } else {
                Text(stringResource(R.string.skills_no_allowed_tools_metadata), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(stringResource(R.string.skills_metadata_explanation),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(
                text = skill.body,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp),
            )
            if (skill.source == SkillSource.IMPORTED) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.skills_delete_imported)) }
            }
        }
    }
}

enum class SkillImportEntryKind { MARKDOWN, GITHUB }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillSourceSheet(
    onDismiss: () -> Unit,
    onPasteMarkdown: () -> Unit,
    onFromFile: () -> Unit,
    onFromGitHub: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun select(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { BottomSheetDefaults.windowInsets },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 16.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.align(Alignment.CenterHorizontally)) { JarvysSheetDragHandle() }
            Spacer(Modifier.height(6.dp))
            SkillSourceOption(LucideIcons.ClipboardPaste, stringResource(R.string.skills_import_paste_markdown)) { select(onPasteMarkdown) }
            SkillSourceOption(LucideIcons.FileUp, stringResource(R.string.skills_import_file)) { select(onFromFile) }
            SkillSourceOption(LucideIcons.GitFork, stringResource(R.string.skills_import_github)) { select(onFromGitHub) }
        }
    }
}

@Composable
private fun SkillSourceOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.jarvysSheetOptionRow(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SkillDraftDialog(
    kind: SkillImportEntryKind,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var value by remember(kind) { mutableStateOf("") }
    val isMarkdown = kind == SkillImportEntryKind.MARKDOWN
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isMarkdown) R.string.skills_import_paste_markdown else R.string.skills_import_github)) },
        text = {
            ScrollableDialogContent {
                JarvysTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (isMarkdown) R.string.skills_import_markdown_label else R.string.skills_import_github_label)) },
                    placeholder = {
                        Text(stringResource(if (isMarkdown) R.string.skills_import_markdown_placeholder else R.string.skills_import_github_placeholder))
                    },
                    singleLine = !isMarkdown,
                    minLines = if (isMarkdown) 8 else 1,
                    maxLines = if (isMarkdown) 16 else 1,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onImport(value); onDismiss() }) {
                Text(stringResource(R.string.skills_import))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.skills_cancel)) } },
    )
}

@Composable
private fun SkillCardSurface(content: @Composable () -> Unit) {
    val sizeMotion = if (LocalReducedMotion.current) Modifier else Modifier.animateContentSize(JarvysMotion.expand())
    JarvysGroup(contentPadding = PaddingValues(JarvysUiTokens.ScreenPadding),
        modifier = sizeMotion) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
