package com.blenderar.client.ui.connect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blenderar.client.App
import com.blenderar.client.core.net.ConnectionState
import com.blenderar.client.ui.theme.BrandGold
import com.blenderar.client.ui.theme.BrandPurple
import com.blenderar.client.ui.theme.StatusError
import com.blenderar.client.ui.theme.StatusOk
import com.blenderar.client.ui.theme.StatusWarn

/**
 * 页面一:设备连接。
 * 输入 Mac 局域网 IP、端口、配对码,建立 WebSocket 连接。
 * 连接服务端后进入 AR。
 */
@Composable
fun ConnectScreen(
    onEnterAR: () -> Unit,
) {
    val context = LocalContext.current
    val repo = (context.applicationContext as App).container.effectRepository

    val connectionState by repo.connectionState.collectAsState()
    val snapshots by repo.snapshots.collectAsState()

    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8000") }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var autoReconnect by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("AR 魔法特效", style = MaterialTheme.typography.titleLarge)
        Text(
            "连接同一局域网内的 Mac 服务端",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("服务端 IP") },
                    placeholder = { Text("例如 192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.trim() },
                    label = { Text("配对码") },
                    placeholder = { Text("见 Mac 控制台") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { autoReconnect = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = BrandPurple),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("自动重连")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val connecting = connectionState is ConnectionState.Connecting
            Button(
                onClick = { repo.connect(host, port.toIntOrNull() ?: 8000, pairingCode, autoReconnect) },
                enabled = host.isNotBlank() && pairingCode.isNotBlank() && !connecting,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (connecting) "连接中…" else "连接")
            }
            OutlinedButton(
                onClick = { repo.disconnect() },
                enabled = connectionState !is ConnectionState.Disconnected,
            ) {
                Text("断开")
            }
        }

        when (val state = connectionState) {
            is ConnectionState.Connected -> StatusRow("已连接 · 会话 ${state.sessionId ?: "-"}", StatusOk)
            is ConnectionState.Connecting -> StatusRow("正在连接(第 ${state.attempt} 次)…", BrandGold)
            is ConnectionState.Failed -> StatusRow("连接失败:${state.reason}(将自动重试)", StatusError)
            ConnectionState.Disconnected -> StatusRow("未连接 · 等待服务端", BrandGold)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已就绪特效", style = MaterialTheme.typography.titleMedium, color = BrandPurple)
            if (snapshots.isEmpty()) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (s in snapshots.values) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box8(if (s.assetsReady) StatusOk else StatusWarn)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${s.definition.name}  v${s.assetVersion}  ${if (s.assetsReady) "资源就绪" else "资源缺失"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("使用说明", style = MaterialTheme.typography.titleMedium, color = BrandPurple)
            Text("1. 在 Mac 上启动服务端,记下控制台显示的局域网 IP 与配对码", style = MaterialTheme.typography.bodyMedium)
            Text("2. 保持手机与 Mac 在同一 Wi-Fi", style = MaterialTheme.typography.bodyMedium)
            Text("3. 填写上方信息并连接,然后进入 AR 场景放置特效", style = MaterialTheme.typography.bodyMedium)
            Text(
                "在 Blender 中播放动画后，手机会接收紫焰画面",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = onEnterAR, modifier = Modifier.fillMaxWidth(), enabled = connectionState is ConnectionState.Connected) {
            Text("进入 AR 场景")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Box8(color: Color) {
    Canvas(Modifier.height(8.dp).width(8.dp)) { drawCircle(color) }
}

@Composable
private fun StatusRow(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box8(color)
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
