# R8（release 构建的代码压缩 / 混淆）规则。
#
# ⚠️⚠️ **这个文件里的规则「够不够」，唯一的判据是把 release 包装上去真跑一遍。**
# R8 删错东西**不会在构建时报错** —— 它就是把某个类摇掉了，然后运行时才炸，
# 或者更坏：反序列化静默拿到空对象、同步看起来跑了但一条都没落。
# 所以改完这里必须重新装 release 包、真同步一次、核对条数。
#
# 为什么开 R8（`isMinifyEnabled = true`）而不是图省事关掉：
# material-icons-extended 有两千多个图标、全是代码（ImageVector 构造），
# debug 包因此有 72MB；这些图标绝大多数没用到，R8 能把它们摇掉。
# 对一个装在自己手机上天天开的 app，包大小和冷启动是实打实的体验。

# ---------------------------------------------------------------- kotlinx.serialization
#
# 同步协议的那四个 DTO 全靠它。⚠️ 这是**最容易被 R8 咬到**的一块：
# @Serializable 生成的是伴生对象里的 `$$serializer`，静态分析看不出谁在用它，
# 不 keep 的话运行时抛 SerializationException —— 而症状是「同步失败」，
# 看着像服务端坏了或者网络问题。
#
# 新版库自带 consumer rules，这里再写一遍是**冗余但便宜**的保险：
# 库哪天改了打包方式，这几行还在。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# 我们自己那几个 DTO 整个留着（一共四个类，留下来的体积可以忽略）
-keep class com.shize.expensetracker.sync.**Dto { *; }
-keep class com.shize.expensetracker.sync.Payload { *; }
-keep class com.shize.expensetracker.sync.PushResult { *; }

# ---------------------------------------------------------------- Retrofit / OkHttp
#
# Retrofit 靠**运行时反射**读接口上的注解和泛型签名来生成实现，
# 签名信息被抹掉的话它拼不出请求（而报错很难读）。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# 我们那个 API 接口本身
-keep interface com.shize.expensetracker.sync.SyncApi { *; }
# OkHttp 在非 Android 平台上引用的可选类，摇不掉但也用不到，别刷警告
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------- Room
#
# 生成的 Impl 类是靠反射按名字找的（`AppDatabase_Impl`）。
# Room 自带 consumer rules，同样再兜一层。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------- Glance（桌面小组件）
#
# ⚠️ Receiver 是系统按**清单里的类名**反射实例化的，R8 看不到任何调用点 ——
# 不 keep 的话它会被改名/删掉，小组件在桌面上直接变成「无法加载」。
-keep class com.shize.expensetracker.widget.ExpenseWidgetReceiver { *; }
-keep class com.shize.expensetracker.widget.ExpenseWidget { *; }

# ---------------------------------------------------------------- WorkManager
#
# 同理：Worker 是系统按类名反射构造的。
-keep class com.shize.expensetracker.sync.SyncWorker { <init>(...); }

# ---------------------------------------------------------------- 其它
#
# Application / Activity 由系统按清单里的名字实例化
-keep class com.shize.expensetracker.App { *; }
-keep class com.shize.expensetracker.MainActivity { *; }
