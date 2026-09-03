package com.shize.expensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// 数据访问层。
//
// ⚠️⚠️ **每一条给界面看的查询都必须带 `deleted = 0`。**
// 漏一处，被删掉的记录就会在那个页面上冒出来 —— 而且只在「删过东西之后」才复现，
// 平时完全看不出来。给界面的查询一律走这里，不要在别处手写 SQL。
//
// ⚠️ 私密记录的过滤**不在这一层做**，在 Repository 那一层统一做（对位 iOS 那边
// 「过滤只在一处做」的规矩）。原因：能不能看见私密记录取决于运行时的锁定状态，
// 而 SQL 里塞一个开关会让每个查询都得记得带上它 —— 迟早漏。

@Dao
interface ExpenseDao {
    /// 给界面的：某个月的账目，按记账时间倒序
    @Query("SELECT * FROM expense WHERE deleted = 0 AND date >= :from AND date < :to ORDER BY date DESC")
    fun observeRange(from: Long, to: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expense WHERE deleted = 0 ORDER BY date DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expense WHERE id = :id AND deleted = 0")
    suspend fun find(id: String): ExpenseEntity?

    /// 同步用：**不带** deleted 过滤（合并时要看得到墓碑）
    @Query("SELECT * FROM expense WHERE id = :id")
    suspend fun findRaw(id: String): ExpenseEntity?

    @Query("SELECT * FROM expense WHERE dirty = 1")
    suspend fun dirty(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExpenseEntity)

    /// 推送成功后清 dirty。
    /// ⚠️⚠️ 必须带 `updatedAt = :updatedAt` 这个条件：推送在飞的时候用户可能又改了这条记录，
    /// 只按 id 清的话那次改动的 dirty 会被误清掉、**永远推不上去**，而且神不知鬼不觉。
    @Query("UPDATE expense SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearDirty(id: String, updatedAt: Long)

    /// 分类下面有几笔账。⚠️ 两个口径分开：能不能删按**全部**记录判，
    /// 显示的笔数按**看得见的**算。只数看得见的会让「某个分类底下只有私密记录」时
    /// 数出 0、于是允许删除 → 那几笔私密账当场悬空（iOS 那边踩过这个坑）
    @Query("SELECT COUNT(*) FROM expense WHERE deleted = 0 AND categoryKey = :key")
    suspend fun countAllInCategory(key: String): Int

    @Query("SELECT COUNT(*) FROM expense WHERE deleted = 0 AND categoryKey = :key AND isPrivate = 0")
    suspend fun countVisibleInCategory(key: String): Int
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag WHERE deleted = 0 AND isArchived = 0 ORDER BY sortOrder, createdAt")
    fun observeActive(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<TagEntity>>

    /// 合并时用来处理「两台设备各自建了同名标签」：按清理后的名字找同名的
    @Query("SELECT * FROM tag WHERE deleted = 0 AND name = :name")
    suspend fun findByName(name: String): List<TagEntity>

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun findRaw(id: String): TagEntity?

    @Query("SELECT * FROM tag WHERE dirty = 1")
    suspend fun dirty(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TagEntity)

    @Query("UPDATE tag SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearDirty(id: String, updatedAt: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun findRaw(id: String): CategoryEntity?

    @Query("SELECT * FROM category WHERE dirty = 1")
    suspend fun dirty(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CategoryEntity)

    @Query("UPDATE category SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearDirty(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM category WHERE deleted = 0")
    suspend fun count(): Int
}

@Dao
interface LinkDao {
    @Query("SELECT * FROM link WHERE deleted = 0")
    fun observeAll(): Flow<List<LinkEntity>>

    @Query("SELECT tagId FROM link WHERE deleted = 0 AND expenseId = :expenseId")
    suspend fun tagIdsOf(expenseId: String): List<String>

    @Query("SELECT * FROM link WHERE id = :id")
    suspend fun findRaw(id: String): LinkEntity?

    @Query("SELECT * FROM link WHERE dirty = 1")
    suspend fun dirty(): List<LinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<LinkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LinkEntity)

    @Query("UPDATE link SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearDirty(id: String, updatedAt: Long)

    /// 把关联从一个标签整体挪到另一个（合并同名标签时用）
    @Transaction
    suspend fun repoint(fromTagId: String, toTagId: String, now: Long) {
        for (l in linksOfTag(fromTagId)) {
            // 老的置墓碑、新的建出来，两条都标 dirty 好推给对端
            upsert(l.copy(deleted = true, updatedAt = now, dirty = true))
            val newId = LinkEntity.idOf(l.expenseId, toTagId)
            upsert(LinkEntity(newId, l.expenseId, toTagId, updatedAt = now, dirty = true))
        }
    }

    @Query("SELECT * FROM link WHERE deleted = 0 AND tagId = :tagId")
    suspend fun linksOfTag(tagId: String): List<LinkEntity>
}
