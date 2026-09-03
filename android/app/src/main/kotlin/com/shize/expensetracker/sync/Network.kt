package com.shize.expensetracker.sync

import com.shize.expensetracker.data.Settings
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/// 按当前设置里的地址和 token 现造一个 API 客户端。
///
/// ⚠️ 不做成单例：地址和 token 是用户在设置页里随时能改的，
/// 缓存住的话改完还得重启 app 才生效 —— 那种「改了没反应」的坑很难查。
object Network {
    private val json = Json {
        ignoreUnknownKeys = true   // 服务端以后加字段不至于让老客户端直接崩
        encodeDefaults = true      // deleted=false 这种默认值也要发出去，别让服务端猜
    }

    suspend fun api(settings: Settings): SyncApi? {
        val base = settings.urlNow()
        val token = settings.tokenNow()
        if (base.isBlank() || token.isBlank()) return null

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                )
            })
            .build()

        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SyncApi::class.java)
    }
}
