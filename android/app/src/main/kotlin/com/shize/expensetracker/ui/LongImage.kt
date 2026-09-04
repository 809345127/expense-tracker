package com.shize.expensetracker.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import java.math.BigDecimal
import java.time.LocalDate

// 把一个月的账目画成一张长图。
//
// ## ⚠️⚠️ 为什么是用原生 Canvas 一笔一笔画，而不是「把界面截下来」
//
// 安卓上没有「滚动截屏」这种 API：屏幕外的内容压根没被渲染，不是被裁掉了。
// 把 Compose 的界面搬到屏幕外渲染是可行的（ComposeView + measure/layout/draw），
// 但要自己伺候一套 lifecycle / SavedStateRegistry owner，出错时的堆栈很难读。
//
// 更重要的是 **iOS 那边踩过的坑指向了另一个方向**：
// 那边的长图 bug 是「按笔数挑清晰度」，而真正的自变量是**像素高度**
// （一张位图最高 8192 像素，越界时 ImageRenderer 返回 nil、不报错、不崩，
//  界面上就是「点了生成长图，什么都没发生」）。验收时库里只有 14 笔，
// 3 倍图才 4897 px，一切正常；等账目长到 99 笔就静默失效了。
//
// 自己画的好处正是**分配位图之前就能把高度算准**（measure 是一道纯加法），
// 所以「画不画得下」这件事在动手之前就有答案，不会变成一个静默失败。
//
// ## ⚠️ 跟 iOS 有意接受的一处不同：行内那个图标
//
// iOS 的长图复用了明细页的 `ExpenseRow`，图和界面不会分叉。这边做不到复用，
// 所以行内画的是**「分类色块 + 分类名第一个字」**，不是分类图标
// （Compose 的 ImageVector 没有不进 composition 就能栅格化的口子）。
// 颜色和排版跟明细页对齐，识别作用一样在。

object LongImage {

    /// 渲染宽度（逻辑点）。⚠️ 用固定值而不是当前屏宽：导出的图跟在哪台设备上导的无关，
    /// 换手机之后导出来的图还是一样宽，存档才对得齐。跟 iOS 那边同一个数
    const val WIDTH_PT = 390f

    /// 一张位图最高多少像素。
    /// ⚠️ 8192 是 iOS 那边实测出来的位图上限；安卓没有同一个硬限制（软件位图更宽松），
    /// **但内存是真限制**：390pt × 3 倍 = 1170px 宽，8000px 高就已经是 37MB 一张。
    /// 两端用同一个数，行为一致比各自压榨极限更值钱。
    const val MAX_PIXEL_HEIGHT = 8000f

    // ---- 版式常量（逻辑点）。measure 和 render 必须用同一套，别一边改一边不改 ----
    private const val PAD = 16f
    private const val HEADER_H = 118f
    private const val DAY_TITLE_H = 26f
    private const val ROW_H = 56f
    private const val DAY_GAP = 18f
    private const val TOP_GAP = 18f
    private const val FOOTER_H = 56f
    private const val CARD_RADIUS = 16f

    data class Day(val date: LocalDate, val items: List<ExpenseEntity>, val total: BigDecimal)

    /// 按天分组、天倒序（跟明细页一致）
    fun group(expenses: List<ExpenseEntity>): List<Day> =
        expenses.groupBy { it.date.toLocalDate() }
            .toSortedMap(reverseOrder())
            .map { (d, items) ->
                Day(d, items.sortedByDescending { it.date }, items.map { it.amount }.sum())
            }

    /// 这张图在 1 倍下有多高（逻辑点）。**纯加法，没有任何猜测** ——
    /// 这就是上面说的「分配位图之前就知道装不装得下」
    fun measureHeight(days: List<Day>): Float {
        var h = HEADER_H + TOP_GAP
        days.forEachIndexed { i, d ->
            h += DAY_TITLE_H + d.items.size * ROW_H
            if (i != days.lastIndex) h += DAY_GAP
        }
        return h + FOOTER_H
    }

    /// 按真实高度反推能用的最大清晰度，上限 3 倍（再高肉眼也看不出差别，只是更占内存）。
    /// ⚠️ **不按笔数挑** —— iOS 那个 bug 就是这么潜伏下来的（笔数只是像素高度的间接代理，
    /// 代理关系随数据量增长就断了）
    fun scaleFor(contentHeightPt: Float): Float {
        if (contentHeightPt <= 0f) return 1f
        val fit = Math.floor((MAX_PIXEL_HEIGHT / contentHeightPt * 100f).toDouble()) / 100f
        return fit.toFloat().coerceIn(1f, 3f)
    }

    /// 连 1 倍都装不下 —— 这时要**明确告诉用户**，不能像 iOS 那个老 bug 一样悄悄失败
    fun tooBig(contentHeightPt: Float) = contentHeightPt > MAX_PIXEL_HEIGHT

    /// 大约还能装多少笔（给提示文案用）
    fun approxRowCapacity() = (MAX_PIXEL_HEIGHT / ROW_H).toInt()

