package com.jarvys.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.jarvys.agent.connectors.DeviceConnectorIconTile
import kotlinx.coroutines.launch

object JarvysUiTokens {
    val ScreenPadding = 18.dp
    val CardShape = RoundedCornerShape(14.dp)
    val FieldShape = RoundedCornerShape(8.dp)
    val DialogShape = RoundedCornerShape(20.dp)
    val ListRowMinHeight = 72.dp
    val IconTileSize = 42.dp
    val ToolbarTitleSize = 21.sp
    val PrimaryButtonHeight = 54.dp
    val Space1 = 6.dp
    val Space2 = 12.dp
    val Space3 = 18.dp
    val Space4 = 24.dp
    val Space5 = 30.dp
    val ZeroWindowInsets = WindowInsets(0, 0, 0, 0)
}

@Composable
fun JarvysSheetDragHandle() {
    Box(
        Modifier.width(40.dp).height(4.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
    )
}

fun Modifier.jarvysSheetOptionRow(role: Role? = null, onClick: () -> Unit): Modifier =
    fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp)
        .clip(RoundedCornerShape(14.dp)).clickable(role = role, onClick = onClick)

/** Jarvys palette: mineral paper, bottle glass, brass time marks and fired-clay errors. */
object JarvysPalette {
    val CanvasLight = Color(0xFFEEF2F8)
    val SurfaceLight = Color(0xFFFBFCFF)
    val RaisedLight = Color(0xFFE3EAF5)
    val InkLight = Color(0xFF15213A)
    val SecondaryInkLight = Color(0xFF55637E)
    val GlassLight = Color(0xFF2757C9)
    val BrassLight = Color(0xFF916319)
    val ClayLight = Color(0xFFA83F37)
    val RuleLight = Color(0xFFCBD5E6)
    val CanvasDark = Color(0xFF0E1420)
    val SurfaceDark = Color(0xFF161E2D)
    val RaisedDark = Color(0xFF1F2A3D)
    val InkDark = Color(0xFFE6ECF7)
    val SecondaryInkDark = Color(0xFFAAB6CC)
    val GlassDark = Color(0xFF8DB4FF)
    val BrassDark = Color(0xFFE0B75F)
    val ClayDark = Color(0xFFF09889)
    val RuleDark = Color(0xFF2F3C54)
}

/** One source for motion timing/easing; static states remain understandable without animation. */
object JarvysMotion {
    const val FeedbackMillis = 120
    const val ExpandMillis = 190
    const val PlaceChangeMillis = 250
    const val PresenceMillis = 220
    const val MessageArrivalMillis = 190
    const val BotPulseCycleMillis = 1_600
    const val AntennaCycleMillis = 1_800
    const val StreamCursorCycleMillis = 760
    val Decelerate = CubicBezierEasing(0.18f, 0.72f, 0.22f, 1f)

