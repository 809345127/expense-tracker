package com.shize.expensetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shize.expensetracker.App
import com.shize.expensetracker.data.*
import com.shize.expensetracker.sync.Network
import com.shize.expensetracker.sync.SyncEngine
import com.shize.expensetracker.sync.SyncWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val settings = App.from(app).settings
    private val db = App.from(app).database

    val url = settings.url.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val token = settings.token.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val lastSyncAt = settings.lastSyncAt.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val lastRev = settings.lastRevFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val lastError = settings.lastError.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val _pending = MutableStateFlow(-1)
    val pending: StateFlow<Int> = _pending

    fun save(url: String, token: String) = viewModelScope.launch {
        settings.setServer(url, token)
    }

    fun test() = viewModelScope.launch {
        _busy.value = true
        _message.value = try {
            val api = Network.api(settings)
            if (api == null) "地址或 token 还没填" else {
                val r = api.health()
                if (r.contains("ok")) "连上了 ✓" else "连上了但返回不对：${r.take(60)}"
            }
        } catch (e: Exception) {
            "连不上：${e.message ?: e.javaClass.simpleName}"
        }
        _busy.value = false
    }

    fun syncNow() = viewModelScope.launch {
        _busy.value = true
        _message.value = try {
            val api = Network.api(settings)
            if (api == null) "地址或 token 还没填" else {
                val r = SyncEngine(api, db, settings).syncOnce()
                if (r.stale.isEmpty()) {
                    settings.recordSuccess()
                    "拉下来 ${r.pulled} 条 / 推上去 ${r.pushed} 条"
                } else {
                    // ⚠️ 不能只说「同步完成」—— 那几条改动已经永久丢了
                    settings.recordFailure(SyncWorker.staleMessage(r.stale.size))
                    "拉下来 ${r.pulled} 条 / 推上去 ${r.pushed} 条。" +
                            SyncWorker.staleMessage(r.stale.size)
                }
            }
        } catch (e: Exception) {
            // ⚠️ 失败要留痕，静默失败是最坏的形态
            settings.recordFailure(e.message ?: e.javaClass.simpleName)
            "同步失败：${e.message ?: e.javaClass.simpleName}"
        }
        refreshPending()
        _busy.value = false
    }

    /// 排障：游标归零，下次从头拉
    fun resetCursor() = viewModelScope.launch {
        settings.resetCursor()
        _message.value = "游标已归零，下次同步会从头拉一遍"
    }

    fun refreshPending() = viewModelScope.launch {
        _pending.value = db.expenseDao().dirty().size + db.tagDao().dirty().size +
                db.categoryDao().dirty().size + db.linkDao().dirty().size
    }
}
