import Foundation
import Security
import SwiftData
import UIKit
import Vision

// MARK: - 智能记账：把一段文字 / 几张图片识别成几笔待核对的账（2026-10-09 加）
//
// 用户原话：「加入输入文字或者图片，识别要记什么账，可以批量记账」
// 「可以使用我的 DeepSeek api」「如果 api 不能用，是否有降级的方法？」「key 可以搞成输入的吗」
//
// ## 四级降级（前一级不行就自动退到下一级，界面上会写这次用的是哪一级）
//
// | 级 | 做法 | 什么时候退到这一级 |
// |---|---|---|
// | 1 | 图片 + 本地读出来的字 + 你的文字，一起发给 DeepSeek 看图模型 | 有图、填了 key（正常情况）|
// | 2 | 只发文字（你的文字 + 本地从图里读出来的字）给 DeepSeek 文字模型 | 只有文字；或者第 1 级报错 |
// | 3 | 手机本地按规则拆：数字当金额、按关键词猜分类 | 没填 key；或者 DeepSeek 整个不通 |
// | 4 | 什么都没认出来：保留输入，提示稍后重试 | 第 3 级也拆不出任何金额 |
//
// 第 1 级也把本地读出来的字一起发过去，是因为 DeepSeek 看图时会把图缩得很小
// （第三方资料说一张图最多按 384 个 token 算），长截图上的小字容易看错；
// 读字在手机本地做、免费，有了它模型就不用靠看小字去认金额。
//
// ## 安全（用户问过「安全问题如何解决」）
//
// · key 只存在这台手机的钥匙串里，设成「只在本机、解锁后可读」，不跟 iCloud / 备份走
// · 不写日志、不进导出、不同步到服务器、不打进安装包
// · 请求只发往 `api.deepseek.com`（DEBUG 构建可以指向本机假服务器做测试，正式构建写死）
// · 识别出来的东西只是草稿，用户点「保存」之前一个字都不进数据库、不会同步
//
// ⚠️ 安卓以后照这份抄（用户说「iOS 打磨完成我确认后，再 copy 到安卓」），
//    规则拆分的关键词表、提示词、降级顺序两边要一字不差。

// MARK: - 草稿

/// 识别出来的一笔，用户核对、改完之后才真正存成 `Expense`。
struct ParsedDraft: Identifiable, Equatable {
    let id = UUID()
    /// 金额，**恒为正数**；收支看 isIncome（跟记账表单同一个口径）
    var amount: Decimal
    var isIncome: Bool
    var categoryKey: String
    var date: Date
    var note: String
    /// 没把握：分类是猜的 / 日期没认出来 / 第 3 级本地规则拆的。界面上标黄
    var uncertain: Bool = false
    /// 同一天已经有一笔同样金额、同样收支的账。界面上标「可能已记过」，默认不勾
    var duplicate: Bool = false
    /// 勾上了才会保存
    var selected: Bool = true
    /// 标签只存 ID，不存托管对象（理由同 ExpenseFilter）
    var tagIDs: Set<PersistentIdentifier> = []
    var isPrivate: Bool = false
}

/// 识别时要用到的一个分类。从 `CategoryDef` 抄出来的纯值，逻辑层不碰托管对象，好测
struct SmartCategory: Equatable {
    let key: String
    let name: String
    var isIncome: Bool { CategoryKind.isIncome(key: key) }
}

/// 这次识别用的是哪一级
enum SmartLevel: Int, Equatable {
    case aiVision = 1, aiText = 2, rules = 3, nothing = 4

    var title: String {
        switch self {
        case .aiVision: "AI 看图识别"
        case .aiText:   "AI 文字识别"
        case .rules:    "本地规则拆分"
        case .nothing:  "没认出来"
        }
    }
}

struct SmartResult {
    var level: SmartLevel
    var drafts: [ParsedDraft]
    /// 为什么没用上更高的那一级（「DeepSeek 余额不足」「没填 key」这类），给界面显示用。
    /// 第 1 / 2 级成功时为空
    var downgradeReason: String = ""
}

// MARK: - 第 3 级：本地规则拆分（纯逻辑，有测试）

enum RuleParser {
    /// 关键词 → 预设分类代号。只在库里真有这个代号时才用（用户可能删了某个预设）。
    /// 先比用户自己的分类名，比不上才查这张表。
    /// ⚠️ 安卓照抄时这张表要一字不差
    static let keywords: [(key: String, words: [String])] = [
        ("餐饮", ["早饭", "早餐", "午饭", "午餐", "晚饭", "晚餐", "夜宵", "外卖", "饭", "餐", "奶茶", "咖啡",
                 "饮料", "水果", "零食", "火锅", "面", "吃", "喝", "美团", "饿了么", "星巴克", "瑞幸", "麦当劳", "肯德基"]),
        ("交通", ["打车", "滴滴", "出租", "地铁", "公交", "高铁", "火车", "机票", "航班", "油费", "加油", "停车",
                 "过路费", "单车", "车费", "12306"]),
        ("购物", ["超市", "淘宝", "京东", "拼多多", "天猫", "衣服", "鞋", "日用", "便利店", "买"]),
        ("居住", ["房租", "水费", "电费", "燃气", "物业", "宽带", "话费", "租金"]),
        ("娱乐", ["电影", "游戏", "KTV", "门票", "旅游", "演出", "酒吧"]),
        ("医疗", ["药", "医院", "挂号", "体检", "看病", "牙"]),
        ("学习", ["书", "课", "培训", "学费", "考试"]),
        ("人情", ["礼物", "份子", "请客", "随礼", "送礼"]),
        ("订阅", ["会员", "订阅", "月费", "年费", "iCloud", "爱奇艺", "腾讯视频", "网易云", "Netflix"]),
        ("收入:工资", ["工资", "薪水", "薪资"]),
        ("收入:奖金", ["奖金", "年终奖", "绩效"]),
        ("收入:理财", ["理财", "利息", "分红", "基金", "收益"]),
        ("收入:红包", ["收红包", "收到红包"]),
        ("收入:退款", ["退款", "退货", "报销"]),
        ("收入:兼职", ["兼职", "稿费", "外快"]),
    ]