    fun <T> feedback() = tween<T>(FeedbackMillis, easing = Decelerate)
    fun <T> expand() = tween<T>(ExpandMillis, easing = Decelerate)
    fun <T> placeChange() = tween<T>(PlaceChangeMillis, easing = Decelerate)
    fun <T> presence() = tween<T>(PresenceMillis, easing = Decelerate)
    fun <T> messageArrival() = tween<T>(MessageArrivalMillis, easing = Decelerate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvysTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    transparent: Boolean = false,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val colors = if (transparent) TopAppBarDefaults.topAppBarColors(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) else TopAppBarDefaults.topAppBarColors()
    TopAppBar(
        title = { Box(Modifier.testTag("jarvys-topbar-title")) { title() } },
        navigationIcon = navigationIcon, actions = actions, colors = colors,
        windowInsets = windowInsets,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun JarvysScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    nestedInScaffold: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            JarvysTopAppBar(
                title = { Text(title,
                    fontSize = JarvysUiTokens.ToolbarTitleSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) JarvysBackNavigationButton(onBack) else Spacer(Modifier.size(48.dp)) },
                actions = actions,
                transparent = true,
                windowInsets = if (nestedInScaffold) JarvysUiTokens.ZeroWindowInsets else TopAppBarDefaults.windowInsets,
            )
        },
        bottomBar = {
            bottomAction?.let { action ->
                Box((if (nestedInScaffold) Modifier else Modifier.navigationBarsPadding())
                    .fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 10.dp)) {
                    action()
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

@Composable
fun JarvysGroup(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth(), shape = JarvysUiTokens.CardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
    }
}

@Composable
fun JarvysListRow(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = JarvysUiTokens.ListRowMinHeight)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leadingContent?.invoke() ?: icon?.let { DeviceConnectorIconTile(it, size = JarvysUiTokens.IconTileSize) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = subtitleColor,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

@Composable
fun JarvysSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

data class JarvysChoiceOption<T>(val value: T, val label: String)

/** Material sheet shell shared by simple one-choice pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> JarvysChoiceSheet(
    title: String,
    choices: List<JarvysChoiceOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectionInProgress by remember { mutableStateOf(false) }
    var closeDelivered by remember { mutableStateOf(false) }
    fun closeOnce() {
        if (!closeDelivered) {
            closeDelivered = true
            onClose()
        }
    }
    fun select(value: T) {
        if (selectionInProgress) return
        selectionInProgress = true
        scope.launch {
            sheetState.hide()
            closeOnce()
            onSelect(value)
        }
    }
    BackHandler {
        scope.launch {
            sheetState.hide()
            closeOnce()
        }
    }
    ModalBottomSheet(
        onDismissRequest = ::closeOnce,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { BottomSheetDefaults.windowInsets },
    ) {
        JarvysChoiceSheetContent(title, choices, selected, ::select)
    }
}

/** Reusable sheet content separated from the dialog window for previews and visual captures. */
@Composable
fun <T> JarvysChoiceSheetContent(
    title: String,
    choices: List<JarvysChoiceOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 16.dp)
            .testTag("jarvys-choice-sheet-content"),
    ) {
        Spacer(Modifier.height(2.dp))
        Box(Modifier.align(Alignment.CenterHorizontally)) { JarvysSheetDragHandle() }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("jarvys-choice-sheet-title"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        choices.forEachIndexed { index, choice ->
            val isSelected = selected == choice.value
            Row(
                modifier = Modifier.jarvysSheetOptionRow(role = Role.RadioButton) { onSelect(choice.value) }
                    .padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.RadioButton
                        this.selected = isSelected
                    }
                    .testTag("jarvys-choice-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(choice.label, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                if (isSelected) Icon(LucideIcons.CircleCheck, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                        .testTag("jarvys-choice-selected-indicator"))
            }
        }
    }
}

@Composable
fun JarvysTag(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun JarvysTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = JarvysUiTokens.FieldShape,
    )
}

data class JarvysDropdownOption<T>(val value: T, val label: String, val supportingText: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> JarvysDropdownField(
    label: String,
    value: T,
    options: List<JarvysDropdownOption<T>>,
    onSelect: (T) -> Unit,
    filterLabel: String,
    noResultsLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(expanded) { mutableStateOf("") }
    val selected = options.firstOrNull { it.value == value }
    val visibleOptions = options.filter { option ->
        query.isBlank() || option.label.contains(query, ignoreCase = true)
            || option.supportingText?.contains(query, ignoreCase = true) == true
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.semantics {
            role = Role.Button
            contentDescription = "$label: ${selected?.label.orEmpty()}"
        },
    ) {
        OutlinedTextField(
            value = selected?.label.orEmpty(),
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("jarvys-dropdown-field"),
            enabled = enabled,
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = JarvysUiTokens.FieldShape,
            maxLines = 2,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    .testTag("jarvys-dropdown-filter"),
                label = { Text(filterLabel) },
                singleLine = false,
                maxLines = 2,
                shape = JarvysUiTokens.FieldShape,
            )
            if (visibleOptions.isEmpty()) {
                Text(noResultsLabel, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            } else visibleOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            option.supportingText?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

data class JarvysChoice<T>(val value: T, val label: String)

@Composable
fun <T> JarvysChoiceGroup(
    title: String,
    choices: List<JarvysChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    insideGroup: Boolean = false,
    enabled: Boolean = true,
) {
    val choicesContent: @Composable ColumnScope.() -> Unit = {
        JarvysSectionLabel(title, Modifier.padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 8.dp))
        choices.forEachIndexed { index, choice ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled) { onSelect(choice.value) }
                .padding(horizontal = JarvysUiTokens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                Text(choice.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge)
                RadioButton(selected == choice.value, onClick = { onSelect(choice.value) }, enabled = enabled)
            }
            if (index < choices.lastIndex) HorizontalDivider(Modifier.padding(start = JarvysUiTokens.ScreenPadding))
        }
    }
    if (insideGroup) Column(modifier, content = choicesContent)
    else JarvysGroup(modifier, PaddingValues(vertical = 6.dp), content = choicesContent)
}

@Composable
fun JarvysSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingStatus: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    warning: Boolean = false,
    icon: ImageVector? = null,
    switchModifier: Modifier = Modifier,
) {
    JarvysGroup(modifier, PaddingValues(JarvysUiTokens.ScreenPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (warning) Icon(LucideIcons.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                supportingStatus?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall, lineHeight = 17.sp)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = switchModifier)
        }
    }
}

@Composable
fun JarvysPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = JarvysUiTokens.PrimaryButtonHeight), enabled = enabled,
        shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
