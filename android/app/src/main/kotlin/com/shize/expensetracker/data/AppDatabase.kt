package com.shize.expensetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/// 本地库。对位 iOS 那边的 SwiftData。
///
/// ⚠️ 加字段/改表结构时**必须写迁移**（iOS 那边 SwiftData 能自动做轻量迁移，Room 不会）。
/// 图省事用 fallbackToDestructiveMigration 的话，用户升级 app 时账目会被**清空** ——
/// 这个 app 的数据只在两台手机和 VPS 上，清了就是真没了。所以这里故意不开那个开关。
@Database(
    entities = [ExpenseEntity::class, TagEntity::class, CategoryEntity::class, LinkEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun tagDao(): TagDao
    abstract fun categoryDao(): CategoryDao
    abstract fun linkDao(): LinkDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "expense.db"
            ).build().also { instance = it }
        }
    }
}
