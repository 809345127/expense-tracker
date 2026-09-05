package com.shize.expensetracker

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.sync.SyncWorker
import com.shize.expensetracker.ui.*
import com.shize.expensetracker.ui.theme.AppTheme
import kotlinx.coroutines.launch

/// 单 Activity + 全 Compose，没有一行 XML 布局（安卓官方推荐的写法）。
///
/// 导航仍然是一个 sealed interface + `when` 手写，没引 Navigation 库：
/// 目的地只有七个、回退关系是一层（都从首页推出去），手写的活动件更少。
/// 转场是 2026-09-05 加的（原来是硬切，没有任何过渡）—— 见 `ui/Motion.kt`。
///
/// ⚠️ **这个 Activity 必须是 `FragmentActivity`，不能是 `ComponentActivity`**：
/// `BiometricPrompt` 的构造函数只收 FragmentActivity / Fragment
/// （它内部靠一个不可见 Fragment 承载系统弹窗的生命周期）。
/// FragmentActivity 本身继承自 ComponentActivity，所以 `setContent` 照样能用。
class MainActivity : FragmentActivity() {

    private sealed interface Screen {
        /// 层级深度：转场要靠它判断是「往里进」还是「往回退」。首页 0，推出去的页面 1。
        val depth: Int

        data object Home : Screen { override val depth = 0 }
        data object Sync : Screen { override val depth = 1 }
        data object Categories : Screen { override val depth = 1 }
        data object Tags : Screen { override val depth = 1 }
        data object Export : Screen { override val depth = 1 }
        data class Form(val editing: ExpenseEntity?) : Screen { override val depth = 1 }
    }

    private enum class Tab { List, Stats }

    /// 桌面小组件那颗「＋ 记一笔」发过来的 intent 带这个 action。
    /// ⚠️ 用一个自定义 action 而不是 extra：`actionStartActivity` 会把同一个 Intent
    /// 复用给 PendingIntent，而系统判两个 PendingIntent「是不是同一个」时**不看 extra**
    /// —— 两颗按钮只在 extra 上不同的话，后建的那个会拿到前一个的 intent。
    /// action 是参与比较的，用它才不会串。
    companion object {
        const val ACTION_ADD = "com.shize.expensetracker.action.ADD"
    }

    /// 冷启动 / 已在后台时被小组件叫起来，都要落到「记一笔」。
    /// 用 State 而不是直接改导航状态：onNewIntent 可能在 Compose 还没组合时就来了
    private val pendingAdd = mutableStateOf(false)

    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingAdd.value = intent?.action == ACTION_ADD

        val app = App.from(this)
        val gate = app.gate

        setContent {
            AppTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var tab by remember { mutableStateOf(Tab.List) }
                val unlocked by gate.unlocked.collectAsStateWithLifecycle()

                // 解锁着的时候给窗口加 FLAG_SECURE。
                // ⚠️ 这一条是 iOS 那边「解锁态要盖住多任务快照」的安卓对应做法：
                // 不加的话，切到最近任务列表时系统会给这一屏拍张缩略图，
                // **私密记录就躺在那张缩略图里**（而且它还会被写进磁盘）。
                // FLAG_SECURE 顺带也挡掉截屏和录屏。
                LaunchedEffect(unlocked) {
                    if (unlocked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }

                // 小组件那颗「＋」进来 → 直接推到记一笔
                LaunchedEffect(pendingAdd.value) {
                    if (pendingAdd.value) {
                        screen = Screen.Form(null)
                        pendingAdd.value = false
                    }
                }

                /// 私密门开关。挂在「连点三下月份标题」上（明细页和统计页都有）。
                /// ⚠️ 界面上**没有**任何常驻的锁头按钮 —— 有按钮就等于告诉别人这儿有东西。
                val toggleLock: () -> Unit = {
                    if (unlocked) gate.lock()
                    else lifecycleScope.launch { gate.unlock(this@MainActivity) }
                }

                val bottomBar: @Composable () -> Unit = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.List,
                            onClick = { tab = Tab.List },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                            label = { Text("明细") },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Stats,
                            onClick = { tab = Tab.Stats },
                            icon = { Icon(Icons.Filled.PieChart, null) },
                            label = { Text("统计") },
                        )
                    }
                }

                // 系统返回键 / 返回手势。
                // ⚠️ 不接的话按返回是**直接退出 app**（默认行为），而在安卓上返回是
                // 一等一的导航方式 —— 从「导出」页按返回退出整个 app 会被当成崩了。
                // 只在推出去的页面上拦；首页不拦（首页按返回退出 app 才是对的）。
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // 首页 ↔ 推出去的页面：横向推进，方向跟返回手势一致
                        pushTransform(forward = targetState.depth > initialState.depth)
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Home -> AnimatedContent(
                            targetState = tab,
                            // 底部两个 tab 是**平级**的，用淡入淡出穿透。
                            // 横向推进会让人以为「统计在明细右边」，而 tab 没有先后
                            transitionSpec = { fadeThroughTransform() },
                            label = "tab",
                        ) { t ->
                            when (t) {
                                Tab.List -> ExpenseListScreen(
                                    onOpenSync = { screen = Screen.Sync },
                                    onOpenCategories = { screen = Screen.Categories },
                                    onOpenTags = { screen = Screen.Tags },
                                    onOpenExport = { screen = Screen.Export },
                                    onToggleLock = toggleLock,
                                    onAdd = { screen = Screen.Form(null) },
                                    onEdit = { screen = Screen.Form(it) },
                                    bottomBar = bottomBar,
                                )
                                Tab.Stats -> StatsScreen(
                                    onToggleLock = toggleLock,
                                    // 点排行里的某一行 → 把条件换成「只看这一个」并切回明细。
                                    // ⚠️ 是**换**不是叠加：点第二个分类应该是「改看那个」，
                                    // 要两个都要就去筛选面板里选（跟 iOS 同一个决定）
                                    onDrillDown = { f: ExpenseFilter ->
                                        app.filter.value = f
                                        tab = Tab.List
                                    },
                                    bottomBar = bottomBar,
                                )
                            }
                        }
                        Screen.Sync -> SyncSettingsScreen(onBack = { screen = Screen.Home })
                        Screen.Categories -> CategoryManagerScreen(onBack = { screen = Screen.Home })
                        Screen.Tags -> TagManagerScreen(onBack = { screen = Screen.Home })
                        Screen.Export -> ExportScreen(onBack = { screen = Screen.Home })
                        is Screen.Form -> ExpenseFormScreen(
                            editing = s.editing,
                            onClose = { screen = Screen.Home },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_ADD) pendingAdd.value = true
    }

    override fun onStart() {
        super.onStart()
        // 每次回到前台同步一次。
        // ⚠️ 这条比周期任务靠得住：vivo（OriginOS）这类 ROM 杀后台很凶，
        // WorkManager 那个 15 分钟的周期任务很可能不会按时跑。
        SyncWorker.syncNow(this)
    }

    override fun onStop() {
        super.onStop()
        // 切到后台自动上锁（对位 iOS 的 scenePhase == .background）。
        // ⚠️⚠️ **必须放在 onStop、不能放 onPause**：走「输锁屏密码」那条路时，
        // 系统的密码界面是另一个 Activity，它会让这里走 onPause —— 放 onPause 的话
        // 刚弹出解锁框就把自己锁了，永远解不开。
        // 就算是 onStop 也仍然会被密码界面触发，所以 PrivacyGate 里还有一道
        // `authenticating` 挡着（见那边的注释）。这两道缺一不可。
        App.from(this).gate.lockOnBackground()
    }
}