    /// 出现这些词就当收入（除非是「发红包」这种明确的支出）
    static let incomeHints = ["工资", "薪水", "奖金", "年终奖", "收到", "收红包", "退款", "退货", "报销",
                              "利息", "分红", "收益", "兼职", "稿费", "收入", "进账", "入账"]

    /// 日期词 → 往前推几天
    static let dayWords: [(word: String, offset: Int)] = [("大前天", -3), ("前天", -2), ("昨天", -1), ("今天", 0)]

    /// 把一段文字拆成几笔。
    ///
    /// 规则：
    ///   · 全角数字、全角标点先转半角（中文输入法打出来的 `３２` `。`）
    ///   · 每个数字是一笔，它**前面**那截文字是这笔的说明；前面没字就取后面那截（「32 午饭」）
    ///   · 「昨天 / 前天」往后一直生效，直到出现下一个日期词（「昨天 午饭 32 打车 18」两笔都算昨天）
    ///   · 数字前面紧挨着 `+` 当收入、`-` 当支出（支付宝 / 微信账单的写法）
    ///   · 每笔都标「没把握」：规则拆的结果一律要人看一眼
    static func parse(_ raw: String, categories: [SmartCategory], now: Date = .now,
                      calendar: Calendar = .current) -> [ParsedDraft] {
        let text = normalize(raw)
        guard let regex = try? NSRegularExpression(pattern: #"([+\-]?)(?:[¥$]\s*)?(\d+(?:\.\d{1,2})?)"#) else { return [] }
        let ns = text as NSString

        // 第一遍：挑出真正是金额的数字。年份、日期、时间（「2026」「10月9日」「12:30」「2026-10-09」）跳过
        struct Hit { let range: NSRange; let sign: String; let amount: Decimal }
        var hits: [Hit] = []
        for m in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let end = m.range.location + m.range.length
            let tail = end < ns.length ? ns.substring(with: NSRange(location: end, length: 1)) : ""
            if ["月", "日", "号", "点", ":", "年", "/", "-"].contains(tail) { continue }
            let head = m.range.location > 0 ? ns.substring(with: NSRange(location: m.range.location - 1, length: 1)) : ""
            if head == ":" || head == "/" { continue }
            // 「2026-10-09」里的「-09」：符号前面紧挨着数字，是日期的分隔符，不是负号
            if m.range(at: 1).length > 0, head.first?.isNumber == true { continue }
            guard let amount = Decimal(string: ns.substring(with: m.range(at: 2)), locale: Locale(identifier: "en_US_POSIX")),
                  amount > 0, amount < 1_000_000_000 else { continue }
            hits.append(Hit(range: m.range, sign: ns.substring(with: m.range(at: 1)), amount: amount))
        }
        guard !hits.isEmpty else { return [] }

        // 说明文字写在金额前面（「午饭 32 打车 18」）还是后面（「32 午饭 18 打车」）：
        // 看第一笔金额前面有没有字，**整段统一**按一种来。逐笔各自判断的话，
        // 第二笔「18」前面恰好是「午饭」，就会被说成午饭
        let labelAfter = cleanLabel(ns.substring(with: NSRange(location: 0, length: hits[0].range.location))).isEmpty

        var drafts: [ParsedDraft] = []
        var dayOffset = 0
        var prevEnd = 0
        for (i, h) in hits.enumerated() {
            let end = h.range.location + h.range.length
            let before = ns.substring(with: NSRange(location: prevEnd, length: h.range.location - prevEnd))
            let nextStart = i + 1 < hits.count ? hits[i + 1].range.location : ns.length
            let after = ns.substring(with: NSRange(location: end, length: nextStart - end))
            prevEnd = end

            // 日期词：往后一直生效，直到出现下一个
            let labelText: String
            if labelAfter {
                if let off = lastDayOffset(in: before) { dayOffset = off }
                // 说明在后面时只取到下一个分隔符为止
                labelText = String(after.split(whereSeparator: { "\n,;，；、".contains($0) }).first ?? "")
                if let off = lastDayOffset(in: labelText) { dayOffset = off }
            } else {
                if let off = lastDayOffset(in: before) { dayOffset = off }
                // 只取紧挨着金额的那一行：前面隔着好几行的字（店名、日期）不算这笔的说明
                labelText = before.components(separatedBy: "\n").map(cleanLabel).last { !$0.isEmpty } ?? ""
            }
            let label = cleanLabel(labelText)

            let isIncome: Bool
            if h.sign == "+" { isIncome = true }
            else if h.sign == "-" { isIncome = false }
            else { isIncome = incomeHints.contains { label.contains($0) } && !label.contains("发红包") }

            let key = guessCategory(label: label, isIncome: isIncome, categories: categories)
            let day = calendar.date(byAdding: .day, value: dayOffset, to: now) ?? now
            let catName = categories.first { $0.key == key }?.name ?? ""
            drafts.append(ParsedDraft(
                amount: h.amount, isIncome: isIncome, categoryKey: key, date: day,
                note: label == catName ? "" : label, uncertain: true))
        }
        return drafts
    }

