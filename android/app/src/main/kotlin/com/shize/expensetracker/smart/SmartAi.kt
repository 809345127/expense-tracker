package com.shize.expensetracker.smart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.shize.expensetracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

// 智能记账里跟 Android 打交道的部分：key 存取、调 DeepSeek、本地读字、四级降级调度。
// 纯逻辑（规则拆分、提示词、解析回答）在 SmartParse.kt。

// ---------------------------------------------------------------- key 存取

/// DeepSeek 的 key。**加密后**存在本 app 私有的 SharedPreferences 里，
/// 加密用的钥匙放在系统的 Android Keystore（硬件保护、导不出来）—— 对位 iOS 的钥匙串。
///
/// ⚠️ 安全要点（用户问过「安全问题如何解决」）：
///   · Keystore 里的钥匙只在这台手机上，导不出来；就算有人拷走了 prefs 文件，没有钥匙也解不开
///   · 这个 prefs 文件在备份规则里排除了（见 res/xml/backup_rules.xml），不进云备份、不进换机迁移
///   · 不写日志、不进导出、不同步到服务器、不打进安装包
///   · 界面上只显示末 4 位
object DeepSeekKeyStore {
    private const val PREFS = "smart_secret"
    private const val ALIAS = "deepseek_key_wrap"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun load(ctx: Context): String? = runCatching {
        val p = prefs(ctx)
        val iv = Base64.decode(p.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val ct = Base64.decode(p.getString("ct", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun save(ctx: Context, key: String): Boolean = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    fun delete(ctx: Context) {
        prefs(ctx).edit().clear().commit()
    }

    /// 设置页显示用：`sk-••••a3f9`。完整的 key 存进去之后界面上再也看不到
    fun masked(key: String): String = (if (key.startsWith("sk-")) "sk-" else "") + "••••" + key.takeLast(4)
}

/// 模型名 + 接口地址。模型名不是秘密，放普通 prefs
object DeepSeekConfig {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("smart_config", Context.MODE_PRIVATE)

    /// 文字模型。`deepseek-chat` 是 DeepSeek 一直保留的老名字
    fun textModel(ctx: Context) = prefs(ctx).getString("text_model", null) ?: "deepseek-chat"
    /// 看图模型。⚠️ 名字来自第三方资料，没经过官方核实，所以做成可改的（同 iOS）
    fun visionModel(ctx: Context) = prefs(ctx).getString("vision_model", null) ?: "deepseek-flash"

    fun setModels(ctx: Context, text: String, vision: String) {
        prefs(ctx).edit().apply {
            if (text.isNotBlank()) putString("text_model", text.trim())
            if (vision.isNotBlank()) putString("vision_model", vision.trim())
        }.apply()
    }

    /// 接口地址。正式包写死 DeepSeek 官方；debug 包可以用 adb 指向本机假服务器做测试：
    /// `adb shell am start -n <包名>/com.shize.expensetracker.MainActivity --es deepseekBase http://10.0.2.2:18930`
    fun baseUrl(ctx: Context): String {
        if (BuildConfig.DEBUG) prefs(ctx).getString("debug_base", null)?.let { return it.trimEnd('/') }
        return "https://api.deepseek.com"
    }

    fun setDebugBase(ctx: Context, url: String?) {
        if (!BuildConfig.DEBUG) return
        prefs(ctx).edit().apply { if (url.isNullOrBlank()) remove("debug_base") else putString("debug_base", url) }.apply()
    }
}

// ---------------------------------------------------------------- 调 DeepSeek

object DeepSeekClient {
    private val json = "application/json".toMediaType()

    private fun client(timeoutSec: Long) = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .callTimeout(timeoutSec + 15, TimeUnit.SECONDS)
        .build()

    /// 发一次识别请求，返回模型吐出来的 JSON 原文
    suspend fun complete(
        ctx: Context, key: String, model: String, system: String, userText: String,
        images: List<ByteArray>, timeoutSec: Long,
    ): String = withContext(Dispatchers.IO) {
        val userContent: JsonElement = if (images.isEmpty()) {
            // 纯文字时 content 直接给字符串：老的文字模型不一定认数组形式（同 iOS）
            JsonPrimitive(userText)
        } else buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", userText) })
            for (img in images) add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP))
                })
            })
        }
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", userContent) })
            })
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("temperature", 0)
            put("stream", false)
        }
        val req = Request.Builder()
            .url(DeepSeekConfig.baseUrl(ctx) + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(json))
            .build()
        val (code, text) = send(req, timeoutSec)
        if (code != 200) throw SmartError(describe(code, text))
        try {
            Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        } catch (e: Exception) {
            throw SmartError("DeepSeek 返回的格式看不懂")
        }
    }

    /// 设置页「测试」用：列出这个 key 能用的模型
    suspend fun listModels(ctx: Context, key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(DeepSeekConfig.baseUrl(ctx) + "/models")
            .header("Authorization", "Bearer $key").build()
        val (code, text) = send(req, 20)
        if (code != 200) throw SmartError(describe(code, text))
        runCatching {
            Json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.sorted()
        }.getOrDefault(emptyList())
    }

    private fun send(req: Request, timeoutSec: Long): Pair<Int, String> = try {
        client(timeoutSec).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    } catch (e: InterruptedIOException) {
        throw SmartError("DeepSeek 超时没回")
    } catch (e: UnknownHostException) {
        throw SmartError("手机没联网")
    } catch (e: IOException) {
        throw SmartError("连不上 DeepSeek（${e.message}）")
    }

    /// 状态码翻人话（跟 iOS `DeepSeekClient.describe` 同一张表）
    fun describe(code: Int, body: String): String {
        val raw = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        }.getOrDefault("")
        val detail = if (raw.isEmpty()) "" else "：${raw.take(80)}"
        return when (code) {
            401 -> "key 不对或已作废（401）"
            402 -> "DeepSeek 余额不足（402）"
            429 -> "请求太频繁，等一下再试（429）"
            400, 422 -> "请求被拒（$code）$detail"
            in 500..599 -> "DeepSeek 服务繁忙（$code）"
            else -> "DeepSeek 返回 $code$detail"
        }
    }
}

