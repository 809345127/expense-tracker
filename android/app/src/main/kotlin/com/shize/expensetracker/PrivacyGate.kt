package com.shize.expensetracker

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/// 私密记录的「门」。**对位 iOS 那边的 `PrivacyGate.swift`，行为要一致。**
///
/// 锁着的时候，整个 app 表现得像那些记录**根本不存在** —— 不只是列表里不显示，
/// 本月合计、笔数、统计页的每一个数字、导出的内容、桌面小组件，全部要把它们排除掉。
///
/// ⚠️ 这一点是整个功能成立的前提，别图省事只改列表：
/// 如果只把行藏起来、合计仍旧算上私密那几笔，别人把看得见的几行一加、
/// 发现跟顶上的总数对不上，立刻就知道你藏了东西、还知道藏了多少。
/// **露馅的从来不是被藏的内容，是对不上的那个数。**
///
/// ⚠️ 它挡的是什么、挡不住什么：
/// 挡的是「别人拿着你已解锁的手机翻这个 app」。
/// **挡不住**能拿到手机文件或备份的人 —— 私密记录仍然是明文躺在同一张表里，
/// 只是多了个 isPrivate 标记。要挡那种得整库加密，代价是每次开 app 都要解锁。
///
/// ⚠️⚠️ **为什么是全进程一个实例**（挂在 App 上，不是每个 ViewModel 各存一份）：
/// 解锁状态必须是**唯一的一份**。明细页和统计页各存一个的话，会出现
/// 「明细页锁着、统计页开着」—— 两个页面的总额当场对不上，等于自己把上面那条红线破了。
class PrivacyGate {

    private val _unlocked = MutableStateFlow(false)
    /// ⚠️ 默认锁着 —— 跟 iOS 一致
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /// 系统认证界面正开着。
    ///
    /// ⚠️⚠️ **这个标记是为了不自锁死**，别删。自动上锁挂在 Activity 的 `onStop` 上，
    /// 而**指纹/密码那个界面本身就会把 Activity 顶到后台**（走设备密码那条路时它是另一个
    /// 系统 Activity）—— 不挡一下的话，刚弹出解锁框就把自己锁了，用户永远解不开。
    /// iOS 那边同一个坑：`scenePhase` 只能判 `.background`，写成 `!= .active` 会自锁死
    /// （Face ID 弹窗本身就会让 app 变 `.inactive`）。
    @Volatile private var authenticating = false

    fun lock() { _unlocked.value = false }

    /// 切到后台时自动上锁。⚠️ 认证界面开着的时候**跳过**（见 authenticating 的注释）
    fun lockOnBackground() {
        if (!authenticating) _unlocked.value = false
    }

    /// 弹系统的指纹 / 人脸，认不出来（手指湿、戴口罩）自动退回输锁屏密码。
    ///
    /// ⚠️ 用 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` 而不是只要生物识别：
    /// 后者没有密码兜底，手指一破皮就彻底进不去了。
    /// （minSdk 33，所以不用写「API 30 以下这两个不能一起用」那段分支。）
    suspend fun unlock(activity: FragmentActivity): Boolean {
        if (_unlocked.value) return true
        // ⚠️ 连点两下解锁按钮不能叠出两个系统弹框（第二个会把第一个顶掉，
        // 而第一个那条协程还挂在那儿等回调，永远等不到）
        if (authenticating) return false

        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (BiometricManager.from(activity).canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit

            // 这台机器压根没设锁屏密码、也没录指纹。没有任何凭据可验，就没有门可言 ——
            // 直接放行反而更诚实（跟 iOS 一致）：否则这批记录会被永久锁死、自己也拿不回来。
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                _unlocked.value = true
                return true
            }

            else -> return false
        }

        authenticating = true
        try {
            val ok = prompt(activity)
            _unlocked.value = ok
            return ok
        } finally {
            authenticating = false
        }
    }

    private suspend fun prompt(activity: FragmentActivity): Boolean = suspendCoroutine { cont ->
        var done = false
        fun finish(v: Boolean) { if (!done) { done = true; cont.resume(v) } }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    finish(true)

                /// ⚠️ 认证失败 / 用户取消，**安静地留在锁定态，不弹任何错误提示** ——
                /// 一弹提示就等于告诉旁边的人「这儿有东西」。跟 iOS 一致。
                override fun onAuthenticationError(code: Int, msg: CharSequence) = finish(false)

                /// 单次没认出来（换个手指再试）。⚠️ 这里**不能 resume** ——
                /// 系统还开着框让人重试，这时候结束协程会让弹窗和状态对不上
                override fun onAuthenticationFailed() = Unit
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("查看私密记录")
                .setSubtitle("验证一下是你本人")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                // ⚠️ 允许了 DEVICE_CREDENTIAL 就**不能**再设 negativeButtonText，
                // 两个一起给会直接抛 IllegalArgumentException（取消按钮由系统那套出）
                .build()
        )
    }
}

/// 给非 Activity 的地方（比如小组件）用的只读判断：这台机器有没有可用的锁。
/// 没有的话「私密」这个开关其实形同虚设，界面上要说清楚。
fun hasDeviceLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    ) == BiometricManager.BIOMETRIC_SUCCESS