    /// 小票上表示「总共付了多少」的词，前面的优先
    static let totalWords = ["实付", "实收", "应付", "合计", "总计"]

    /// 一张小票（从图里读出来的字）→ **一笔**：取「实付 / 合计」那一行的金额，店名取第一行不带数字的字。
    /// 不是小票（找不到这几个词）返回 nil，调用方改用 `parse` 逐笔拆。
    ///
    /// 跟发给 AI 的规则同一个口径：一张小票只算一笔，不把每个商品、小计、优惠拆开
    /// （安卓模拟器上实测：不加这条，一张便利店小票被拆成 6 笔）。⚠️ 安卓 `parseReceipt` 同一套
    static func parseReceipt(_ ocr: String, categories: [SmartCategory], now: Date = .now,
                             calendar: Calendar = .current) -> [ParsedDraft]? {
        let lines = normalize(ocr).components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        for w in totalWords {
            guard let line = lines.last(where: { $0.contains(w) }),
                  var hit = parse(line, categories: categories, now: now, calendar: calendar).first else { continue }
            let shop = lines.first { !$0.contains(where: \.isNumber) }.map { String($0.prefix(20)) } ?? ""
            hit.isIncome = false
            hit.categoryKey = guessCategory(label: lines.joined(separator: " "), isIncome: false, categories: categories)
            hit.note = shop
            // 小票上印的时间：认得出、又不在未来，就用它
            hit.date = now
            let all = lines.joined(separator: "\n")
            if let re = try? NSRegularExpression(pattern: #"(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:\s+(\d{1,2}):(\d{2}))?"#),
               let m = re.firstMatch(in: all, range: NSRange(all.startIndex..., in: all)) {
                func g(_ i: Int) -> Int? { Range(m.range(at: i), in: all).flatMap { Int(all[$0]) } }
                var dc = DateComponents(year: g(1), month: g(2), day: g(3), hour: g(4) ?? 12, minute: g(5) ?? 0)
                dc.timeZone = calendar.timeZone
                if let d = calendar.date(from: dc), d <= now { hit.date = d }
            }
            return [hit]
        }
        return nil
    }

    /// 全角转半角，再把「元 / 块 / ￥」这类单位去掉
    static func normalize(_ s: String) -> String {
        // ⚠️ 句号要在转半角**之前**换：全角转半角会把「。」变成半角的「｡」（U+FF61），不是「.」
        let dotted = s.replacingOccurrences(of: "。", with: ".").replacingOccurrences(of: "．", with: ".")
        let half = dotted.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? dotted
        return half.replacingOccurrences(of: "｡", with: ".")
            .replacingOccurrences(of: "￥", with: "¥")
    }

    private static func lastDayOffset(in s: String) -> Int? {
        var best: (pos: String.Index, off: Int)?
        for (w, off) in dayWords {
            if let r = s.range(of: w, options: .backwards) {
                // 比的是词的**结尾**位置：「大前天」和它里面的「前天」结尾相同，
                // 表里长的排在前面、相同时不替换，所以取到的是「大前天」
                if best == nil || r.upperBound > best!.pos { best = (r.upperBound, off) }
            }
        }
        return best?.off
    }

    /// 说明文字：去掉日期词、货币单位、两头的标点空白
    private static func cleanLabel(_ s: String) -> String {
        // 先去掉里面夹着的日期、时间（「12:30 咖啡」「10月9日 午饭」）
        var t = s.replacingOccurrences(of: #"\d[\d:/\-.年月日号点]*"#, with: " ", options: .regularExpression)
        for (w, _) in dayWords { t = t.replacingOccurrences(of: w, with: "") }
        for w in ["元", "块钱", "块", "¥", "RMB", "rmb"] { t = t.replacingOccurrences(of: w, with: "") }
        let junk = CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters).union(.symbols)
        return t.trimmingCharacters(in: junk)
    }

    static func guessCategory(label: String, isIncome: Bool, categories: [SmartCategory]) -> String {
        let pool = categories.filter { $0.isIncome == isIncome }
        // ① 用户自己的分类名，长的先比（「其他收入」别被「收入」截胡）
        for c in pool.sorted(by: { $0.name.count > $1.name.count }) where !c.name.isEmpty && label.contains(c.name) {
            return c.key
        }
        // ② 关键词表
        let keys = Set(pool.map(\.key))
        for (key, words) in keywords where keys.contains(key) {
            if words.contains(where: { label.localizedCaseInsensitiveContains($0) }) { return key }
        }
        // ③ 兜底
        let fallback = isIncome ? CategorySeed.incomeFallbackKey : CategorySeed.fallbackKey
        return keys.contains(fallback) ? fallback : (pool.first?.key ?? fallback)
    }
}

// MARK: - 第 1 / 2 级：DeepSeek

enum DeepSeekConfig {
    private static let d = UserDefaults.standard

