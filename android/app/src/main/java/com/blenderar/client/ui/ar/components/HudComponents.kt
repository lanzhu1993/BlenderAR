package com.blenderar.client.ui.ar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blenderar.client.core.net.ConnectionState
import com.blenderar.client.effects.PerformanceLevel
import com.blenderar.client.ui.theme.StatusError
import com.blenderar.client.ui.theme.StatusOk
import com.blenderar.client.ui.theme.StatusWarn

/** 顶部 HUD:返回 | 特效名 | 连接状态 | FPS | 性能等级 */
@Composable
fun TopHud(
    onBack: () -> Unit,
    effectName: String,
    connectionState: ConnectionState,
    fps: Float,
    performanceLevel: PerformanceLevel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(effectName, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (color, label) = when (connectionState) {
                    is ConnectionState.Connected -> StatusOk to "已连接"
                    is ConnectionState.Connecting -> StatusWarn to "连接中"
                    is ConnectionState.Failed -> StatusError to "连接失败"
                    ConnectionState.Disconnected -> StatusWarn to "离线模式"
                }
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(String.format("FPS %.0f", fps), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text(performanceLevel.label, style = MaterialTheme.typography.labelSmall, color = StatusWarn)
        }
    }
}

/** 中部提示横幅 */
@Composable
fun HudBanner(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC121822), RoundedCornerShape(12.dp)),
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
