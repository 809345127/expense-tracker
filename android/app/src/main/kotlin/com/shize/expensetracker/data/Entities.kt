package com.shize.expensetracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.math.BigDecimal

// 本地库的表结构。**跟同步协议一一对应**（协议见 server/README.md），
// 多出来的只有一个 `dirty` —— 那是本地专用、永远不上传的。
//
// ⚠️⚠️ 金额用 BigDecimal，绝不用 Double/Float。
// 记账 app 里 0.1 + 0.2 那种误差是不能接受的；iOS 那边用的是 Decimal，两边对得上。
// 存进 SQLite 时转成字符串（见下面的 Converters），传输时也是字符串。

/// 每张表都有的三个同步字段 + 一个本地字段。
/// 之所以不做成 Room 的 @Embedded：Room 对继承和嵌入的支持会让 DAO 查询写起来更绕，
/// 这个 app 只有四张表，直接在每个实体里重复这四行更好读。
interface Syncable {
    val id: String
    val updatedAt: Long   // 毫秒。任何本地改动都要更新它
    val deleted: Boolean  // 删除墓碑。删 = 置 true，**不是**把行删掉
    val dirty: Boolean    // 有未推送的本地改动。⚠️ 只在本地用，不进网络请求
}

@Entity(tableName = "expense", indices = [Index("date"), Index("dirty")])
data class ExpenseEntity(
    @PrimaryKey override val id: String,
    /// 金额。**正数 = 支出，负数 = 收入**（2026-10-01 加收入时定的，两端 + 协议一致）。
    /// ⚠️ 界面上一律不直接显示它的正负号 —— 显示用 `magnitude` + `isIncome`；
    ///    求和一律走 ui/Money.kt 的 `expenseSum()` / `incomeSum()`，**不许直接把 amount 加起来**：
    ///    收入混进去会把「本月支出」抵掉一块，而且看着像少记了账。
    val amount: BigDecimal,
    /// 分类**代号**（不是显示名）。对应 CategoryEntity.id
    val categoryKey: String,
    val note: String = "",
    /// 记账时间：这笔钱花出去的时刻
    val date: Long,
    /// 创建时间：这条记录写进库的时刻。编辑时不改
    val createdAt: Long,
    /// 私密记录。锁着的时候整个 app 当它不存在（列表、合计、统计、小组件全排除）
    val isPrivate: Boolean = false,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = false,
) : Syncable {
    /// 是不是一笔收入（金额是负数）
    val isIncome: Boolean get() = amount.signum() < 0

    /// 金额的绝对值，界面上显示用
    val magnitude: BigDecimal get() = amount.abs()
}

@Entity(tableName = "tag", indices = [Index("dirty")])
data class TagEntity(
    /// ⚠️ 是 UUID，不是名字。标签**可以改名**，用名字当 id 会让改名后所有关联失联
    @PrimaryKey override val id: String,
    val name: String,
    val colorIndex: Int = 0,
    val sortOrder: Int = 0,
    /// 停用但不删（历史统计还要用它）
    val isArchived: Boolean = false,
    val createdAt: Long,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = false,
) : Syncable

@Entity(tableName = "category", indices = [Index("dirty")])
data class CategoryEntity(
    /// ⚠️ id **就是分类代号 key**，不是另发的 UUID。
    /// 两台设备各自新建同名分类会算出同一个代号 → 自动并成一条；
    /// 发 UUID 就会变成两条一模一样的分类。而且代号一旦建好永不改（改名只改 name），
    /// 所以它天生稳定。历史账目的 categoryKey 存的就是它。
    @PrimaryKey override val id: String,
    val name: String,
    /// 图标名。⚠️ iOS 用的是 SF Symbols 的名字（如 `fork.knife`），安卓这边没有这套图标，
    /// 需要一张「SF Symbols 名 → Material 图标」的映射表，见 ui/CategoryIcons.kt
    val iconName: String,
    val colorIndex: Int = 0,
    val sortOrder: Int = 0,
    /// 兜底分类（「其他」）。删不掉，账目的分类被删时落到它上面
    val isFallback: Boolean = false,
    val createdAt: Long,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = false,
) : Syncable {
    /// 收入分类：代号带「收入:」前缀（见 CategoryKind）。建好就定了，跟 id 一样永不改
    val isIncome: Boolean get() = CategoryKind.isIncome(id)
}

/// 支出分类 / 收入分类（2026-10-01 加收入时加的）。
///
/// 收入分类的代号一律是「收入:」+ 名字（`收入:工资`），支出分类照旧（`餐饮`）。
/// 这样**同步协议一个字段都不用加、服务器一行都不用改**：分类的 id 就是代号，前缀跟着 id 走。
/// 加字段的话有个坑：还没升级的那台设备不认识这个字段，它一改这个分类再推上去，
/// 字段就被它丢了 —— 收入分类悄悄变回支出分类，而且没有任何报错。
///
/// ⚠️ 跟 iOS `CategoryKind`（Category.swift）**一个字都不能差**，预设清单也必须一样
object CategoryKind {
    const val INCOME_PREFIX = "收入:"
    fun isIncome(key: String) = key.startsWith(INCOME_PREFIX)

    /// 收入分类的排序号从这里起跳，跟支出分类（从 0 起）分开两段、各排各的
    const val INCOME_SORT_BASE = 1000

    const val INCOME_FALLBACK_KEY = "收入:其他"

    /// 收入预设。⚠️ 代号、名字、图标、颜色下标跟 iOS `CategorySeed.builtInIncome` 逐项一致
    data class Seed(val key: String, val name: String, val icon: String, val color: Int)
    val builtInIncome = listOf(
        Seed("收入:工资", "工资", "banknote.fill", 10),
        Seed("收入:奖金", "奖金", "star.fill", 12),
        Seed("收入:理财", "理财", "chart.line.uptrend.xyaxis", 11),
        Seed("收入:红包", "红包", "envelope.fill", 5),
        Seed("收入:退款", "退款", "arrow.uturn.backward.circle.fill", 1),
        Seed("收入:兼职", "兼职", "briefcase.fill", 6),
        Seed("收入:其他", "其他收入", "ellipsis.circle.fill", 9),
    )
}

/// 「某笔账挂了某个标签」这件事本身，是一条独立记录。
///
/// ⚠️ 为什么不做成 Room 的多对多关系表就完了：**取消一个标签这个动作要能同步出去**。
/// 取消标签时账目那条记录的字段一个都没变，所以必须让关联自己有删除墓碑和 updatedAt。
@Entity(tableName = "link", indices = [Index("expenseId"), Index("tagId"), Index("dirty")])
data class LinkEntity(
    /// ⚠️ id 是拼出来的：`<账目id>:<标签id>`。确定性 —— 两台设备各自给同一笔账
    /// 打同一个标签，算出的 id 相同、自动并成一条。
    @PrimaryKey override val id: String,
    val expenseId: String,
    val tagId: String,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = false,
) : Syncable {
    companion object {
        fun idOf(expenseId: String, tagId: String) = "$expenseId:$tagId"
    }
}

class Converters {
    /// 金额存成字符串。⚠️ 不能存成 REAL —— 那就是 double，误差就回来了
    @TypeConverter fun decimalToString(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun stringToDecimal(v: String?): BigDecimal? = v?.let { BigDecimal(it) }
}
