package com.jarvys.agent.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jarvys.agent.PersonalMemoryConsolidationWorker
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.PersonalMemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SQLite-only memory UI. It deliberately has no MemoryStore or legacy file parameter. */
@Composable
fun PersonalMemoryScreen(conversationId: String, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<PersonalMemoryItem>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("nickname") }
    var draft by remember { mutableStateOf("") }
    var dailyConsolidationEnabled by remember {
        mutableStateOf(context.getSharedPreferences(PersonalMemoryConsolidationWorker.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .getBoolean(PersonalMemoryConsolidationWorker.DAILY_ENABLED_KEY, true))
    }

    fun reload() {
        scope.launch {
            loading = true
            try {
                items = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentBrowse() }
                error = null
            } catch (failure: Exception) {
                error = failure.message ?: "No se pudo leer el perfil guardado."
            } finally { loading = false }
        }
    }

    LaunchedEffect(conversationId) { reload() }

    fun persist(profile: Boolean) {
        val value = draft.trim()
        if (value.isEmpty()) return
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    val bridge = ChatDatabaseBridge.getInstance(context)
                    if (profile) bridge.personalAgentSetDirective(section.trim().ifBlank { "profile" }, value, null, "User entered through the memory screen.")
                    else bridge.personalAgentSaveMemory(value, "curated", "user_note", 0.5, "memory-ui")
                }
                notice = "Guardado con evidencia: ${receipt.id} · revisión ${receipt.revision}."
                draft = ""
                showProfileDialog = false
                showNoteDialog = false
                reload()
            } catch (failure: Exception) {
                notice = "No se guardó: ${failure.message ?: "error de base de datos"}."
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Memoria personal", style = MaterialTheme.typography.headlineSmall)
            if (onClose != null) TextButton(onClick = onClose) { Text("Cerrar") }
        }
        Text("El perfil se comparte entre tus conversaciones normales. Los chats privados o sin memoria no lo leen ni lo modifican.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Consolidar notas diarias (una vez al día)")
            Switch(checked = dailyConsolidationEnabled, onCheckedChange = { enabled ->
                dailyConsolidationEnabled = enabled
                PersonalMemoryConsolidationWorker.setDailyEnabled(context, enabled)
            }, modifier = Modifier.testTag("memory-daily-consolidation"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { draft = ""; section = "nickname"; showProfileDialog = true }, modifier = Modifier.testTag("memory-add-profile")) { Text("Añadir dato de perfil") }
            Button(onClick = { draft = ""; showNoteDialog = true }, modifier = Modifier.testTag("memory-add-note")) { Text("Añadir recuerdo") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("memory-notice")) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) Text("Cargando memoria…")
        else if (items.isEmpty()) {
            Text("Tu perfil nuevo está vacío. Los archivos de memoria anteriores no se han importado.", modifier = Modifier.testTag("memory-empty"))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("personal-memory-list")) {
                items(items, key = { "${it.kind}:${it.id}" }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (item.kind == "profile") "Perfil · ${item.section}" else "Recuerdo · ${item.section}", style = MaterialTheme.typography.labelLarge)
                            Text(item.text)
                            if (item.kind == "memory") {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentForget(item.id) }
                                            notice = "Recuerdo eliminado con revisión de auditoría."
                                            reload()
                                        } catch (failure: Exception) { error = failure.message ?: "No se pudo eliminar." }
                                    }
                                }) { Text("Olvidar") }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }

    if (showProfileDialog) AlertDialog(
        onDismissRequest = { showProfileDialog = false },
        title = { Text("Dato de perfil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(section, { section = it }, label = { Text("Sección, por ejemplo nickname o profession") }, singleLine = true, modifier = Modifier.testTag("memory-profile-section"))
                OutlinedTextField(draft, { draft = it }, label = { Text("Dato que quieres guardar") }, modifier = Modifier.testTag("memory-profile-value"))
            }
        },
        confirmButton = { TextButton(onClick = { persist(true) }, modifier = Modifier.testTag("memory-save")) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { showProfileDialog = false }) { Text("Cancelar") } },
    )
    if (showNoteDialog) AlertDialog(
        onDismissRequest = { showNoteDialog = false },
        title = { Text("Recuerdo duradero") },
        text = { OutlinedTextField(draft, { draft = it }, label = { Text("Nota") }, modifier = Modifier.testTag("memory-note-value")) },
        confirmButton = { TextButton(onClick = { persist(false) }, modifier = Modifier.testTag("memory-save")) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("Cancelar") } },
    )
}
