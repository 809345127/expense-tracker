package com.shize.expensetracker

import com.shize.expensetracker.data.cleanedName
import com.shize.expensetracker.data.comparisonKey
import org.junit.Assert.*
import org.junit.Test

/// 分类 / 标签名字清理与查重的单元测试。
///
/// **为什么这两个函数值得单独测**：`cleanedName` 不只是显示用的，
/// **新分类的代号（id）就是拿它算出来的**（见 Repository.addCategory）。
/// 而按协议，分类的 id 在两台设备上必须算得一模一样 ——
/// 算得不一样的话，同一个名字会得到两个代号，同步之后变成两条一模一样的分类，
/// 而且**不可逆**（历史账目已经各存了自己那个代号）。
///
/// 所以这些断言实际上是在钉「安卓这边和 iOS 那边算法一致」这件事。
/// iOS 侧对应的是 `CategoryDef.cleanedName` / `CategoryDef.comparisonKey`
/// （`.folding(options: [.caseInsensitive, .widthInsensitive, .diacriticInsensitive])`）。
class NamesTest {

    @Test
    fun `首尾空白去掉，中间连续空白压成一个空格`() {
        assertEquals("理发", cleanedName("  理发  "))
        assertEquals("下午 茶", cleanedName("下午   茶"))
        assertEquals("下午 茶", cleanedName("下午\t\n茶"))
        // ⚠️ 全角空格（U+3000）肉眼看不出来，但它会让「理发」和「理发　」变成两个分类
        assertEquals("理发", cleanedName("理发　"))
        // 不换行空格（U+00A0）同理，从网页复制粘贴时很常见
        assertEquals("理发", cleanedName(" 理发"))
    }

    @Test
    fun `只有空白的名字清理成空串`() {
        assertEquals("", cleanedName("   "))
        assertEquals("", cleanedName("　"))
    }

    @Test
    fun `查重忽略大小写`() {
        assertEquals(comparisonKey("Coffee"), comparisonKey("coffee"))
        // 控制组：清理之后它们**还是**两个不同的串 —— 证明是 comparisonKey 在起作用，
        // 不是 cleanedName 顺手把大小写统一了
        assertNotEquals(cleanedName("Coffee"), cleanedName("coffee"))
    }

    @Test
    fun `查重忽略全角半角`() {
        // 全角字母 / 数字（Ｃｏｆｆｅｅ、１）看着跟半角很像，肉眼很难分
        assertEquals(comparisonKey("Ｃｏｆｆｅｅ"), comparisonKey("coffee"))
        assertEquals(comparisonKey("咖啡１"), comparisonKey("咖啡1"))
        // 控制组：原始串确实不相等
        assertNotEquals("Ｃｏｆｆｅｅ", "coffee")
    }

    @Test
    fun `查重忽略变音符号`() {
        assertEquals(comparisonKey("café"), comparisonKey("cafe"))
        assertNotEquals("café", "cafe")
    }

    @Test
    fun `不同的名字仍然要算成不同`() {
        // ⚠️ 这条是上面那几条的反面控制组：如果 comparisonKey 折得太狠
        //（比如把中文也折掉），所有名字都会撞在一起 → 任何新分类都建不出来
        assertNotEquals(comparisonKey("餐饮"), comparisonKey("交通"))
        assertNotEquals(comparisonKey("咖啡"), comparisonKey("咖啡店"))
    }

    @Test
    fun `空名字的比较形式是空串`() {
        // Repository / 编辑页靠这个判「名字还没填」，不能让它变成某个非空的东西
        assertEquals("", comparisonKey("   "))
    }

    @Test
    fun `代号取自 cleanedName，所以中间的多余空格必须先压掉`() {
        // 这条正是「两端算法要一致」的落点：iOS 的 newKey() 用的是 cleanedName，
        // 安卓这边只 trim 的话，「咖  啡」在两台设备上会得到两个不同的代号
        assertEquals("咖 啡", cleanedName("咖  啡"))
        assertNotEquals("只 trim 的结果居然和 cleanedName 一样，那这条就没有区分力了",
                        "咖  啡".trim(), cleanedName("咖  啡"))
    }
}
