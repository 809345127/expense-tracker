package com.shize.expensetracker.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.shize.expensetracker.App
import com.shize.expensetracker.MainActivity
import com.shize.expensetracker.data.WidgetSummary
import com.shize.expensetracker.ui.formatYuan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 桌面小组件。用 Glance（拿 Compose 的写法写小组件），对位 iOS 的 WidgetKit。
//
// ⚠️ **跟 iOS 那边最大的不同：这边能直接读库。**
// iOS 上小组件是独立进程、还因为免费账号用不了 App Group，只能靠钥匙串传一份摘要过去；
// 安卓这边小组件跑在**同一个 app 进程**里，直接开 Room 读就行，没有那套桥接。
//
// ⚠️⚠️ 显示的数字**恒不含私密记录**（过滤在 Repository.widgetSummary 里做）。
// 小组件摆在桌面上比 app 里更暴露；而且只要它跟 app 锁定态的数字对不上，
// 别人一比就知道藏了东西、还知道藏了多少。
//
// ⚠️ 「点哪儿去哪儿」这件事**跟 iOS 是两套**，因为平台能力不一样：
//   · iOS：中号靠 Link + widgetURL 两个目的地；小号系统忽略 Link，只能二选一。
//   · 安卓：**任何尺寸都能给每个元素各自挂点击**（Glance 的 clickable 没有这个限制）。
//     所以这边统一成「右上角胶囊 → 记一笔，其余任何地方 → 打开 app 停在明细页」，
//     小号也一样。**别照抄 iOS 那套"小号整块记一笔"的特例** —— 那是它的平台限制，
//     照抄过来只会让两个尺寸行为不一致、没有任何好处。

class ExpenseWidget : GlanceAppWidget() {

    /// ⚠️ 用 SizeMode.Exact：桌面上把小组件拉大拉小时重新按真实尺寸排一次版。
    /// 默认的 Single 只按最小尺寸排，拉宽之后内容会缩在左上角
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // ⚠️ 在 provideContent 之前把数据读完：provideContent 里是 Composable，
        // 不能跑挂起的库查询
        val summary = App.from(context).repository.widgetSummary()
        provideContent {
            GlanceTheme { WidgetBody(summary) }
        }
    }
}

@Composable
private fun WidgetBody(s: WidgetSummary) {
    // ⚠️⚠️ 分类排行放几行要**按真实高度算**，不能用一个「小于 X 就全不放」的开关。
    // 第一版就是那么写的（height < 120.dp 才收起来），结果 3×2 格那档刚好过了阈值、
    // 三行全画上去 —— 最后一行被切掉一半。**而且不报错**：小组件没有"内容溢出"的提示，
    // 桌面上就是一行字被裁了个头，看着像渲染坏了。
    //
    // 这跟 iOS 长图那个坑是同一个形状：**真正的自变量是高度，不是"哪一档尺寸"**，
    // 拿档位当代理，代理关系在某个尺寸上就断了。所以这里直接用剩余高度做除法。
    val size = LocalSize.current
    val compact = size.height < 120.dp
    val amountHeight = if (compact) 26.dp else 32.dp
    // 固定占掉的部分：自己的上下内边距 + 顶部那一行（里面那颗胶囊比文字高）+ 间距 +
    // 金额 + 间距 + **桌面自己给小组件加的外边距**。
    //
    // ⚠️ 最后那 24dp 是实测出来的，不是推出来的：`LocalSize` 给的是**格子的尺寸**，
    // 而 Android 12 起桌面会在小组件内容外面再套一圈自己的留白，
    // 所以「能画的高度」比 LocalSize 报的小一截。实测这台机器 3×2 格报 h=150dp，
    // 而那个尺寸下**只装得下 2 行**（第 3 行被切一半）。
    //
    // ⚠️ 这个估算故意**往保守里算**：算少一行只是少显示一个分类，算多一行是内容被裁 ——
    // 后者在桌面上看着像渲染坏了，代价不对称。
    val chrome = 14.dp * 2 + 24.dp + 4.dp + amountHeight + 6.dp + 24.dp
    val rankRows = ((size.height - chrome) / 16.dp).toInt().coerceIn(0, 3)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(14.dp)
            // 整块的默认去处：打开 app、停在明细页。
            // ⚠️ 右上角那颗「+」自己挂了另一个去处，会盖掉这一层
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.monthLabel,
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "${s.count} 笔",
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.width(8.dp))
            AddButton()
        }

        Spacer(GlanceModifier.height(4.dp))

        if (s.count == 0) {
            // ⚠️ 空态要说清是「还没数据」而不是「这个月没花钱」—— 后者会让人以为统计坏了
            Text(
                "这个月还没记账",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                  color = GlanceTheme.colors.onSurface),
            )
        } else {
            Text(
                formatYuan(s.total),
                style = TextStyle(
                    fontSize = if (compact) 22.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
            if (rankRows > 0) {
                Spacer(GlanceModifier.height(6.dp))
                for (slice in s.top.take(rankRows)) {
                    Row(GlanceModifier.fillMaxWidth()) {
                        Text(slice.name, style = TextStyle(
                            fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(formatYuan(slice.amount), style = TextStyle(
                            fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.onSurfaceVariant))
                    }
                    Spacer(GlanceModifier.height(2.dp))
                }
            }
        }
    }
}

/// 右上角那颗「记一笔」。
///
/// ⚠️ 做成**实心胶囊 + 带字**，不是一枚裸图标 —— 理由跟 iOS 那边一样：
/// 点它和点它旁边是**两个不同的去处**，裸图标才 20 来 dp，差几 dp 就会
/// 「明明点的是 +、却进了明细页」，那看起来是功能坏了、不是手指偏了。
/// 顺带也把新规矩说明白：桌面上看一眼就知道「记账要点这颗」。
@Composable
private fun AddButton() {
    val context = LocalContext.current
    Text(
        "＋ 记一笔",
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = GlanceTheme.colors.onPrimary,
            textAlign = TextAlign.Center,
        ),
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primary)
            .cornerRadius(11.dp)
            // 命中区靠这圈 padding 撑起来（约 66×26 dp），比视觉上那个胶囊大一圈
            .padding(horizontal = 9.dp, vertical = 5.dp)
            // ⚠️ 这里不能用泛型那个 actionStartActivity<MainActivity>() —— 它带不了
            // 自定义 action，落到 app 里就跟"整块可点"那条没区别了。要带 Intent 的那个重载。
            .clickable(
                androidx.glance.appwidget.action.actionStartActivity(
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD)
                )
            ),
    )
}

class ExpenseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpenseWidget()
}

/// 让「数据变了就刷小组件」这件事在任何地方都能一行调用（Repository、同步任务都要用）。
///
/// ⚠️ `updateAll` 是挂起函数，而 Repository 的写入路径不想被它拖住，所以这里自己起个
/// 后台协程。用 app 级的 scope（不是 viewModelScope）——刷新不该因为界面退出就取消。
object WidgetRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            // 桌面上一个都没放时 updateAll 什么也不做，不用自己判断
            runCatching { ExpenseWidget().updateAll(app) }
        }
    }
}
