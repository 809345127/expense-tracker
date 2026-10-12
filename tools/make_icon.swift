// 生成 1024x1024 的 App 图标：深海军蓝底 + 三条渐变横条（像一列账目）+ 一个金色圆点
//
// ⚠️ 这里有个坑，改动前务必看一眼（2026-08-17 踩过）：
// 旧版用 NSBitmapImageRep(samplesPerPixel: 3, hasAlpha: false) 建画布，再往上画。
// 这种「24 位、不带透明通道」的像素格式 CoreGraphics 不支持，画布能建出来、
// NSGraphicsContext 也能拿到，但所有绘制指令会被静默丢弃——不报错、不警告，
// 导出的是一张全 0 的纯黑图，装到手机上桌面图标就是个黑方块。
//
// 正确做法：用 CGContext + noneSkipLast（32 位、alpha 位留空不参与合成）。
// 这个格式是 CoreGraphics 支持的，且导出的 PNG 不带透明通道
// —— iOS 的 App 图标不允许带透明通道，所以这一点是必须的。
//
// ⚠️ 第二个坑：drawLinearGradient 默认只画起点到终点之间那一段，两头以外留黑。
// 斜着拉渐变时角落会露出黑三角，所以 options 一定要带 drawsBefore / drawsAfter。
//
// 安卓那边的同款图标是手写的矢量图（res/drawable/ic_launcher_foreground.xml），
// 改这里的颜色或比例时记得两边一起改。
//
// 用法：swift tools/make_icon.swift <输出路径>

import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let px = 1024
let size = CGFloat(px)
let cs = CGColorSpaceCreateDeviceRGB()

func rgb(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xff) / 255,
            green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255, alpha: 1)
}
func gradient(_ a: UInt32, _ b: UInt32) -> CGGradient {
    CGGradient(colorsSpace: cs, colors: [rgb(a), rgb(b)] as CFArray, locations: nil)!
}
let extend: CGGradientDrawingOptions = [.drawsBeforeStartLocation, .drawsAfterEndLocation]

guard let ctx = CGContext(
    data: nil, width: px, height: px,
    bitsPerComponent: 8, bytesPerRow: 0, space: cs,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else {
    fatalError("建画布失败：像素格式不被 CoreGraphics 支持")
}

// 1. 底色：深海军蓝，上浅下深一点点，避免一整块死黑
ctx.drawLinearGradient(gradient(0x16263D, 0x0A1220),
                       start: CGPoint(x: 0, y: size), end: CGPoint(x: 0, y: 0), options: extend)

// 2. 三条横条，从上到下变短，颜色从薄荷青渐到紫（CoreGraphics 原点在左下，所以 y 越大越靠上）
//    整组宽 524、高 480（三条 112 + 两段间距 72），正好落在画布正中
let left: CGFloat = 250, barH: CGFloat = 112, fullW: CGFloat = 524
let bars: [(y: CGFloat, width: CGFloat, from: UInt32, to: UInt32)] = [
    (640, 524, 0x5EE7C3, 0x2BC4F0),
    (456, 380, 0x2BC4F0, 0x4C7DFF),
    (272, 250, 0x4C7DFF, 0x8B5CFF),
]
for bar in bars {
    let rect = CGRect(x: left, y: bar.y, width: bar.width, height: barH)
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: barH / 2, cornerHeight: barH / 2, transform: nil))
    ctx.clip()
    // 渐变统一按整组宽度拉，短条只露出前一段，三条的颜色变化节奏一致
    ctx.drawLinearGradient(gradient(bar.from, bar.to),
                           start: CGPoint(x: left, y: 0), end: CGPoint(x: left + fullW, y: 0), options: extend)
    ctx.restoreGState()
}

// 3. 最短那条右边的金色圆点
ctx.setFillColor(rgb(0xFFC94D))
ctx.fillEllipse(in: CGRect(x: 662, y: 272, width: barH, height: barH))

guard let cgImage = ctx.makeImage() else { fatalError("导出位图失败") }

let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.png"
let outURL = URL(fileURLWithPath: outPath)
guard let dest = CGImageDestinationCreateWithURL(outURL as CFURL, UTType.png.identifier as CFString, 1, nil) else {
    fatalError("创建 PNG 输出失败：\(outPath)")
}
CGImageDestinationAddImage(dest, cgImage, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("写 PNG 失败：\(outPath)") }

print("written: \(outPath)  (\(px)x\(px), 无透明通道)")
