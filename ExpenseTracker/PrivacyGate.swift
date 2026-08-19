import SwiftUI
import LocalAuthentication

/// 私密记录的「门」。
///
/// 锁着的时候，整个 app 表现得像那些记录**根本不存在**——不只是列表里不显示，
/// 本月合计、笔数、统计页的每一个数字都要把它们排除掉。
///
/// ⚠️ 这一点是整个功能成立的前提，别图省事只改列表：
/// 如果只把行藏起来、合计仍旧算上私密的那几笔，别人把看得见的几行一加、
/// 发现跟顶上的总数对不上，立刻就知道你藏了东西、还知道藏了多少。
/// 藏东西这件事，露馅的从来不是被藏的内容，是对不上的那个数。
///
/// ⚠️ 它挡的是什么、挡不住什么：
/// 挡的是「别人拿着你已解锁的手机翻这个 app」。
/// **挡不住**能拿到手机文件或备份的人——私密记录仍然是明文躺在同一张表里，
/// 只是多了个 isPrivate 标记。要挡那种得整库加密，代价是每次开 app 都要解锁。
@Observable
final class PrivacyGate {
    /// 解锁状态。私有 setter：只能通过 unlock() / lock() 改，避免哪里手滑直接置成 true
    private(set) var isUnlocked = false

    init() {
        #if DEBUG
        // -unlockPrivate：开发期截图用。这台机器上没有模拟器窗口、点不了屏幕，
        // 连点三下这个手势没法手动做，只能靠启动参数把解锁态摆出来。见 DevFlags。
        if DevFlags.has("-unlockPrivate") { isUnlocked = true }
        #endif
    }

    func lock() { isUnlocked = false }

    /// 走系统的 Face ID / Touch ID；认不出来（戴口罩、光线差）自动退回输设备密码。
    ///
    /// ⚠️ 用 .deviceOwnerAuthentication 而不是 ...WithBiometrics：
    /// 后者没有密码兜底，戴个口罩就彻底进不去了。
    @MainActor
    func unlock() async {
        let context = LAContext()
        context.localizedFallbackTitle = "输入密码"

        let policy = LAPolicy.deviceOwnerAuthentication
        var error: NSError?
        guard context.canEvaluatePolicy(policy, error: &error) else {
            // 设备没设锁屏密码时会走到这里。没有任何凭据可验，就没有门可言，
            // 直接放行反而更诚实——否则这批记录会被永久锁死、自己也拿不回来。
            isUnlocked = true
            return
        }

        do {
            isUnlocked = try await context.evaluatePolicy(policy, localizedReason: "查看私密记录")
        } catch {
            // 用户点了取消、或者认证失败：安静地留在锁定态。
            // 故意不弹任何错误提示 —— 一弹提示就等于告诉旁边的人「这儿有东西」。
            isUnlocked = false
        }
    }
}
