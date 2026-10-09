package com.shize.expensetracker.smart

import com.shize.expensetracker.data.CategoryKind
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// 智能记账：把一段文字 / 几张图片识别成几笔待核对的账（2026-10-09 加，照 iOS 抄过来）。
//
// 用户原话：「加入输入文字或者图片，识别要记什么账，可以批量记账」
// 「可以使用我的 DeepSeek api」「如果 api 不能用，是否有降级的方法？」「key 可以搞成输入的吗」
// iOS 先做、用户试过说「还可以，做安卓吧」。
//
// ## 四级降级（前一级不行就自动退到下一级，界面上会写这次用的是哪一级）
//
// | 级 | 做法 | 什么时候退到这一级 |
// |---|---|---|
// | 1 | 图片 + 本地读出来的字 + 你的文字，一起发给 DeepSeek 看图模型 | 有图、填了 key（正常情况）|
// | 2 | 只发文字（你的文字 + 本地从图里读出来的字）给 DeepSeek 文字模型 | 只有文字；或者第 1 级报错 |
// | 3 | 手机本地按规则拆：数字当金额、按关键词猜分类 | 没填 key；或者 DeepSeek 整个不通 |
// | 4 | 什么都没认出来：保留输入，提示稍后重试 | 第 3 级也拆不出任何金额 |
//
// ⚠️⚠️ 这个文件是**纯逻辑**（不碰 Android API），单元测试在 SmartParseTest.kt。
//    跟 iOS `SmartParse.swift` 是同一套规则：关键词表、提示词、降级顺序、解析口径**一字不差**，
//    改一边要同步改另一边。

/// 识别出来的一笔，用户核对、改完之后才真正存成账目。
data class ParsedDraft(
    val id: String = UUID.randomUUID().toString(),
    /// 金额，**恒为正数**；收支看 isIncome（跟记账表单同一个口径）
    val amount: BigDecimal,
    val isIncome: Boolean,
    val categoryKey: String,
    /// 毫秒时间戳
    val date: Long,
    val note: String,
    /// 没把握：分类是猜的 / 日期没认出来 / 第 3 级本地规则拆的。界面上标黄
    val uncertain: Boolean = false,
    /// 同一天已经有一笔同样金额、同样收支的账。界面上标「可能已记过」，默认不勾
    val duplicate: Boolean = false,
    /// 勾上了才会保存
    val selected: Boolean = true,
    val tagIds: Set<String> = emptySet(),
    val isPrivate: Boolean = false,
)

/// 识别时要用到的一个分类（从 CategoryEntity 抄出来的纯值，好测）
data class SmartCategory(val key: String, val name: String) {
    val isIncome: Boolean get() = CategoryKind.isIncome(key)
}

enum class SmartLevel(val title: String) {
    AiVision("AI 看图识别"), AiText("AI 文字识别"), Rules("本地规则拆分"), Nothing("没认出来")
}

data class SmartResult(
    val level: SmartLevel,
    val drafts: List<ParsedDraft>,
    /// 为什么没用上更高的那一级（「DeepSeek 余额不足」「没填 key」这类）。第 1 / 2 级成功时为空
    val downgradeReason: String = "",
)

/// 调 DeepSeek / 解析回答失败的原因，已经是人话，直接给界面显示
class SmartError(message: String) : Exception(message)

/// 兜底分类代号。⚠️ 跟 iOS `CategorySeed.fallbackKey` 一致
const val EXPENSE_FALLBACK_KEY = "其他"

// ---------------------------------------------------------------- 第 3 级：本地规则

