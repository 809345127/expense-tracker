import SwiftUI

/// 月份切换器：‹ 2026年8月 ›（明细、统计两页共用）
struct MonthSwitcher: View {
    @Binding var month: Date
    var foreground: Color = .primary
    /// 连点月份标题三下时触发。私密记录的隐蔽入口就挂在这儿——
    /// 选这个位置是因为它平时就是一行普通说明文字、不可点，别人不会想到去连点它，
    /// 而且明细页和统计页都有，两边都能进。不传就是没有这个行为。
    var onSecretTap: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 18) {
            Button {
                month = month.addingMonths(-1)
            } label: {
                Image(systemName: "chevron.left")
                    .font(.subheadline.weight(.semibold))
                    .frame(width: 30, height: 30)
            }
            Text(month.monthTitle)
                .font(.headline)
                .monospacedDigit()
                // 三下才触发：一下两下都可能是误碰。没传 onSecretTap 时整个手势不装
                .onTapGesture(count: 3) { onSecretTap?() }
            Button {
                month = month.addingMonths(1)
            } label: {
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .frame(width: 30, height: 30)
            }
        }
        .foregroundStyle(foreground)
        .buttonStyle(.plain)
    }
}

/// 标签小胶囊
struct TagChip: View {
    let tag: Tag
    var compact: Bool = false

    var body: some View {
        Text(tag.name)
            .font(compact ? .caption2 : .caption)
            .lineLimit(1)
            .padding(.horizontal, compact ? 6 : 8)
            .padding(.vertical, compact ? 2 : 3)
            .foregroundStyle(tag.color)
            .background(tag.color.opacity(0.15), in: Capsule())
    }
}

/// 一行里最多显示 limit 个标签，多出来的收成「+N」
/// —— 不限制的话，几个长标签就能把列表行挤爆
struct TagChipRow: View {
    let tags: [Tag]
    /// 最多显示几个胶囊，多出来的收成「+N」。`nil` = 全部显示。
    /// 表单里用 `nil`：那一行是「我给这笔挂了哪些标签」的答案，
    /// 收成「+N」会被读成"只能挂 N 个"（用户 2026-08-18 就这么问过）。
    /// 列表行仍然限量，那里一行要跟金额、备注抢宽度。
    var limit: Int? = 2
    var compact: Bool = true

    private var shown: [Tag] {
        guard let limit else { return tags }
        return Array(tags.prefix(limit))
    }

    var body: some View {
        HStack(spacing: 4) {
            ForEach(shown) { tag in
                TagChip(tag: tag, compact: compact)
            }
            if tags.count > shown.count {
                Text("+\(tags.count - shown.count)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }
}

/// 分类图标：淡色底的圆角小方块 + 分类色图标。
///
/// 收的是「图标名 + 颜色」这两个值，而不是一个分类对象 —— 这样它同时能给
/// 「已有分类」和「正在新建、还没存进库的分类」用（新建界面要实时预览）。
struct CategoryIcon: View {
    let iconName: String
    let color: Color
    var size: CGFloat = 40

    init(iconName: String, color: Color, size: CGFloat = 40) {
        self.iconName = iconName
        self.color = color
        self.size = size
    }

    /// 从库里的分类定义建。`nil`（分类被删了这种意外情况）时退化成一个灰色问号
    init(_ def: CategoryDef?, size: CGFloat = 40) {
        self.iconName = def?.iconName ?? CategoryIconLibrary.fallback
        self.color = def?.color ?? .gray
        self.size = size
    }

    var body: some View {
        Image(systemName: iconName)
            .font(.system(size: size * 0.42, weight: .medium))
            .foregroundStyle(color)
            .frame(width: size, height: size)
            .background(
                color.opacity(0.15),
                in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
            )
    }
}
