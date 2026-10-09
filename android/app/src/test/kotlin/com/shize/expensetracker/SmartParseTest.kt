package com.shize.expensetracker

import com.shize.expensetracker.data.CategoryKind
import com.shize.expensetracker.smart.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/// 智能记账的纯逻辑。用例跟 iOS 的 `SmartSelfTest`（SmartParse.swift 末尾）逐条对应，
/// 两边结果必须一样 —— 这是「两端同一套规则」唯一能机械核对的地方。
class SmartParseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    /// 固定一个「现在」：2026-10-09 20:15（周五）
    private val now = LocalDateTime.of(2026, 10, 9, 20, 15).atZone(zone).toInstant().toEpochMilli()

    /// 预设分类：10 个支出 + 7 个收入，跟两端的种子一致
    private val cats = listOf("餐饮", "交通", "购物", "居住", "娱乐", "医疗", "学习", "人情", "订阅", "其他")
        .map { SmartCategory(it, it) } +
        CategoryKind.builtInIncome.map { SmartCategory(it.key, it.name) }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).dayOfMonth
    private fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).hour
    private fun rules(s: String) = RuleParser.parse(s, cats, now, zone)
    private fun sig(ds: List<ParsedDraft>) = ds.map { "${it.amount.toPlainString()}|${it.categoryKey}|${it.note}|${day(it.date)}" }
    private fun decode(s: String) = runCatching { AIPrompt.decode(s, cats, now, zone) }.getOrNull()

    // ---- 第 3 级：本地规则 ----

    @Test fun 说明在前加昨天() =
        assertEquals(listOf("32|餐饮|午饭|9", "18.5|交通|打车|9", "126|购物|超市|8"), sig(rules("午饭 32 打车 18.5 昨天超市 126")))

    @Test fun 说明在后() = assertEquals(listOf("32|餐饮|午饭|9", "18|交通|打车|9"), sig(rules("32 午饭 18 打车")))

    @Test fun 昨天往后延续() = assertEquals(listOf("32|餐饮|午饭|8", "18|交通|打车|8"), sig(rules("昨天 午饭 32 打车 18")))

    @Test fun 大前天不被前天截胡() = assertEquals(listOf("60|娱乐|电影|6"), sig(rules("大前天 电影 60")))

    @Test fun 工资是收入() {
        val r = rules("工资 8000")
        assertEquals(1, r.size); assertTrue(r[0].isIncome); assertEquals("收入:工资", r[0].categoryKey)
    }

    @Test fun 发红包是支出() {
        val r = rules("发红包 200")
        assertEquals(1, r.size); assertFalse(r[0].isIncome)
    }

    @Test fun 日期里的数字不当金额() = assertEquals(listOf("32|餐饮|午饭|9"), sig(rules("2026-10-09 午饭 32")))

    @Test fun 时间里的数字不当金额() = assertEquals(listOf("25|餐饮|咖啡|9"), sig(rules("12:30 咖啡 25")))

    @Test fun 月日加元() = assertEquals(listOf("45|餐饮|晚饭|9"), sig(rules("10月9日 晚饭 45元")))

    @Test fun 全角数字和句号() = assertEquals(listOf("32.5|餐饮|午饭|9"), sig(rules("午饭３２。５")))

    @Test fun 人民币符号前缀加品牌词() = assertEquals(listOf("45|餐饮|星巴克|9"), sig(rules("¥45 星巴克")))

    @Test fun 账单写法正负号() {
        val r = rules("+100 退款\n-30 滴滴")
        assertEquals(2, r.size)
        assertTrue(r[0].isIncome); assertEquals("收入:退款", r[0].categoryKey)
        assertFalse(r[1].isIncome); assertEquals("交通", r[1].categoryKey)
    }

    @Test fun 买归购物() = assertEquals(listOf("66|购物|随便买了点东西|9"), sig(rules("随便买了点东西 66")))

    @Test fun 猜不出归其他() = assertEquals(listOf("12|其他|不知道是啥|9"), sig(rules("不知道是啥 12")))

    @Test fun 规则结果一律标黄() = assertTrue(rules("午饭 32").all { it.uncertain })

    @Test fun 没有数字就是零笔() {
        assertTrue(rules("你好").isEmpty()); assertTrue(rules("").isEmpty())
    }

    // ---- 解析 AI 回答 ----

    @Test fun AI正常两笔() {
        val d = decode("""{"items":[{"amount":32.5,"type":"expense","category":"餐饮","date":"2026-10-09","time":"12:30","note":"午饭","confident":true},{"amount":"8000","type":"income","category":"收入:工资","date":"2026-10-08","time":null,"note":"十月工资"}]}""")!!
        assertEquals(2, d.size)
        assertEquals(0, d[0].amount.compareTo(BigDecimal("32.5")))
        assertFalse(d[0].uncertain)
        assertEquals(12, hour(d[0].date))
        assertTrue(d[1].isIncome); assertEquals("收入:工资", d[1].categoryKey)
        assertEquals(8, day(d[1].date)); assertEquals(12, hour(d[1].date))
    }

    @Test fun 分类不在列表就猜一个并标黄() {
        val d = decode("""{"items":[{"amount":20,"type":"expense","category":"奶茶","date":"2026-10-09","note":"奶茶"}]}""")!!
        assertEquals("餐饮", d[0].categoryKey); assertTrue(d[0].uncertain)
    }

    @Test fun 支出配了收入分类要改掉() {
        val d = decode("""{"items":[{"amount":20,"type":"expense","category":"收入:工资","date":"2026-10-09","note":"x"}]}""")!!
        assertEquals("其他", d[0].categoryKey); assertTrue(d[0].uncertain)
    }

    @Test fun 负数取绝对值零丢掉() {
        val d = decode("""{"items":[{"amount":-15,"type":"expense","category":"交通","date":"2026-10-09"},{"amount":0,"type":"expense","category":"交通","date":"2026-10-09"}]}""")!!
        assertEquals(1, d.size); assertEquals("15", d[0].amount.toPlainString())
    }

    @Test fun 剥掉代码块包装且备注同分类名清空() {
        val d = decode("```json\n{\"items\":[{\"amount\":9.9,\"type\":\"expense\",\"category\":\"订阅\",\"date\":\"2026-10-09\",\"note\":\"订阅\"}]}\n```")!!
        assertEquals(1, d.size); assertEquals("", d[0].note)
    }

    @Test fun 今天没写几点就用现在() {
        val d = decode("""{"items":[{"amount":9,"type":"expense","category":"餐饮","date":"2026-10-09"}]}""")!!
        assertEquals(now, d[0].date)
    }

    @Test fun 未来日期标黄() {
        val d = decode("""{"items":[{"amount":9,"type":"expense","category":"餐饮","date":"2027-01-01"}]}""")!!
        assertTrue(d[0].uncertain)
    }

    @Test fun 日期格式不对用现在并标黄() {
        val d = decode("""{"items":[{"amount":9,"type":"expense","category":"餐饮","date":"昨天"}]}""")!!
        assertTrue(d[0].uncertain); assertEquals(now, d[0].date)
    }

    @Test fun 不是约定格式就报错() {
        assertNull(decode("我不知道")); assertNull(decode("""{"foo":1}"""))
    }

    @Test fun 空items不报错() = assertEquals(0, decode("""{"items":[]}""")!!.size)

    // ---- 提示词 ----

    @Test fun 提示词带今天日期和全部分类() {
        val sys = AIPrompt.system(cats, now, zone)
        assertTrue(sys.contains("2026-10-09 星期五 20:15"))
        assertTrue(cats.all { sys.contains(it.key) })
    }

    @Test fun 用户消息拼法() {
        assertEquals("用户写的：\n午饭 32\n\n图片1里读出的文字：\n实付 24.50", AIPrompt.user(" 午饭 32 ", listOf("实付 24.50")))
        assertEquals(AIPrompt.NO_TEXT, AIPrompt.user("", listOf("")))
    }
}

