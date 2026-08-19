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
    var limit: Int = 2
    var compact: Bool = true

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(tags.prefix(limit))) { tag in
                TagChip(tag: tag, compact: compact)
            }
            if tags.count > limit {
                Text("+\(tags.count - limit)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }
}

/// 分类图标：淡色底的圆角小方块 + 分类色图标
struct CategoryIcon: View {
    let category: ExpenseCategory
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: category.icon)
            .font(.system(size: size * 0.42, weight: .medium))
            .foregroundStyle(category.color)
            .frame(width: size, height: size)
            .background(
                category.color.opacity(0.15),
                in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
            )
    }
}
