package com.shize.expensetracker.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// 网络传输的形状。**必须跟 server/model.go 一字对应**，改任何一边都要同时改另一边。
//
// ⚠️⚠️ amount 是 String，不是 Double。
// JSON 的 number 在很多语言里就是 double，一过就有误差。iOS 那边用 Decimal、
// 这边用 BigDecimal，中间统一走字符串，全程不碰浮点。
//
// ⚠️ `dirty` 不在这里 —— 它是本地专用字段，永远不上传。

@Serializable
data class ExpenseDto(
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    val rev: Long = 0,
    val amount: String,
    @SerialName("category_key") val categoryKey: String,
    val note: String = "",
    val date: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("is_private") val isPrivate: Boolean = false,
)

@Serializable
data class TagDto(
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    val rev: Long = 0,
    val name: String,
    @SerialName("color_index") val colorIndex: Int = 0,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class CategoryDto(
    /// ⚠️ 这个 id 就是分类代号 key
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    val rev: Long = 0,
    val name: String,
    @SerialName("icon_name") val iconName: String,
    @SerialName("color_index") val colorIndex: Int = 0,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_fallback") val isFallback: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class LinkDto(
    /// ⚠️ 这个 id 是 `<账目id>:<标签id>` 拼出来的
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    val rev: Long = 0,
    @SerialName("expense_id") val expenseId: String,
    @SerialName("tag_id") val tagId: String,
)

@Serializable
data class Payload(
    /// 拉取时：本页可以安全推进到的游标（**不是**库里最大的 rev，见 server/README.md）
    val rev: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val expenses: List<ExpenseDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val links: List<LinkDto> = emptyList(),
) {
    val size: Int get() = expenses.size + tags.size + categories.size + links.size
    val isEmpty: Boolean get() = size == 0
}

@Serializable
data class PushResult(
    val rev: Long = 0,
    val received: Int = 0,
    val applied: Int = 0,
    /// ⚠️⚠️ **被服务器当成旧数据丢掉的那些记录的 id。非空必须让用户看见，不能咽掉。**
    ///
    /// 它几乎只有一个原因：**这台设备的时钟比另一台慢**。
    /// 时钟慢的那台每次推都会被判成旧的 → 它的改动**永久丢失、而且毫无迹象**
    /// （界面上一切正常，只是那笔账在另一台上永远不出现）。
    /// 协议原文见 server/README.md「POST /v1/changes」那一节。
    ///
    /// 注意区分：原样重推同一批数据时 `applied` 也会是 0，但那是正常的、`stale` 是空的
    /// —— 服务端会现查库里那条的 updated_at 来分辨这两种情况。
    val stale: List<String> = emptyList(),
)

interface SyncApi {
    @GET("v1/health")
    suspend fun health(): String

    /// 拉取 rev 比 since 大的全部记录（**含删除墓碑** —— 要靠它才知道有东西被删了）
    @GET("v1/changes")
    suspend fun pull(@Query("since") since: Long, @Query("limit") limit: Int = 500): Payload

    @POST("v1/changes")
    suspend fun push(@Body body: Payload): PushResult
}
