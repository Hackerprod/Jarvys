package com.jarvys.agent.skills

import androidx.compose.runtime.Composable
import com.jarvys.agent.ui.skills.SkillDraftDialog
import com.jarvys.agent.ui.skills.SkillSourceSheet
import com.jarvys.agent.ui.skills.SkillsCatalog
import com.jarvys.agent.ui.skills.SkillImportEntryKind as WorkspaceSkillImportEntryKind

typealias SkillImportEntryKind = WorkspaceSkillImportEntryKind

@Composable
fun SkillsScreen(repository: SkillRepository) = SkillsCatalog(repository)

@Composable
fun SkillImportOptionsSheet(
    onDismiss: () -> Unit,
    onPasteMarkdown: () -> Unit,
    onFromFile: () -> Unit,
    onFromGitHub: () -> Unit,
) = SkillSourceSheet(onDismiss, onPasteMarkdown, onFromFile, onFromGitHub)

@Composable
fun SkillImportEntryDialog(kind: SkillImportEntryKind, onDismiss: () -> Unit, onImport: (String) -> Unit) =
    SkillDraftDialog(kind, onDismiss, onImport)
