package com.blenderar.client.ui.ar.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blenderar.client.ui.theme.BrandGold
import com.blenderar.client.ui.theme.BrandPurple
import com.blenderar.client.ui.theme.StatusError

/**
 * 底部工具栏:
 * 第一行:特效选择 chips
 * 第二行:放置 / 参数 / 截图 / 录制(含音频开关) / 清除
 * 第三行(选中时):删除 / 复制 / 重置
 */
@Composable
fun ToolBar(
    modifier: Modifier = Modifier,
    effectOptions: List<Pair<String, String>>,
    currentEffectId: String,
    onSelectEffect: (String) -> Unit,
    onPlaceCenter: () -> Unit,
    onParams: () -> Unit,
    onScreenshot: () -> Unit,
    isRecording: Boolean,
    recordAudio: Boolean,
    onToggleAudio: () -> Unit,
    onToggleRecording: () -> Unit,
    onClear: () -> Unit,
    hasSelection: Boolean,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xAA0B0F14), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 特效选择
        if (effectOptions.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((id, name) in effectOptions) {
                    AssistChip(
                        onClick = { onSelectEffect(id) },
                        label = { Text(if (id == currentEffectId) "● $name" else name) },
                    )
                }
            }
        }

        // 主操作行
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onPlaceCenter, modifier = Modifier.weight(1.2f)) {
                Text("放置特效")
            }
            OutlinedButton(onClick = onParams, modifier = Modifier.weight(0.9f)) {
                Text("参数")
            }
            OutlinedButton(onClick = onScreenshot, modifier = Modifier.weight(0.9f)) {
                Text("截图")
            }
            OutlinedButton(
                onClick = onToggleRecording,
                modifier = Modifier.weight(0.9f),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isRecording) StatusError else MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(if (isRecording) "停止" else "录制")
            }
        }

        // 录制选项行
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("录制音频", style = MaterialTheme.typography.labelMedium, color = Color.White)
            Switch(
                checked = recordAudio,
                onCheckedChange = { onToggleAudio() },
                colors = SwitchDefaults.colors(checkedTrackColor = BrandPurple),
            )
            Box(Modifier.size(6.dp))
            OutlinedButton(onClick = onClear) {
                Text("清除场景", color = StatusError)
            }
        }

        // 选中实例操作行
        if (hasSelection) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDelete) { Text("删除", color = StatusError) }
                OutlinedButton(onClick = onDuplicate) { Text("复制") }
                OutlinedButton(onClick = onReset) { Text("重置") }
            }
        }
    }
}
