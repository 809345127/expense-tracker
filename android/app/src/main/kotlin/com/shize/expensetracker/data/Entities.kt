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
) : Syncable

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
) : Syncable

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
