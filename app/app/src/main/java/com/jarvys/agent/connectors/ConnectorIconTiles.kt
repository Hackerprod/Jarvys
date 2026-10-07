package com.jarvys.agent.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.BrandIcons
import com.jarvys.agent.JarvysGroup

@Composable
internal fun DeviceConnectorIconTile(icon: ImageVector, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(13.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.52f), tint = Color(0xFF171717))
    }
}

@Composable
internal fun ServiceBrandIconTile(iconId: String, fallbackLabel: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val mark = BrandIcons.forService(iconId)
    Box(modifier.size(size).clip(RoundedCornerShape(13.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (mark != null) {
            Icon(mark.vector, contentDescription = null, modifier = Modifier.size(size * 0.56f), tint = mark.color)
        } else {
            Box(Modifier.size(size).clip(RoundedCornerShape(13.dp)).background(Color(0xFF00C4CC)),
                contentAlignment = Alignment.Center) {
                Text(fallbackLabel.take(1).uppercase(), color = Color.White,
                    fontSize = (size.value * 0.48f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun ConnectorStatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, fontSize = 12.sp, maxLines = 1)
}

@Composable
internal fun ConnectorRowsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), content = content)
}

@Composable
internal fun ConnectorRowsDivider() {
    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(start = 72.dp, end = 16.dp),
        thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
}