// ---------------------------------------------------------------- 图片：读进来、压缩、读字

object SmartImages {
    /// 从 content:// 读一张图，按 EXIF 摆正，长边压到 maxSide 以内（原图动辄几千像素，内存和上传都扛不住）
    suspend fun load(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            // ⚠️ 相机拍的照片像素是横着存的、靠 EXIF 标方向。不摆正的话读字会读不出来
            val rotation = ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val upright = if (rotation == 0f) bmp
                else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            val longest = maxOf(upright.width, upright.height)
            if (longest <= maxSide) upright
            else Bitmap.createScaledBitmap(upright, upright.width * maxSide / longest, upright.height * maxSide / longest, true)
        }.getOrNull()
    }

    fun jpeg(bmp: Bitmap, quality: Int = 70): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /// 本地读字（ML Kit，打包进 app 的中文模型，离线、免费）。
    suspend fun ocr(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val boxes = r.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { OcrBox(l.text, it.left.toFloat(), it.top.toFloat(), it.bottom.toFloat()) }
                }
                cont.resume(joinRows(boxes))
                recognizer.close()
            }
            .addOnFailureListener { cont.resume(""); recognizer.close() }
    }
}

/// 读出来的一小段字和它在图上的位置（y 往下增大）
data class OcrBox(val text: String, val left: Float, val top: Float, val bottom: Float)

/// 把读出来的一段段字**按图上的行**拼回去：同一行的从左到右用两个空格连起来，行与行换行。
///
/// ⚠️ 为什么要拼行：小票上「饭团 ……… 8.50」左右两截是分开读出来的，而且金额那截的上边缘
/// 常常比商品名高一两个像素 —— 直接按上边缘排序会排成「8.50 ↵ 饭团」，
/// 第 3 级本地规则就会把金额配给下一行的商品（模拟器上实测踩到）。
/// 判同一行：竖直方向的中线落在这一行的上下边之间。
/// ⚠️ iOS `LocalOCR.joinRows` 是同一个算法
fun joinRows(boxes: List<OcrBox>): String {
    val rows = mutableListOf<MutableList<OcrBox>>()
    for (b in boxes.sortedBy { (it.top + it.bottom) / 2 }) {
        val mid = (b.top + b.bottom) / 2
        val row = rows.lastOrNull()
        val first = row?.first()
        if (first != null && mid > first.top && mid < first.bottom) row.add(b) else rows.add(mutableListOf(b))
    }
    return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString("  ") { it.text } }
}

// ---------------------------------------------------------------- 总调度：按四级降级跑一遍

object SmartRecognizer {
    /// 跟 iOS `SmartRecognizer.run` 同一个顺序
    suspend fun run(ctx: Context, text: String, images: List<Bitmap>, categories: List<SmartCategory>,
                    now: Long = System.currentTimeMillis()): SmartResult {
        // 本地读字先做：第 1、2、3 级都用得上，而且不联网、不花钱
        val ocr = images.map { SmartImages.ocr(it) }

        var reason = ""
        val key = DeepSeekKeyStore.load(ctx)
        if (key != null) {
            val system = AIPrompt.system(categories, now)
            val user = AIPrompt.user(text, ocr)

            // 第 1 级：有图才走
            if (images.isNotEmpty()) {
                try {
                    val jpegs = withContext(Dispatchers.Default) { images.map { SmartImages.jpeg(it) } }
                    val out = DeepSeekClient.complete(ctx, key, DeepSeekConfig.visionModel(ctx), system, user, jpegs, 90)
                    return SmartResult(SmartLevel.AiVision, AIPrompt.decode(out, categories, now))
                } catch (e: SmartError) {
                    reason = "看图没成功：" + e.message
                }
            }
            // 第 2 级：只发文字。有图但图里一个字都没读出来、用户也没写字时，发了也白发
            if (user != AIPrompt.NO_TEXT) {
                try {
                    val out = DeepSeekClient.complete(ctx, key, DeepSeekConfig.textModel(ctx), system, user, emptyList(), 60)
                    return SmartResult(SmartLevel.AiText, AIPrompt.decode(out, categories, now), reason)
                } catch (e: SmartError) {
                    val msg = e.message ?: ""
                    // 两级报的是同一个原因（比如 key 失效）就只说一遍
                    reason = when {
                        reason.endsWith(msg) -> reason
                        reason.isEmpty() -> msg
                        else -> "$reason；文字也没成功：$msg"
                    }
                }
            }
        } else {
            reason = "还没填 DeepSeek key"
        }

        // 第 3 级：本地规则。用户写的字逐笔拆；每张图单独看 —— 是小票就只算一笔，不是再逐笔拆
        val drafts = RuleParser.parse(text, categories, now) + ocr.flatMap {
            RuleParser.parseReceipt(it, categories, now) ?: RuleParser.parse(it, categories, now)
        }
        if (drafts.isNotEmpty()) return SmartResult(SmartLevel.Rules, drafts, reason)
        // 第 4 级
        return SmartResult(SmartLevel.Nothing, emptyList(), reason)
    }
}