    /// 文字模型。`deepseek-chat` 是 DeepSeek 一直保留的老名字
    static var textModel: String {
        get { d.string(forKey: "ds.textModel") ?? "deepseek-chat" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "ds.textModel") }
    }

    /// 看图模型。⚠️ 这个名字是从第三方资料看来的（2026-09 起叫 `deepseek-flash`），
    /// 没经过官方文档核实 —— 所以做成可改的，设置页的「测试」会列出你的 key 实际能用哪些模型
    static var visionModel: String {
        get { d.string(forKey: "ds.visionModel") ?? "deepseek-flash" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "ds.visionModel") }
    }

    /// 接口地址。正式构建写死 DeepSeek 官方；DEBUG 构建可以用 `-deepseekBase` 指向本机假服务器
    static var baseURL: URL {
        #if DEBUG
        if let s = DevFlags.value("-deepseekBase"), let u = URL(string: s) { return u }
        #endif
        return URL(string: "https://api.deepseek.com")!
    }
}

/// DeepSeek 的 key 存取。**只进钥匙串**，不进 UserDefaults、不进日志。
enum DeepSeekKeyStore {
    private static let base: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.shize.ExpenseTracker.deepseek",
        kSecAttrAccount as String: "api-key",
    ]

    static func load() -> String? {
        var q = base
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data, let s = String(data: data, encoding: .utf8), !s.isEmpty
        else { return nil }
        return s
    }

    @discardableResult
    static func save(_ key: String) -> Bool {
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(key.utf8)
        // ⚠️ ThisDeviceOnly：不跟 iCloud 钥匙串同步、不进加密备份恢复到别的机器
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func delete() { SecItemDelete(base as CFDictionary) }

    /// 设置页显示用：`sk-••••a3f9`。完整的 key 存进去之后界面上再也看不到
    static func masked(_ key: String) -> String {
        let head = key.hasPrefix("sk-") ? "sk-" : ""
        return head + "••••" + String(key.suffix(4))
    }
}

/// 调 DeepSeek 失败的原因，翻成人话给界面显示
struct DeepSeekError: Error {
    let message: String
}

enum DeepSeekClient {
    /// 发一次识别请求，返回模型吐出来的 JSON 原文
    static func complete(key: String, model: String, system: String, userText: String,
                         images: [Data], timeout: TimeInterval) async throws -> String {
        var content: [[String: Any]] = [["type": "text", "text": userText]]
        for img in images {
            content.append(["type": "image_url",
                            "image_url": ["url": "data:image/jpeg;base64," + img.base64EncodedString()]])
        }
        let body: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "system", "content": system],
                // 纯文字时 content 直接给字符串：老的文字模型不一定认数组形式
                ["role": "user", "content": images.isEmpty ? userText as Any : content as Any],
            ],
            "response_format": ["type": "json_object"],
            "temperature": 0,
            "stream": false,
        ]
        var req = URLRequest(url: DeepSeekConfig.baseURL.appendingPathComponent("chat/completions"))
        req.httpMethod = "POST"
        req.timeoutInterval = timeout
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, resp) = try await send(req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw DeepSeekError(message: describe(code: code, body: data)) }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = obj["choices"] as? [[String: Any]],
              let msg = choices.first?["message"] as? [String: Any],
              let text = msg["content"] as? String
        else { throw DeepSeekError(message: "DeepSeek 返回的格式看不懂") }
        return text
    }

    /// 设置页「测试」用：列出这个 key 能用的模型
    static func listModels(key: String) async throws -> [String] {
        var req = URLRequest(url: DeepSeekConfig.baseURL.appendingPathComponent("models"))
        req.timeoutInterval = 20
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await send(req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw DeepSeekError(message: describe(code: code, body: data)) }
        let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        return ((obj?["data"] as? [[String: Any]]) ?? []).compactMap { $0["id"] as? String }.sorted()
    }

    private static func send(_ req: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await URLSession.shared.data(for: req)
        } catch let e as URLError where e.code == .timedOut {
            throw DeepSeekError(message: "DeepSeek 超时没回")
        } catch let e as URLError where e.code == .notConnectedToInternet {
            throw DeepSeekError(message: "手机没联网")
        } catch {
            throw DeepSeekError(message: "连不上 DeepSeek（\(error.localizedDescription)）")
        }
    }

    /// 状态码翻人话。码的含义按 DeepSeek 文档的错误码表
    static func describe(code: Int, body: Data) -> String {
        // 把服务端的原始报错带上一截，方便判断是不是模型名写错了这类问题
        let raw = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])
            .flatMap { ($0["error"] as? [String: Any])?["message"] as? String } ?? ""
        let detail = raw.isEmpty ? "" : "：\(raw.prefix(80))"
        switch code {
        case 401: return "key 不对或已作废（401）"
        case 402: return "DeepSeek 余额不足（402）"
        case 429: return "请求太频繁，等一下再试（429）"
        case 400, 422: return "请求被拒（\(code)）\(detail)"
        case 500...599: return "DeepSeek 服务繁忙（\(code)）"
        default: return "DeepSeek 返回 \(code)\(detail)"
        }
    }
}

// MARK: - 提示词 + 解析模型的回答（纯逻辑，有测试）

