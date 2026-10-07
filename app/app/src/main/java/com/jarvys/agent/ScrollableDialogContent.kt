package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scrolls one bounded text area with edge cues; place AlertDialog titles/actions outside this content. */
@Composable
fun ScrollableDialogContent(
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val reservedForTitleActionsAndInsets = (screenHeight - 260.dp).coerceAtLeast(0.dp)
    val safeDialogTextHeight = minOf(screenHeight * 0.42f, reservedForTitleActionsAndInsets, 480.dp)
    val boundedHeight = maxHeight?.let { minOf(it, safeDialogTextHeight) } ?: safeDialogTextHeight
    Box(modifier.fillMaxWidth().heightIn(max = boundedHeight)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState),
            content = content,
        )
        if (scrollState.canScrollBackward) {
            HorizontalDivider(
                Modifier.align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
        }
        if (scrollState.canScrollForward) {
            HorizontalDivider(
                Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
        }
    }
}
