package com.shize.expensetracker.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

/// 本地设置。用 DataStore 而不是 SharedPreferences —— 后者是老写法，
/// 读写在主线程上、还没有类型安全。
///
/// ⚠️ 同步服务的地址和 token 存在这里（用户在「同步设置」页里粘一次），
/// **不写进代码、不进仓库**：这个仓库是公开的，而那台 VPS 上还跑着别的东西。
class Settings(private val context: Context) {
    private object Keys {
        val url = stringPreferencesKey("sync_url")
        val token = stringPreferencesKey("sync_token")
        val lastRev = longPreferencesKey("last_rev")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastError = stringPreferencesKey("last_error")
    }

    val url: Flow<String> = context.store.data.map { it[Keys.url] ?: "" }
    val token: Flow<String> = context.store.data.map { it[Keys.token] ?: "" }
    val lastSyncAt: Flow<Long> = context.store.data.map { it[Keys.lastSyncAt] ?: 0L }
    val lastError: Flow<String> = context.store.data.map { it[Keys.lastError] ?: "" }

    suspend fun urlNow(): String = url.first()
    suspend fun tokenNow(): String = token.first()
    suspend fun lastRev(): Long = context.store.data.first()[Keys.lastRev] ?: 0L

    suspend fun setServer(url: String, token: String) {
        context.store.edit {
            // 末尾的斜杠 Retrofit 要求有，用户粘的时候大概率不会带 —— 这里补上，
            // 不然是一个「地址看着完全正确、请求却 404」的坑
            it[Keys.url] = url.trim().trimEnd('/') + "/"
            it[Keys.token] = token.trim()
        }
    }

    suspend fun setLastRev(rev: Long) = context.store.edit { it[Keys.lastRev] = rev }.let {}

    suspend fun recordSuccess() = context.store.edit {
        it[Keys.lastSyncAt] = System.currentTimeMillis()
        it[Keys.lastError] = ""
    }.let {}

    /// ⚠️ 同步失败要**留痕**。静默失败是最坏的形态：两台手机数据不一样，
    /// 而界面上一切正常、你根本不知道该去修什么
    suspend fun recordFailure(message: String) = context.store.edit {
        it[Keys.lastError] = message
    }.let {}

    /// 重置本地游标：下次同步会从头拉一遍（排障用）
    suspend fun resetCursor() = context.store.edit { it[Keys.lastRev] = 0L }.let {}
}
