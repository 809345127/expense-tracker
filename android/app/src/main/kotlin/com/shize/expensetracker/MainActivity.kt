package com.shize.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.sync.SyncWorker
import com.shize.expensetracker.ui.ExpenseListScreen
import com.shize.expensetracker.ui.SyncSettingsScreen
import com.shize.expensetracker.ui.theme.AppTheme

/// 单 Activity + 全 Compose，没有一行 XML 布局（安卓官方推荐的写法）。
///
/// 导航用一个 sealed class + `when` 手写，没引 Navigation 库：
/// 一共三个目的地、没有深链和回退栈的复杂需求，手写更少的活动件；
/// 以后真需要（比如小组件点进来要落到指定页面）再换。
class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object List : Screen
        data object Sync : Screen
        data class Form(val editing: ExpenseEntity?) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.List) }
                when (val s = screen) {
                    Screen.List -> ExpenseListScreen(
                        onOpenSync = { screen = Screen.Sync },
                        onAdd = { screen = Screen.Form(null) },
                        onEdit = { screen = Screen.Form(it) },
                    )
                    Screen.Sync -> SyncSettingsScreen(onBack = { screen = Screen.List })
                    is Screen.Form -> com.shize.expensetracker.ui.ExpenseFormScreen(
                        editing = s.editing,
                        onClose = { screen = Screen.List },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 每次回到前台同步一次。
        // ⚠️ 这条比周期任务靠得住：vivo（OriginOS）这类 ROM 杀后台很凶，
        // WorkManager 那个 15 分钟的周期任务很可能不会按时跑。
        SyncWorker.syncNow(this)
    }
}
