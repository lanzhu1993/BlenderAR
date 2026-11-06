package com.blenderar.client.ui.params

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blenderar.client.App
import com.blenderar.client.core.state.SyncMode
import com.blenderar.client.ui.theme.BrandGold
import com.blenderar.client.ui.theme.BrandPurple
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.ui.platform.LocalContext

/** 颜色预设(紫金魔法系) */
private val colorPresets = listOf(
    "#A83CFF", "#7C4DFF", "#B388FF", "#E040FB",
    "#FFD166", "#FFAB40", "#FF5533", "#34CABC",
)

/**
 * 页面三:特效参数。
 * Slider / 颜色 / 开关;支持两种模式:
 * - 跟随 Mac(服务端权威)
 * - 本地覆盖(临时生效,可选择性回传 Mac)
 */
@Composable
fun EffectParamsSheet(
    effectId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val repo = container.effectRepository

    val snapshots by repo.snapshots.collectAsState()
    val snapshot = snapshots[effectId]
    if (snapshot == null) {
        Text("特效不存在:$effectId", modifier = Modifier.padding(24.dp))
        return
    }

    val params = snapshot.parameters
    val syncMode = snapshot.syncMode
    var syncBack by remember { mutableStateOf(true) }
    var primaryHex by remember(params.primaryColor) { mutableStateOf(params.primaryColor) }
    var secondaryHex by remember(params.secondaryColor) { mutableStateOf(params.secondaryColor) }

    fun edit(vararg pairs: Pair<String, Any>) {
        val json = buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is Float -> put(k, v)
                    is Int -> put(k, v)
                    is String -> put(k, v)
                }
            }
        }
        repo.localEdit(effectId, json, syncBack = syncBack && syncMode == SyncMode.LOCAL_OVERRIDE)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("特效参数 · ${snapshot.definition.name}", style = MaterialTheme.typography.titleLarge)
        Text(
            "修订 #${snapshot.revision} · 资源 v${snapshot.assetVersion} · " + if (snapshot.syncMode == SyncMode.LOCAL_OVERRIDE) "本地覆盖中" else "跟随 Mac",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 同步模式
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("本地覆盖(不跟随 Mac 修改)", style = MaterialTheme.typography.bodyMedium)
                Text("开启后本机修改即时生效且不被 Mac 覆盖", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = syncMode == SyncMode.LOCAL_OVERRIDE,
                onCheckedChange = { on ->
                    if (on) {
                        repo.localEdit(effectId, buildJsonObject {}, syncBack = false)
                    } else {
                        repo.followServer(effectId)
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = BrandPurple),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("修改同步回传 Mac", style = MaterialTheme.typography.bodyMedium)
                Text("关闭则仅本地生效,不会推送给电脑控制台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = syncBack, onCheckedChange = { syncBack = it })
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // 颜色
        Text("主颜色(紫)", style = MaterialTheme.typography.titleMedium)
        ColorRow(hex = primaryHex, onHexChange = { primaryHex = it }, onCommit = { edit("primaryColor" to it) })
        Text("次颜色(金)", style = MaterialTheme.typography.titleMedium)
        ColorRow(hex = secondaryHex, onHexChange = { secondaryHex = it }, onCommit = { edit("secondaryColor" to it) })

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // 滑杆参数
        SliderRow("火焰强度", params.intensity, 0f..10f) { edit("intensity" to it) }
        SliderRow("播放速度", params.speed, 0f..5f) { edit("speed" to it) }
        SliderRow("特效缩放", params.scale, 0.1f..5f) { edit("scale" to it) }
        SliderRow("透明度", params.opacity, 0f..1f) { edit("opacity" to it) }
        SliderRow("辉光强度(Bloom)", params.bloomIntensity, 0f..2f) { edit("bloomIntensity" to it) }
        SliderRow("噪声强度", params.noiseStrength, 0f..1f) { edit("noiseStrength" to it) }
        SliderRow(
            "粒子数量", params.particleCount.toFloat(), 100f..3000f,
            valueLabel = { "${(it / 50).toInt() * 50}" },
        ) { edit("particleCount" to (((it / 50).toInt() * 50))) }
        SliderRow(
            "光带数量", params.ribbonCount.toFloat(), 0f..8f,
            valueLabel = { "${it.toInt()}" },
        ) { edit("ribbonCount" to it.toInt()) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Button(
            onClick = {
                repo.followServer(effectId)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("恢复跟随 Mac(放弃本地覆盖)")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String = { String.format("%.2f", it) },
    onCommit: (Float) -> Unit,
) {
    // 本地拖动状态:松手才提交(避免每帧网络消息)
    var dragValue by remember(label, value) { mutableStateOf(value) }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                valueLabel(dragValue),
                style = MaterialTheme.typography.labelMedium,
                color = BrandGold,
            )
        }
        Slider(
            value = dragValue.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            onValueChange = { dragValue = it },
            onValueChangeFinished = { onCommit(dragValue) },
        )
    }
}

@Composable
private fun ColorRow(
    hex: String,
    onHexChange: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (preset in colorPresets) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(
                        color = runCatching {
                            Color(android.graphics.Color.parseColor(preset))
                        }.getOrDefault(Color.Gray),
                        shape = CircleShape,
                    )
                    .border(
                        width = if (preset.equals(hex, ignoreCase = true)) 2.dp else 1.dp,
                        color = if (preset.equals(hex, ignoreCase = true)) Color.White else Color(0x44FFFFFF),
                        shape = CircleShape,
                    )
                    .clickable { onCommit(preset) },
            )
        }
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = hex,
            onValueChange = { onHexChange(it) },
            label = { Text("HEX") },
            singleLine = true,
            modifier = Modifier.width(140.dp),
            trailingIcon = {
                Text(
                    "确定",
                    modifier = Modifier
                        .clickable { onCommit(hex) }
                        .padding(horizontal = 8.dp),
                    color = BrandPurple,
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
    }
}