object RuleParser {
    /// 关键词 → 预设分类代号。只在库里真有这个代号时才用（用户可能删了某个预设）。
    /// 先比用户自己的分类名，比不上才查这张表。⚠️ 跟 iOS 一字不差
    val keywords: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("早饭", "早餐", "午饭", "午餐", "晚饭", "晚餐", "夜宵", "外卖", "饭", "餐", "奶茶", "咖啡",
            "饮料", "水果", "零食", "火锅", "面", "吃", "喝", "美团", "饿了么", "星巴克", "瑞幸", "麦当劳", "肯德基"),
        "交通" to listOf("打车", "滴滴", "出租", "地铁", "公交", "高铁", "火车", "机票", "航班", "油费", "加油", "停车",
            "过路费", "单车", "车费", "12306"),
        "购物" to listOf("超市", "淘宝", "京东", "拼多多", "天猫", "衣服", "鞋", "日用", "便利店", "买"),
        "居住" to listOf("房租", "水费", "电费", "燃气", "物业", "宽带", "话费", "租金"),
        "娱乐" to listOf("电影", "游戏", "KTV", "门票", "旅游", "演出", "酒吧"),
        "医疗" to listOf("药", "医院", "挂号", "体检", "看病", "牙"),
        "学习" to listOf("书", "课", "培训", "学费", "考试"),
        "人情" to listOf("礼物", "份子", "请客", "随礼", "送礼"),
        "订阅" to listOf("会员", "订阅", "月费", "年费", "iCloud", "爱奇艺", "腾讯视频", "网易云", "Netflix"),
        "收入:工资" to listOf("工资", "薪水", "薪资"),
        "收入:奖金" to listOf("奖金", "年终奖", "绩效"),
        "收入:理财" to listOf("理财", "利息", "分红", "基金", "收益"),
        "收入:红包" to listOf("收红包", "收到红包"),
        "收入:退款" to listOf("退款", "退货", "报销"),
        "收入:兼职" to listOf("兼职", "稿费", "外快"),
    )

    /// 出现这些词就当收入（除非是「发红包」这种明确的支出）
    val incomeHints = listOf("工资", "薪水", "奖金", "年终奖", "收到", "收红包", "退款", "退货", "报销",
        "利息", "分红", "收益", "兼职", "稿费", "收入", "进账", "入账")

    /// 日期词 → 往前推几天。⚠️ 长的排前面（「大前天」要先于「前天」）
    val dayWords = listOf("大前天" to -3, "前天" to -2, "昨天" to -1, "今天" to 0)

    private val amountRegex = Regex("""([+\-]?)(?:[¥$]\s*)?(\d+(?:\.\d{1,2})?)""")

    /// 把一段文字拆成几笔。规则见 iOS `RuleParser.parse` 的注释（同一套）：
    ///   · 全角数字、全角标点先转半角
    ///   · 说明文字在金额前还是后，看第一笔金额前面有没有字，整段统一
    ///   · 「昨天 / 前天」往后一直生效，直到出现下一个日期词
    ///   · 数字前面紧挨着 `+` 当收入、`-` 当支出
    ///   · 每笔都标「没把握」
    fun parse(raw: String, categories: List<SmartCategory>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ParsedDraft> {
        val text = normalize(raw)

        // 第一遍：挑出真正是金额的数字。年份、日期、时间跳过
        data class Hit(val range: IntRange, val sign: String, val amount: BigDecimal)
        val hits = mutableListOf<Hit>()
        for (m in amountRegex.findAll(text)) {
            val end = m.range.last + 1
            val tail = if (end < text.length) text[end].toString() else ""
            if (tail in setOf("月", "日", "号", "点", ":", "年", "/", "-")) continue
            val head = if (m.range.first > 0) text[m.range.first - 1].toString() else ""
            if (head == ":" || head == "/") continue
            // 「2026-10-09」里的「-09」：符号前面紧挨着数字，是日期的分隔符，不是负号
            val sign = m.groupValues[1]
            if (sign.isNotEmpty() && head.firstOrNull()?.isDigit() == true) continue
            val amount = m.groupValues[2].toBigDecimalOrNull() ?: continue
            if (amount.signum() <= 0 || amount >= BigDecimal(1_000_000_000)) continue
            hits += Hit(m.range, sign, amount.clean())
        }
        if (hits.isEmpty()) return emptyList()

        val labelAfter = cleanLabel(text.substring(0, hits[0].range.first)).isEmpty()

        val drafts = mutableListOf<ParsedDraft>()
        var dayOffset = 0
        var prevEnd = 0
        hits.forEachIndexed { i, h ->
            val end = h.range.last + 1
            val before = text.substring(prevEnd, h.range.first)
            val nextStart = if (i + 1 < hits.size) hits[i + 1].range.first else text.length
            val after = text.substring(end, nextStart)
            prevEnd = end

            lastDayOffset(before)?.let { dayOffset = it }
            val labelText = if (labelAfter) {
                // 说明在后面时只取到下一个分隔符为止
                val t = after.split('\n', ',', ';', '，', '；', '、').firstOrNull { it.isNotEmpty() } ?: ""
                lastDayOffset(t)?.let { dayOffset = it }
                t
            } else {
                // 只取紧挨着金额的那一行：前面隔着好几行的字（店名、日期）不算这笔的说明
                before.split('\n').map { cleanLabel(it) }.lastOrNull { it.isNotEmpty() } ?: ""
            }
            val label = cleanLabel(labelText)

            val isIncome = when (h.sign) {
                "+" -> true
                "-" -> false
                else -> incomeHints.any { label.contains(it) } && !label.contains("发红包")
            }
            val key = guessCategory(label, isIncome, categories)
            val day = Instant.ofEpochMilli(now).atZone(zone).plusDays(dayOffset.toLong()).toInstant().toEpochMilli()
            val catName = categories.firstOrNull { it.key == key }?.name ?: ""
            drafts += ParsedDraft(
                amount = h.amount, isIncome = isIncome, categoryKey = key, date = day,
                note = if (label == catName) "" else label, uncertain = true,
            )
        }
        return drafts
    }

    /// 小票上表示「总共付了多少」的词，前面的优先
    val totalWords = listOf("实付", "实收", "应付", "合计", "总计")

    private val receiptTime = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:\s+(\d{1,2}):(\d{2}))?""")

    /// 一张小票（从图里读出来的字）→ **一笔**：取「实付 / 合计」那一行的金额，店名取第一行不带数字的字。
    /// 不是小票（找不到这几个词）返回 null，调用方改用 [parse] 逐笔拆。
    ///
    /// 跟发给 AI 的规则同一个口径：一张小票只算一笔，不把每个商品、小计、优惠拆开
    /// （模拟器上实测：不加这条，一张便利店小票被拆成 6 笔，商品、合计、优惠全算进去了）。
    fun parseReceipt(ocr: String, categories: List<SmartCategory>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ParsedDraft>? {
        val lines = normalize(ocr).lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (w in totalWords) {
            val line = lines.lastOrNull { it.contains(w) } ?: continue
            val hit = parse(line, categories, now, zone).firstOrNull() ?: continue
            val shop = lines.firstOrNull { l -> l.none { it.isDigit() } }?.take(20) ?: ""
            val key = guessCategory(lines.joinToString(" "), false, categories)
            // 小票上印的时间：认得出、又不在未来，就用它
            var date = now
            receiptTime.find(lines.joinToString("\n"))?.let { m ->
                val g = m.groupValues
                runCatching {
                    LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(),
                        g[4].toIntOrNull() ?: 12, g[5].toIntOrNull() ?: 0).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull()?.takeIf { it <= now }?.let { date = it }
            }
            return listOf(hit.copy(isIncome = false, categoryKey = key, note = shop, date = date))
        }
        return null
    }

    /// 全角转半角 + 统一几个符号。
    /// ⚠️ 句号要先换：iOS 那边踩过，系统的全角转半角会把「。」变成半角的「｡」（U+FF61），不是「.」
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.replace("。", ".").replace("．", ".")) {
            sb.append(
                when (ch) {
                    in '\uFF01'..'\uFF5E' -> ch - 0xFEE0   // 全角 ASCII → 半角
                    '\u3000' -> ' '
                    '｡' -> '.'
                    '￥' -> '¥'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun lastDayOffset(s: String): Int? {
        var best: Pair<Int, Int>? = null   // (词的结尾位置, 偏移)
        for ((w, off) in dayWords) {
            val i = s.lastIndexOf(w)
            if (i < 0) continue
            val end = i + w.length
            // 比结尾位置：「大前天」和它里面的「前天」结尾相同，长的排在前面、相同时不替换
            if (best == null || end > best.first) best = end to off
        }
        return best?.second
    }

    /// 说明文字：去掉夹着的日期时间、日期词、货币单位、两头的标点空白
    private fun cleanLabel(s: String): String {
        var t = s.replace(Regex("""\d[\d:/\-.年月日号点]*"""), " ")
        for ((w, _) in dayWords) t = t.replace(w, "")
        for (w in listOf("元", "块钱", "块", "¥", "RMB", "rmb")) t = t.replace(w, "")
        return t.trim { !it.isLetterOrDigit() }
    }

    fun guessCategory(label: String, isIncome: Boolean, categories: List<SmartCategory>): String {
        val pool = categories.filter { it.isIncome == isIncome }
        // ① 用户自己的分类名，长的先比
        pool.sortedByDescending { it.name.length }
            .firstOrNull { it.name.isNotEmpty() && label.contains(it.name) }?.let { return it.key }
        // ② 关键词表
        val keys = pool.map { it.key }.toSet()
        for ((key, words) in keywords) {
            if (key in keys && words.any { label.contains(it, ignoreCase = true) }) return key
        }
        // ③ 兜底
        val fallback = if (isIncome) CategoryKind.INCOME_FALLBACK_KEY else EXPENSE_FALLBACK_KEY
        return if (fallback in keys) fallback else pool.firstOrNull()?.key ?: fallback
    }
}

/// 去掉多余的尾零（25.00 → 25），跟 iOS Decimal 存进去的样子一致（同 AmountExpression.value）
internal fun BigDecimal.clean(): BigDecimal =
    stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

// ---------------------------------------------------------------- 提示词 + 解析回答

object AIPrompt {
    /// 系统提示词。⚠️ 跟 iOS `AIPrompt.system` 一字不差
    fun system(categories: List<SmartCategory>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.SIMPLIFIED_CHINESE)
            .format(Instant.ofEpochMilli(now).atZone(zone))
        val expense = categories.filter { !it.isIncome }.joinToString("、") { it.key }
        val income = categories.filter { it.isIncome }.joinToString("、") { it.key }
        return """