class OcrRowsTest {
    @Test fun 同一行左右两截拼成一行() {
        // 金额那截比商品名高 2 像素：逐段按上边缘排会排成「8.50 ↵ 饭团」
        val boxes = listOf(
            OcrBox("饭团", 60f, 202f, 242f), OcrBox("8.50", 400f, 200f, 240f),
            OcrBox("全家便利店", 60f, 100f, 140f),
            OcrBox("6.00", 400f, 299f, 339f), OcrBox("乌龙茶", 60f, 300f, 340f),
        )
        assertEquals("全家便利店\n饭团  8.50\n乌龙茶  6.00", joinRows(boxes))
    }
}

class ReceiptTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 9, 20, 15).atZone(zone).toInstant().toEpochMilli()
    private val cats = listOf("餐饮", "交通", "购物", "其他").map { SmartCategory(it, it) }

    @Test fun 小票只算一笔取实付() {
        val r = RuleParser.parseReceipt(
            "全家梗利店\n2026-10-09 12:41\n饭团  8.50\n乌龙荼  6.00\n三明治  12.00\n合计  26.50\n优惠  -2.00\n实付  24.50",
            cats, now, zone)!!
        assertEquals(1, r.size)
        assertEquals("24.5", r[0].amount.toPlainString())
        assertEquals("全家梗利店", r[0].note)
        assertEquals("餐饮", r[0].categoryKey)
        val t = Instant.ofEpochMilli(r[0].date).atZone(zone)
        assertEquals(12, t.hour); assertEquals(41, t.minute)
    }

    @Test fun 不是小票返回空() = assertNull(RuleParser.parseReceipt("午饭 32", cats, now, zone))

    @Test fun 说明只取紧挨着的那一行() {
        val r = RuleParser.parse("全家便利店\n2026-10-09\n饭团 8.5", cats, now, zone)
        assertEquals(listOf("8.5|餐饮|饭团"), r.map { "${it.amount.toPlainString()}|${it.categoryKey}|${it.note}" })
    }
}