    fun render(
        title: String,
        total: BigDecimal,
        count: Int,
        days: List<Day>,
        categories: List<CategoryEntity>,
        footer: String,
        /// 深色模式下也画白底：长图是要发出去/存档的，跟着系统深浅色变会很怪
        scale: Float = scaleFor(measureHeight(days)),
    ): Bitmap {
        val catByKey = categories.associateBy { it.id }
        val heightPt = measureHeight(days)
        val w = (WIDTH_PT * scale).toInt()
        val h = (heightPt * scale).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)

        val bg = AndroidColor.rgb(0xF2, 0xF2, 0xF7)      // 对位 iOS 的 systemGroupedBackground
        val card = AndroidColor.WHITE
        val primary = AndroidColor.rgb(0x00, 0x7A, 0xFF)  // 对位分类色板里的蓝
        val ink = AndroidColor.rgb(0x1C, 0x1C, 0x1E)
        val inkSub = AndroidColor.rgb(0x6E, 0x6E, 0x73)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(size: Float, color: Int, bold: Boolean = false, right: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
                textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            }

        fill.color = bg
        c.drawRect(0f, 0f, WIDTH_PT, heightPt, fill)

        // ---- 头部 ----
        fill.color = primary
        c.drawRect(0f, 0f, WIDTH_PT, HEADER_H, fill)
        c.drawText(title, WIDTH_PT / 2, 34f,
                   text(15f, AndroidColor.argb(235, 255, 255, 255)).apply { textAlign = Paint.Align.CENTER })
        c.drawText(formatYuan(total), WIDTH_PT / 2, 78f,
                   text(34f, AndroidColor.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER })
        c.drawText("共 $count 笔", WIDTH_PT / 2, 100f,
                   text(12f, AndroidColor.argb(200, 255, 255, 255)).apply { textAlign = Paint.Align.CENTER })

        // ---- 按天 ----
        var y = HEADER_H + TOP_GAP
        for ((i, day) in days.withIndex()) {
            c.drawText(day.date.dayTitle(), PAD + 4f, y + 17f, text(13f, inkSub, bold = true))
            c.drawText(formatYuan(day.total), WIDTH_PT - PAD - 4f, y + 17f,
                       text(13f, inkSub, bold = true, right = true))
            y += DAY_TITLE_H

            val cardTop = y
            val cardBottom = y + day.items.size * ROW_H
            fill.color = card
            c.drawRoundRect(RectF(PAD, cardTop, WIDTH_PT - PAD, cardBottom),
                            CARD_RADIUS, CARD_RADIUS, fill)

            for ((j, e) in day.items.withIndex()) {
                val top = cardTop + j * ROW_H
                if (j > 0) {
                    fill.color = AndroidColor.rgb(0xE5, 0xE5, 0xEA)
                    c.drawRect(PAD + 52f, top, WIDTH_PT - PAD, top + 0.6f, fill)
                }
                val def = catByKey[e.categoryKey]
                val catName = def?.name ?: e.categoryKey
                val color = categoryColor(def?.colorIndex ?: 9).toArgb()

                // 分类色块 + 名字第一个字（见文件头「跟 iOS 有意接受的一处不同」）
                fill.color = AndroidColor.argb(40, AndroidColor.red(color),
                                               AndroidColor.green(color), AndroidColor.blue(color))
                c.drawRoundRect(RectF(PAD + 12f, top + 9f, PAD + 50f, top + 47f), 11f, 11f, fill)
                c.drawText(
                    catName.take(1), PAD + 31f, top + 34f,
                    text(17f, color, bold = true).apply { textAlign = Paint.Align.CENTER },
                )

                // 第一行：有备注显示备注，否则显示分类名（跟明细页的口径一致）
                val titleText = e.note.ifEmpty { catName } + if (e.isPrivate) "  🔒" else ""
                val amountText = formatYuan(e.amount)
                val amountPaint = text(15f, ink, bold = true, right = true)
                val amountWidth = amountPaint.measureText(amountText)
                val titlePaint = text(15f, ink)
                c.drawText(
                    ellipsize(titleText, titlePaint, WIDTH_PT - PAD * 2 - 62f - amountWidth - 12f),
                    PAD + 62f, top + 25f, titlePaint,
                )
                c.drawText(amountText, WIDTH_PT - PAD - 12f, top + 27f, amountPaint)

                // 第二行：分类 · 时间 · 补记
                val sub = buildString {
                    if (e.note.isNotEmpty()) append(catName).append(" · ")
                    append(e.date.timeText())
                    if (isBackfilled(e.date, e.createdAt)) append(" · 补记")
                }
                c.drawText(sub, PAD + 62f, top + 43f, text(11f, inkSub))
            }
            y = cardBottom
            if (i != days.lastIndex) y += DAY_GAP
        }

        // ---- 脚注 ----
        c.drawText(footer, WIDTH_PT / 2, y + 32f,
                   text(10f, AndroidColor.rgb(0x8E, 0x8E, 0x93)).apply { textAlign = Paint.Align.CENTER })

        return bmp
    }

    /// 一行装不下就截断加省略号。
    /// ⚠️ 原生 Canvas 的 drawText **不会**自己截断 —— 超出的部分直接画到画布外面去了，
    /// 结果是长备注把右边的金额盖住。必须自己量
    private fun ellipsize(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var n = s.length
        while (n > 0 && paint.measureText(s.take(n) + "…") > maxWidth) n--
        return s.take(n) + "…"
    }
}
