// 生成 1024x1024 的 App 图标：蓝色渐变底 + 白色 ¥
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
// 用法：swift tools/make_icon.swift <输出路径>
// 想换配色改下面 topColor / bottomColor 两个值即可。

import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let px = 1024
let size = CGFloat(px)

// 底色渐变，与 app 内顶部卡片同源（#2E8BFF -> #084A94）
let topColor = NSColor(srgbRed: 0.18, green: 0.545, blue: 1.0, alpha: 1)
let bottomColor = NSColor(srgbRed: 0.031, green: 0.29, blue: 0.58, alpha: 1)

guard let ctx = CGContext(
    data: nil, width: px, height: px,
    bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else {
    fatalError("建画布失败：像素格式不被 CoreGraphics 支持")
}

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)

let full = NSRect(x: 0, y: 0, width: size, height: size)

// 1. 渐变底
NSGradient(starting: topColor, ending: bottomColor)!.draw(in: full, angle: -70)

// 2. 左上角一点柔光，让底色不至于太平（矩形铺满整张，避免出现拼接硬边）
NSGradient(starting: NSColor(white: 1, alpha: 0.18), ending: NSColor(white: 1, alpha: 0))!
    .draw(in: full, relativeCenterPosition: NSPoint(x: -0.5, y: 0.5))

// 3. 白色 ¥（系统圆体、加粗），精确居中
//
// ⚠️ 别用 NSAttributedString.draw(at:) + boundingRect 来居中：那个矩形带着字体的
// 上下留白（ascender / descender），¥ 这种没有下伸部分的字形会被顶到偏上的位置，
// 图标下方空一大块。要拿「字形真实墨迹范围」，只有 CoreText 的
// CTFontGetBoundingRectsForGlyphs 给得准。
let fontSize: CGFloat = 700
let desc = NSFont.systemFont(ofSize: fontSize, weight: .bold).fontDescriptor.withDesign(.rounded)!
let font = NSFont(descriptor: desc, size: fontSize)!
let ctFont = font as CTFont

var ch: UniChar = Array("¥".utf16)[0]
var glyph = CGGlyph(0)
guard CTFontGetGlyphsForCharacters(ctFont, &ch, &glyph, 1) else {
    fatalError("这个字体里没有 ¥ 字形")
}

// 墨迹范围是相对于「基线原点」的，所以把基线原点挪到 画布中心 - 墨迹中心 即可
let ink = CTFontGetBoundingRectsForGlyphs(ctFont, .horizontal, &glyph, nil, 1)
var baselineOrigin = CGPoint(x: size / 2 - ink.midX, y: size / 2 - ink.midY)

ctx.setFillColor(NSColor.white.cgColor)
CTFontDrawGlyphs(ctFont, &glyph, &baselineOrigin, 1, ctx)

NSGraphicsContext.restoreGraphicsState()

guard let cgImage = ctx.makeImage() else { fatalError("导出位图失败") }

let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.png"
let outURL = URL(fileURLWithPath: outPath)
guard let dest = CGImageDestinationCreateWithURL(outURL as CFURL, UTType.png.identifier as CFString, 1, nil) else {
    fatalError("创建 PNG 输出失败：\(outPath)")
}
CGImageDestinationAddImage(dest, cgImage, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("写 PNG 失败：\(outPath)") }

print("written: \(outPath)  (\(px)x\(px), 无透明通道)")