你是记账助手。用户会给你一段文字、或者小票 / 订单 / 支付账单的截图（截图里的文字也会以「图片里读出的文字」附上，可能有错字），把里面**每一笔实际发生的收入或支出**提取出来。

现在是 $stamp（时区 ${zone.id}）。

支出分类只能从这里选：$expense
收入分类只能从这里选：$income

只输出一个 JSON 对象，格式：
{"items":[{"amount":32.5,"type":"expense","category":"餐饮","date":"2026-10-09","time":"12:30","note":"午饭","confident":true}]}

规则：
- amount 是正数，最多两位小数；type 只能是 expense 或 income
- category 必须是上面列出的原样字符串之一，拿不准就用「其他」或「收入:其他」，并把 confident 设为 false
- date 用 yyyy-MM-dd；「昨天」「上周五」这类按现在的日期换算；没提日期就用今天
- time 用 HH:mm，图片或文字里没有具体时间就填 null
- note 写一个简短的说明（商家名或买了什么），不超过 20 个字，不要重复分类名
- 一张小票只算**一笔**（用实付金额，不要把每个商品拆开，不要把小计、优惠、找零、余额当成一笔）
- 账单列表截图里有几行交易就是几笔；转账给自己、信用卡还款这类不算收支的跳过
- 金额不清楚、或者看不出是不是一笔交易时，confident 设为 false
- 一笔都没有就输出 {"items":[]}
""".trim()
    }

    /// 用户消息：用户自己写的字 + 从图片里读出来的字
    fun user(text: String, ocrTexts: List<String>): String {
        val parts = mutableListOf<String>()
        val t = text.trim()
        if (t.isNotEmpty()) parts += "用户写的：\n$t"
        ocrTexts.forEachIndexed { i, o -> if (o.isNotEmpty()) parts += "图片${i + 1}里读出的文字：\n$o" }
        return if (parts.isEmpty()) NO_TEXT else parts.joinToString("\n\n")
    }

    const val NO_TEXT = "（请看图片）"

    /// 把模型的回答翻成草稿。分类不在列表里 → 猜一个 + 标没把握；金额不合法的那条直接丢掉
    fun decode(json: String, categories: List<SmartCategory>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ParsedDraft> {
        // 个别模型会在 JSON 外面包一层 ```json … ```，剥掉
        var s = json.trim()
        if (s.startsWith("```")) {
            s = s.lines().drop(1).joinToString("\n")
            val i = s.lastIndexOf("```")
            if (i >= 0) s = s.substring(0, i)
        }
        val items = try {
            Json.parseToJsonElement(s).jsonObject["items"]!!.jsonArray
        } catch (e: Exception) {
            throw SmartError("AI 回的不是约定的格式")
        }

        val keys = categories.map { it.key }.toSet()
        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        return items.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (item[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

            // 金额：数字或字符串都认，直接按字符串转 BigDecimal，不经过 double
            val raw = str("amount")?.let { RuleParser.normalize(it) } ?: return@mapNotNull null
            val amount = raw.toBigDecimalOrNull()?.abs()?.setScale(2, RoundingMode.HALF_UP) ?: return@mapNotNull null
            if (amount.signum() <= 0 || amount >= BigDecimal(1_000_000_000)) return@mapNotNull null

            val isIncome = str("type") == "income"
            var uncertain = (item["confident"] as? JsonPrimitive)?.booleanOrNull == false
            val note0 = str("note")?.trim() ?: ""

            var key = str("category") ?: ""
            if (key !in keys || CategoryKind.isIncome(key) != isIncome) {
                key = RuleParser.guessCategory(note0, isIncome, categories)
                uncertain = true
            }

            var date = now
            str("date")?.let { ds ->
                val day = runCatching { LocalDate.parse(ds) }.getOrNull()
                if (day == null) {
                    uncertain = true
                } else {
                    val time = str("time")?.let { parseTime(it) }
                    date = when {
                        time != null -> day.atTime(time).atZone(zone).toInstant().toEpochMilli()
                        day == nowZ.toLocalDate() -> now   // 今天、没写几点 → 就用现在，跟手动记一笔一致
                        else -> day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                    }
                    // 认出来的日期在未来：多半是年份猜错了，标没把握让人看一眼
                    if (date > now + 60_000) uncertain = true
                }
            }

            var note = note0.take(30)
            if (note == categories.firstOrNull { it.key == key }?.name) note = ""

            ParsedDraft(amount = amount.clean(), isIncome = isIncome, categoryKey = key,
                        date = date, note = note, uncertain = uncertain)
        }
    }

    private fun parseTime(s: String): LocalTime? {
        val p = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (p.size < 2 || p[0] !in 0..23 || p[1] !in 0..59) return null
        return LocalTime.of(p[0], p[1])
    }
}