enum AIPrompt {
    /// 系统提示词。⚠️ 安卓照抄时一字不差
    static func system(categories: [SmartCategory], now: Date, timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.timeZone = timeZone
        f.dateFormat = "yyyy-MM-dd EEEE HH:mm"
        let expense = categories.filter { !$0.isIncome }.map(\.key).joined(separator: "、")
        let income = categories.filter(\.isIncome).map(\.key).joined(separator: "、")
        return """
        你是记账助手。用户会给你一段文字、或者小票 / 订单 / 支付账单的截图（截图里的文字也会以「图片里读出的文字」附上，可能有错字），\
        把里面**每一笔实际发生的收入或支出**提取出来。

        现在是 \(f.string(from: now))（时区 \(timeZone.identifier)）。

        支出分类只能从这里选：\(expense)
        收入分类只能从这里选：\(income)

        只输出一个 JSON 对象，格式：
        {"items":[{"amount":32.5,"type":"expense","category":"餐饮","date":"2026-10-09","time":"12:30","note":"午饭","confident":true}]}

        规则：
        - amount 是正数，最多两位小数；type 只能是 expense 或 income
        - category 必须是上面列出的原样字符串之一，拿不准就用「其他」或「收入:其他」，并把 confident 设为 false
        - date 用 yyyy-MM-dd；「昨天」「上周五」这类按现在的日期换算；没提日期就用今天
        - time 用 HH:mm，图片或文字里没有具体时间就填 null
        - note 写一个简短的说明（商家名或买了什么），不超过 20 个字，不要重复分类名
        - 一张小票只算**一笔**（用实付金额，不要把每个商品拆开，不要把小计、优惠、找零、余额当成一笔）
        - 账单列表截图里有几行交易就是几笔；转账给自己、信用卡还款这类不算收支的跳过
        - 金额不清楚、或者看不出是不是一笔交易时，confident 设为 false
        - 一笔都没有就输出 {"items":[]}
        """
    }

    /// 用户消息：用户自己写的字 + 从图片里读出来的字
    static func user(text: String, ocrTexts: [String]) -> String {
        var parts: [String] = []
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { parts.append("用户写的：\n\(t)") }
        for (i, o) in ocrTexts.enumerated() where !o.isEmpty {
            parts.append("图片\(i + 1)里读出的文字：\n\(o)")
        }
        return parts.isEmpty ? "（请看图片）" : parts.joined(separator: "\n\n")
    }

