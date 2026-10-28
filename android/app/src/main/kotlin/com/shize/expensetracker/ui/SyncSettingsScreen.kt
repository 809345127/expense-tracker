package com.shize.expensetracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
/// 照 iOS `SyncSettingsView` 做（2026-10-13）：右上「完成」（顺手保存）；
/// 「服务器」一张卡（地址、token 两个输入行）、动作一张卡（测试连接 / 立即同步）、「状态」一张卡、最底下红字「重新拉一遍全部数据」。
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
    val c = Ios.c
    val ready = !busy && url.isNotBlank() && token.isNotBlank()

    IosSheetHeader("同步", trailing = {
        IosPillButton("完成", {
            // 跟 iOS 一样「完成」顺手保存；没改过就不写，免得空值把已保存的冲掉
            if (url.isNotBlank() && token.isNotBlank() && (url != savedUrl || token != savedToken)) vm.save(url, token)
            onBack()
        }, prominent = true)
    })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        IosSection(header = "服务器", footer = "地址末尾有没有斜杠都行，会自动处理。token 在服务器上的 /opt/expense-sync/env 里。") {
            row(first = true) {
                IosInlineField(url, { url = it }, "http://地址:端口", align = TextAlign.Start,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            }
            row(first = false) {
                // ⚠️ 遮起来：这一屏可能在别人眼前打开
                IosInlineField(token, { token = it }, "token", align = TextAlign.Start,
                    visualTransformation = PasswordVisualTransformation())
            }
        }

        IosSection(footer = message.ifEmpty { null }) {
            row(first = true, onClick = if (ready) ({ vm.save(url, token); vm.test() }) else null) {
                Text("测试连接", style = Ios.body, color = if (ready) c.accent else c.tertiaryLabel, modifier = Modifier.weight(1f))
            }
            row(first = false, onClick = if (ready) ({ vm.save(url, token); vm.syncNow() }) else null) {
                Text("立即同步", style = Ios.body, color = if (ready) c.accent else c.tertiaryLabel, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.secondaryLabel)
            }
        }

        IosSection(header = "状态") {
            row(first = true) { IosValueRowContent("这台设备待推送", if (pending < 0) "—" else "$pending 条") }
            // ⚠️ 游标要显示出来：排障时它是最有用的一个数 —— 卡住不动就说明拉取那一半没在推进
            row(first = false) { IosValueRowContent("同步游标", "$lastRev") }
            row(first = false) {
                IosValueRowContent("上次同步",
                    if (lastSyncAt == 0L) "还没同步过"
                    else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastSyncAt)))
            }
            if (lastError.isNotEmpty()) {
                // ⚠️ 失败必须显示出来。静默失败是最坏的形态：两台手机数据不一样，而界面上一切正常
                row(first = false) { Text("上次的问题：$lastError", style = Ios.footnote, color = c.orange) }
            }
        }

        IosSection(footer = "排障用。会把游标归零、从服务器重新拉一遍；本地数据不会丢，同 id 的记录对上之后覆盖。") {
            row(first = true, onClick = if (!busy) ({ vm.resetCursor() }) else null) {
                Text("重新拉一遍全部数据", style = Ios.body, color = c.red)
            }
        }
    }
}
