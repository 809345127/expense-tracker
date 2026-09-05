package com.shize.expensetracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// 「同步设置」：粘服务器地址和 token、测连接、立即同步、看状态。
///
/// ⚠️ 地址和 token **不写进代码**（仓库是公开的，而那台 VPS 上还跑着别的东西），
/// 所以只能在这里粘一次。好处是以后换服务器不用重新装 app。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(onBack: () -> Unit, vm: SyncViewModel = viewModel()) {
    val savedUrl by vm.url.collectAsStateWithLifecycle()
    val savedToken by vm.token.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val lastRev by vm.lastRev.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()

    // 已保存的值到达之后填进输入框（首帧是空的，因为要等 DataStore 读完）
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    LaunchedEffect(savedUrl) { if (url.isEmpty()) url = savedUrl }
    LaunchedEffect(savedToken) { if (token.isEmpty()) token = savedToken }
    LaunchedEffect(Unit) { vm.refreshPending() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("http://地址:端口") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("token") },
                singleLine = true,
                // ⚠️ 遮起来：这一屏可能在别人眼前打开
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "地址末尾有没有斜杠都行，会自动处理。token 在服务器上的 /opt/expense-sync/env 里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.save(url, token); vm.test() },
                    enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                ) { Text("测试连接") }
                Button(
                    onClick = { vm.save(url, token); vm.syncNow() },
                    enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                ) { Text("立即同步") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("状态", style = MaterialTheme.typography.titleSmall)
            InfoRow("这台设备待推送", if (pending < 0) "—" else "$pending 条")
            // ⚠️ 游标要显示出来（iOS 那边一直有，安卓漏了）：排障时它是最有用的一个数 ——
            // 卡住不动就说明拉取那一半没在推进，而界面上其它地方都看不出来
            InfoRow("同步游标", "$lastRev")
            InfoRow(
                "上次同步",
                if (lastSyncAt == 0L) "还没同步过"
                else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastSyncAt)),
            )
            if (lastError.isNotEmpty()) {
                // ⚠️ 失败必须显示出来。静默失败是最坏的形态：
                // 两台手机数据不一样，而界面上一切正常
                Text(
                    "上次的问题：$lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            OutlinedButton(onClick = { vm.resetCursor() }, enabled = !busy) {
                Text("重新拉一遍全部数据")
            }
            Text(
                "排障用。会把游标归零、从服务器重新拉一遍；本地数据不会丢，同 id 的记录对上之后覆盖。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