    /// 把模型的回答翻成草稿。分类不在列表里 → 兜底分类 + 标没把握；金额不合法的那条直接丢掉
    static func decode(_ json: String, categories: [SmartCategory], now: Date,
                       calendar: Calendar = .current) throws -> [ParsedDraft] {
        // 个别模型会在 JSON 外面包一层 ```json … ```，剥掉
        var s = json.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("```") {
            s = s.components(separatedBy: "\n").dropFirst().joined(separator: "\n")
            if let r = s.range(of: "```", options: .backwards) { s = String(s[..<r.lowerBound]) }
        }
        guard let data = s.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = obj["items"] as? [[String: Any]]
        else { throw DeepSeekError(message: "AI 回的不是约定的格式") }

        let keys = Set(categories.map(\.key))
        let dayF = DateFormatter()
        dayF.locale = Locale(identifier: "en_US_POSIX")
        dayF.calendar = calendar
        dayF.timeZone = calendar.timeZone
        dayF.dateFormat = "yyyy-MM-dd"

        return items.compactMap { item in
            // 金额：数字或字符串都认；统一走字符串转 Decimal，不经过 Double 的精度损失
            let rawAmount: String
            if let n = item["amount"] as? NSNumber { rawAmount = n.stringValue }
            else if let t = item["amount"] as? String { rawAmount = RuleParser.normalize(t) }
            else { return nil }
            guard var amount = Decimal(string: rawAmount, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
            if amount < 0 { amount = -amount }
            var rounded = Decimal()
            NSDecimalRound(&rounded, &amount, 2, .plain)
            guard rounded > 0, rounded < 1_000_000_000 else { return nil }

            let isIncome = (item["type"] as? String) == "income"
            var uncertain = (item["confident"] as? Bool) == false

            var key = item["category"] as? String ?? ""
            if !keys.contains(key) || CategoryKind.isIncome(key: key) != isIncome {
                key = RuleParser.guessCategory(label: (item["note"] as? String) ?? "", isIncome: isIncome,
                                               categories: categories)
                uncertain = true
            }

            var date = now
            if let ds = item["date"] as? String {
                if let day = dayF.date(from: ds) {
                    if let ts = item["time"] as? String, let (h, m) = parseTime(ts) {
                        date = calendar.date(bySettingHour: h, minute: m, second: 0, of: day) ?? day
                    } else if calendar.isDate(day, inSameDayAs: now) {
                        date = now   // 今天、没写几点 → 就用现在，跟手动记一笔一致
                    } else {
                        date = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: day) ?? day
                    }
                    // 认出来的日期在未来：多半是年份猜错了，标没把握让人看一眼
                    if date > now.addingTimeInterval(60) { uncertain = true }
                } else {
                    uncertain = true
                }
            }

            var note = ((item["note"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if note.count > 30 { note = String(note.prefix(30)) }
            let catName = categories.first { $0.key == key }?.name
            if note == catName { note = "" }

            return ParsedDraft(amount: rounded, isIncome: isIncome, categoryKey: key, date: date,
                               note: note, uncertain: uncertain)
        }
    }

    private static func parseTime(_ s: String) -> (Int, Int)? {
        let p = s.split(separator: ":").compactMap { Int($0) }
        guard p.count >= 2, (0..<24).contains(p[0]), (0..<60).contains(p[1]) else { return nil }
        return (p[0], p[1])
    }
}

// MARK: - 本地读字（iOS 自带的离线文字识别，免费、不联网）

enum LocalOCR {
    static func text(in image: UIImage) async -> String {
        guard let cg = image.cgImage else { return "" }
        let orientation = image.cgOrientation
        // 不用带回调的写法：perform 抛错时回调也可能被调一次，continuation 会被 resume 两次直接崩
        return await Task.detached(priority: .userInitiated) {
            let req = VNRecognizeTextRequest()
            req.recognitionLevel = .accurate
            req.recognitionLanguages = ["zh-Hans", "en-US"]
            req.usesLanguageCorrection = true
            do { try VNImageRequestHandler(cgImage: cg, orientation: orientation).perform([req]) }
            catch { return "" }
            // Vision 的坐标原点在左下角、y 往上增大，翻成「y 往下增大」再交给 joinRows
            let boxes = (req.results ?? []).compactMap { o -> OCRBox? in
                guard let t = o.topCandidates(1).first?.string else { return nil }
                let b = o.boundingBox
                return OCRBox(text: t, left: b.minX, top: 1 - b.maxY, bottom: 1 - b.minY)
            }
            return joinRows(boxes)
        }.value
    }

    /// 读出来的一小段字和它在图上的位置（y 往下增大）
    struct OCRBox { let text: String; let left: CGFloat; let top: CGFloat; let bottom: CGFloat }

    /// 把读出来的一段段字**按图上的行**拼回去：同一行的从左到右用两个空格连起来，行与行换行。
    ///
    /// ⚠️ 为什么要拼行：小票上「饭团 ……… 8.50」左右两截是分开读出来的，金额那截的上下边
    /// 常常跟商品名差一两个像素 —— 逐段排序会排成「8.50 ↵ 饭团」，第 3 级本地规则就会
    /// 把金额配给别的商品（安卓模拟器上实测踩到）。判同一行：中线落在这一行的上下边之间。
    /// ⚠️ 安卓 `joinRows`（SmartAi.kt）是同一个算法
    static func joinRows(_ boxes: [OCRBox]) -> String {
        var rows: [[OCRBox]] = []
        for b in boxes.sorted(by: { ($0.top + $0.bottom) < ($1.top + $1.bottom) }) {
            let mid = (b.top + b.bottom) / 2
            if let first = rows.last?.first, mid > first.top, mid < first.bottom {
                rows[rows.count - 1].append(b)
            } else {
                rows.append([b])
            }
        }
        return rows.map { $0.sorted { $0.left < $1.left }.map(\.text).joined(separator: "  ") }
            .joined(separator: "\n")
    }
}

extension UIImage {
    /// 长边压到 maxSide 以内、转 JPEG。发给 DeepSeek 用：原图动辄几 MB，上传慢还费流量
    func jpegForUpload(maxSide: CGFloat = 1600, quality: CGFloat = 0.7) -> Data? {
        let longest = max(size.width, size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let img = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
        return img.jpegData(compressionQuality: quality)
    }

    fileprivate var cgOrientation: CGImagePropertyOrientation {
        switch imageOrientation {
        case .up: .up
        case .down: .down
        case .left: .left
        case .right: .right
        case .upMirrored: .upMirrored
        case .downMirrored: .downMirrored
        case .leftMirrored: .leftMirrored
        case .rightMirrored: .rightMirrored
        @unknown default: .up
        }
    }
}

// MARK: - 总调度：按四级降级跑一遍

enum SmartRecognizer {
    static func run(text: String, images: [UIImage], categories: [SmartCategory],
                    now: Date = .now) async -> SmartResult {
        // 本地读字先做：第 1、2、3 级都用得上，而且不联网、不花钱
        var ocr: [String] = []
        for img in images { ocr.append(await LocalOCR.text(in: img)) }

        var reason = ""
        if let key = DeepSeekKeyStore.load() {
            let system = AIPrompt.system(categories: categories, now: now)
            let user = AIPrompt.user(text: text, ocrTexts: ocr)

            // 第 1 级：有图才走
            if !images.isEmpty {
                do {
                    let jpegs = images.compactMap { $0.jpegForUpload() }
                    let out = try await DeepSeekClient.complete(
                        key: key, model: DeepSeekConfig.visionModel, system: system,
                        userText: user, images: jpegs, timeout: 90)
                    let drafts = try AIPrompt.decode(out, categories: categories, now: now)
                    return SmartResult(level: .aiVision, drafts: drafts)
                } catch {
                    reason = "看图没成功：" + ((error as? DeepSeekError)?.message ?? error.localizedDescription)
                }
            }
            // 第 2 级：只发文字。有图但图里一个字都没读出来、用户也没写字时，发了也白发
            if !user.isEmpty, user != "（请看图片）" {
                do {
                    let out = try await DeepSeekClient.complete(
                        key: key, model: DeepSeekConfig.textModel, system: system,
                        userText: user, images: [], timeout: 60)
                    let drafts = try AIPrompt.decode(out, categories: categories, now: now)
                    return SmartResult(level: .aiText, drafts: drafts, downgradeReason: reason)
                } catch {
                    let msg = (error as? DeepSeekError)?.message ?? error.localizedDescription
                    // 两级报的是同一个原因（比如 key 失效）就只说一遍
                    reason = reason.hasSuffix(msg) ? reason : (reason.isEmpty ? msg : reason + "；文字也没成功：" + msg)
                }
            }
        } else {
            reason = "还没填 DeepSeek key"
        }

        // 第 3 级：本地规则。用户写的字逐笔拆；每张图单独看 —— 是小票就只算一笔，不是再逐笔拆
        let drafts = RuleParser.parse(text, categories: categories, now: now) + ocr.flatMap {
            RuleParser.parseReceipt($0, categories: categories, now: now)
                ?? RuleParser.parse($0, categories: categories, now: now)
        }
        if !drafts.isEmpty { return SmartResult(level: .rules, drafts: drafts, downgradeReason: reason) }
        // 第 4 级
        return SmartResult(level: .nothing, drafts: [], downgradeReason: reason)
    }
}

#if DEBUG
// MARK: - 自测（`-smartSelfTest` 启动参数触发，结果落盘到 Documents/smart-selftest.txt）
//
// 这个工程没有单元测试 target，规则拆分和解析 AI 回答都是纯逻辑，用这种方式跑一遍断言。
// 每条写「PASS / FAIL + 实际值」，最后一行是汇总。
enum SmartSelfTest {
    static func run(categories: [SmartCategory]) {
        var lines: [String] = []
        var fails = 0
        let cal = Calendar.current
        // 固定一个「现在」，日期类断言才稳定
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 9, hour: 20, minute: 15))!
        let yesterday = cal.date(byAdding: .day, value: -1, to: now)!

        func check(_ name: String, _ ok: Bool, _ detail: @autoclosure () -> String) {
            if !ok { fails += 1 }
            lines.append("\(ok ? "PASS" : "FAIL") \(name)" + (ok ? "" : " → \(detail())"))
        }
        func show(_ ds: [ParsedDraft]) -> String {
            ds.map { "\($0.amount)|\($0.isIncome ? "收" : "支")|\($0.categoryKey)|\($0.note)|\(cal.component(.day, from: $0.date))日" }
                .joined(separator: " ; ")
        }
        func rules(_ s: String) -> [ParsedDraft] { RuleParser.parse(s, categories: categories, now: now) }
        func sig(_ ds: [ParsedDraft]) -> [String] {
            ds.map { "\($0.amount)|\($0.categoryKey)|\($0.note)|\(cal.component(.day, from: $0.date))" }
        }

        // 分类表是空的话下面好几条会「空转通过」，先把这个前提钉住（预设 10 个支出 + 7 个收入）
        check("分类表已加载", categories.count >= 17, "只有 \(categories.count) 个")

        // ---- 第 3 级：本地规则 ----
        var r = rules("午饭 32 打车 18.5 昨天超市 126")
        check("说明在前 + 昨天", sig(r) == ["32|餐饮|午饭|9", "18.5|交通|打车|9", "126|购物|超市|8"], show(r))
        r = rules("32 午饭 18 打车")
        check("说明在后", sig(r) == ["32|餐饮|午饭|9", "18|交通|打车|9"], show(r))
        r = rules("昨天 午饭 32 打车 18")
        check("昨天往后延续", sig(r) == ["32|餐饮|午饭|8", "18|交通|打车|8"], show(r))
        r = rules("大前天 电影 60")
        check("大前天不被前天截胡", sig(r) == ["60|娱乐|电影|6"], show(r))
        r = rules("工资 8000")
        check("工资是收入", r.count == 1 && r[0].isIncome && r[0].categoryKey == "收入:工资", show(r))
        r = rules("发红包 200")
        check("发红包是支出", r.count == 1 && !r[0].isIncome, show(r))
        r = rules("2026-10-09 午饭 32")
        check("日期里的数字不当金额", sig(r) == ["32|餐饮|午饭|9"], show(r))
        r = rules("12:30 咖啡 25")
        check("时间里的数字不当金额", sig(r) == ["25|餐饮|咖啡|9"], show(r))
        r = rules("10月9日 晚饭 45元")
        check("月日 + 元", sig(r) == ["45|餐饮|晚饭|9"], show(r))
        r = rules("午饭３２。５")
        check("全角数字和句号", sig(r) == ["32.5|餐饮|午饭|9"], show(r))
        r = rules("¥45 星巴克")
        check("¥ 前缀 + 品牌词", sig(r) == ["45|餐饮|星巴克|9"], show(r))
        r = rules("+100 退款\n-30 滴滴")
        check("账单写法 +/-", r.count == 2 && r[0].isIncome && r[0].categoryKey == "收入:退款"
              && !r[1].isIncome && r[1].categoryKey == "交通", show(r))
        r = rules("随便买了点东西 66")
        check("「买」归购物", sig(r) == ["66|购物|随便买了点东西|9"], show(r))
        r = rules("不知道是啥 12")
        check("猜不出 → 其他", sig(r) == ["12|其他|不知道是啥|9"], show(r))
        check("规则结果一律标黄", rules("午饭 32").allSatisfy(\.uncertain), "")
        check("没有数字 → 0 笔", rules("你好").isEmpty && rules("").isEmpty, show(rules("你好")))

        // ---- 解析 AI 回答 ----
        func decode(_ s: String) -> [ParsedDraft]? { try? AIPrompt.decode(s, categories: categories, now: now) }
        var d = decode(#"{"items":[{"amount":32.5,"type":"expense","category":"餐饮","date":"2026-10-09","time":"12:30","note":"午饭","confident":true},{"amount":"8000","type":"income","category":"收入:工资","date":"2026-10-08","time":null,"note":"十月工资"}]}"#)
        check("AI 正常两笔", d?.count == 2 && d?[0].amount == Decimal(string: "32.5") && d?[0].uncertain == false
              && cal.component(.hour, from: d![0].date) == 12 && d?[1].isIncome == true
              && d?[1].categoryKey == "收入:工资" && cal.isDate(d![1].date, inSameDayAs: yesterday)
              && cal.component(.hour, from: d![1].date) == 12, show(d ?? []))
        d = decode(#"{"items":[{"amount":20,"type":"expense","category":"奶茶","date":"2026-10-09","note":"奶茶"}]}"#)
        check("分类不在列表 → 猜一个 + 标黄", d?.first?.categoryKey == "餐饮" && d?.first?.uncertain == true, show(d ?? []))
        d = decode(#"{"items":[{"amount":20,"type":"expense","category":"收入:工资","date":"2026-10-09","note":"x"}]}"#)
        check("支出配了收入分类 → 改掉 + 标黄", d?.first?.categoryKey == "其他" && d?.first?.uncertain == true, show(d ?? []))
        d = decode(#"{"items":[{"amount":-15,"type":"expense","category":"交通","date":"2026-10-09"},{"amount":0,"type":"expense","category":"交通","date":"2026-10-09"}]}"#)
        check("负数取绝对值、0 丢掉", d?.count == 1 && d?.first?.amount == 15, show(d ?? []))
        d = decode("```json\n{\"items\":[{\"amount\":9.9,\"type\":\"expense\",\"category\":\"订阅\",\"date\":\"2026-10-09\",\"note\":\"订阅\"}]}\n```")
        check("剥掉 ```json 包装、备注同分类名清空", d?.count == 1 && d?.first?.note == "", show(d ?? []))
        d = decode(#"{"items":[{"amount":9,"type":"expense","category":"餐饮","date":"2026-10-09"}]}"#)
        check("今天没写几点 → 用现在", d?.first?.date == now, show(d ?? []))
        d = decode(#"{"items":[{"amount":9,"type":"expense","category":"餐饮","date":"2027-01-01"}]}"#)
        check("未来日期 → 标黄", d?.first?.uncertain == true, show(d ?? []))
        d = decode(#"{"items":[{"amount":9,"type":"expense","category":"餐饮","date":"昨天"}]}"#)
        check("日期格式不对 → 用现在 + 标黄", d?.first?.uncertain == true && d?.first?.date == now, show(d ?? []))
        check("不是约定格式 → 报错", decode("我不知道") == nil && decode(#"{"foo":1}"#) == nil, "")
        check("空 items → 0 笔不报错", decode(#"{"items":[]}"#)?.isEmpty == true, "")

        // ---- 小票 → 一笔 ----
        let receipt = "全家梗利店\n2026-10-09 12:41\n饭团  8.50\n乌龙荼  6.00\n三明治  12.00\n合计  26.50\n优惠  -2.00\n实付  24.50"
        let rc = RuleParser.parseReceipt(receipt, categories: categories, now: now) ?? []
        check("小票只算一笔、取实付", rc.count == 1 && rc.first?.amount == Decimal(string: "24.5")
              && rc.first?.note == "全家梗利店" && rc.first?.categoryKey == "餐饮"
              && cal.component(.hour, from: rc.first!.date) == 12 && cal.component(.minute, from: rc.first!.date) == 41, show(rc))
        check("不是小票 → nil", RuleParser.parseReceipt("午饭 32", categories: categories, now: now) == nil, "")
        r = rules("全家便利店\n2026-10-09\n饭团 8.5")
        check("说明只取紧挨着的那一行", sig(r) == ["8.5|餐饮|饭团|9"], show(r))

        // ---- 读字结果按行拼 ----
        let rows = LocalOCR.joinRows([
            .init(text: "饭团", left: 60, top: 202, bottom: 242), .init(text: "8.50", left: 400, top: 200, bottom: 240),
            .init(text: "全家便利店", left: 60, top: 100, bottom: 140),
            .init(text: "6.00", left: 400, top: 299, bottom: 339), .init(text: "乌龙茶", left: 60, top: 300, bottom: 340),
        ])
        check("同一行左右两截拼成一行", rows == "全家便利店\n饭团  8.50\n乌龙茶  6.00", rows)

        // ---- 提示词里要带上分类和日期 ----
        let sys = AIPrompt.system(categories: categories, now: now)
        check("提示词带今天日期", sys.contains("2026-10-09"), "")
        check("提示词带全部分类", categories.allSatisfy { sys.contains($0.key) }, "")

        lines.append(fails == 0 ? "ALL PASS (\(lines.count))" : "FAILED \(fails) / \(lines.count)")
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("smart-selftest.txt")
        try? lines.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
    }
}
#endif
