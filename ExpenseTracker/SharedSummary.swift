import Foundation
import Security

// MARK: - app 与桌面小组件之间的数据通道
//
// ## 为什么走钥匙串这种野路子
//
// 正常做法是 App Group（共享容器 / `UserDefaults(suiteName:)`）。**免费 Apple 账号用不了**：
// 2026-08-20 实测，苹果签发的描述文件里 `com.apple.security.application-groups` 这个键**在**，
// 但值是**空数组** —— 能力给了、一个组都注册不了，代码里写任何组名都会在安装时被拒。
//
// 同一份描述文件里 `keychain-access-groups = <TeamID>.*` 是**给了**的，所以改走钥匙串：
// app 把一小段摘要写进共享组，小组件读出来。摘要只有几百字节，钥匙串装得下。
//
// ⚠️ 这条路的代价：钥匙串不是设计来存业务数据的，容量小、写入比文件慢。
// 所以**只放渲染小组件必需的那几个数字**，不要顺手把明细塞进来。
//
// ## ⚠️ 私密记录永远不进这里
//
// 小组件摆在桌面上，谁拿起手机都看得见，比 app 里更暴露。
// 所以摘要**恒按锁定态计算** —— 不管 app 里当时有没有解锁，写进去的都是不含私密记录的数字。
// 这不是开关，是写死的（`ExpenseSummary.make` 只接受已经过滤过的数组）。
//
// 理由跟「藏记录必须连合计一起藏」同源：只要小组件上的总额和 app 锁定态对不上，
// 别人一比就知道你藏了东西、还知道藏了多少。

/// 小组件要显示的全部内容。刻意做得很小
struct ExpenseSummary: Codable, Equatable {
    struct Slice: Codable, Equatable {
        var name: String
        var amount: Decimal
    }

    // ⚠️ 金额用 Decimal 不用 Double。这是整个项目的规矩（见 Expense.amount 的注释）：
    // Double 存 6923.97 会变成 6923.969999999999，虽然格式化之后看不出来，
    // 但只要哪天拿它做加减比较，误差就会冒出来 —— 记账 app 最不能忍的就是这类静默错账。
    var monthLabel: String      // 「2026年8月」
    var total: Decimal          // 本月支出（**不含**私密记录）
    var count: Int              // 笔数（同上）
    var top: [Slice]            // 金额最高的几个分类
    var updatedAt: Date

    static let empty = ExpenseSummary(monthLabel: "", total: 0, count: 0, top: [], updatedAt: .distantPast)
}

/// 钥匙串通道。app 只调 `save`，小组件只调 `load`
enum SharedSummaryStore {
    private static let service = "com.shize.ExpenseTracker.summary"
    private static let account = "current-month"

    // MARK: 团队前缀

    /// 共享组必须写成 `<TeamID>.xxx`，而 TeamID 不能硬编码进源码
    /// —— 这个仓库是公开的，团队 ID 属于个人身份信息。
    ///
    /// 破法：往钥匙串里放一条**不指定 access group** 的条目，再把它读回来，
    /// 读回来的 `kSecAttrAccessGroup` 就是系统给的默认组 `<TeamID>.<BundleID>`，
    /// 取第一个点之前那段就是 TeamID。app 和小组件各自算各自的，结果一样。
    private static let teamPrefix: String? = {
        let probeQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.shize.ExpenseTracker.teamprobe",
            kSecAttrAccount as String: "probe",
        ]
        var read = probeQuery
        read[kSecReturnAttributes as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        var status = SecItemCopyMatching(read as CFDictionary, &item)
        if status == errSecItemNotFound {
            var add = probeQuery
            add[kSecValueData as String] = Data()
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
            status = SecItemCopyMatching(read as CFDictionary, &item)
        }
        guard status == errSecSuccess,
              let attrs = item as? [String: Any],
              let group = attrs[kSecAttrAccessGroup as String] as? String,
              let dot = group.firstIndex(of: ".")
        else { return nil }
        return String(group[..<dot])
    }()

    /// app 和小组件共用的那个组
    static var accessGroup: String? {
        teamPrefix.map { "\($0).expensetracker.shared" }
    }

    // MARK: 读写

    /// 写入摘要。app 侧在数据变化时调。
    /// - Returns: 成功与否。⚠️ 调用方要看返回值——写失败是静默的，
    ///   不看的话小组件会一直显示旧数据、而你以为它在更新。
    @discardableResult
    static func save(_ summary: ExpenseSummary) -> Bool {
        guard let group = accessGroup,
              let data = try? JSONEncoder().encode(summary) else { return false }

        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: group,
        ]
        // 先删再加，比 SecItemUpdate 少一个「不存在」的分支
        SecItemDelete(base as CFDictionary)

        var add = base
        add[kSecValueData as String] = data
        // ⚠️ 必须 AfterFirstUnlock，不能用默认的 WhenUnlocked：
        // 小组件在锁屏状态下也会被系统唤起刷新，用 WhenUnlocked 那时读不到、会显示空白。
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        lastStatus = SecItemAdd(add as CFDictionary, nil)
        return lastStatus == errSecSuccess
    }

    /// 最后一次写入的系统返回码。排查时看它，别只看 true/false
    /// —— `-34018`（errSecMissingEntitlement）和「组名写错」是完全不同的两回事。
    private(set) static var lastStatus: OSStatus = 0

    static var lastStatusText: String {
        switch lastStatus {
        case 0: return "0 成功"
        case -34018: return "-34018 缺权限：entitlements 里没有这个 keychain 组，或描述文件不认它"
        case -25243: return "-25243 无权访问这个组"
        case -25300: return "-25300 条目不存在"
        default:
            let msg = SecCopyErrorMessageString(lastStatus, nil) as String? ?? "未知"
            return "\(lastStatus) \(msg)"
        }
    }

    /// 读出摘要。小组件侧调；app 侧自检也用它
    static func load() -> ExpenseSummary? {
        guard let group = accessGroup else { return nil }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: group,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return try? JSONDecoder().decode(ExpenseSummary.self, from: data)
    }

    /// 诊断用：把当前状态讲成人话。真机上排查「小组件为什么空着」时看这个
    static func diagnostics() -> String {
        guard let group = accessGroup else {
            return "拿不到团队前缀 —— 钥匙串探针那一步就失败了，共享组算不出来"
        }
        guard let s = load() else {
            return "共享组 \(group) 里还没有摘要。最后一次写入返回：\(lastStatusText)"
        }
        return "共享组 \(group) · \(s.monthLabel) ¥\(s.total) 共 \(s.count) 笔 · 更新于 \(s.updatedAt)"
    }
}
