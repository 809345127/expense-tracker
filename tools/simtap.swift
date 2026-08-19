// 驱动 iOS 模拟器的鼠标事件小工具（开发期自测用）
// 用法：
//   swift simtap.swift tap <ptX> <ptY>
//   swift simtap.swift swipe <ptX1> <ptY1> <ptX2> <ptY2>
// 坐标是 iOS 设备的逻辑点（pt），本工具自动换算成 Mac 屏幕坐标。
import CoreGraphics
import Foundation
import AppKit

// 从 Simulator 窗口的 accessibility 信息拿设备屏幕矩形
func deviceScreenRect() -> CGRect {
    let script = """
    tell application "System Events" to tell process "Simulator" to tell window 1 to get {position, size} of group 1
    """
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    p.arguments = ["-e", script]
    let pipe = Pipe()
    p.standardOutput = pipe
    try! p.run()
    p.waitUntilExit()
    let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
    let nums = out.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
    guard nums.count == 4 else { fatalError("拿不到模拟器窗口位置：\(out)") }
    return CGRect(x: nums[0], y: nums[1], width: nums[2], height: nums[3])
}

// iPhone 17 逻辑分辨率
let deviceW = 402.0, deviceH = 874.0
let rect = deviceScreenRect()

func screenPoint(_ x: Double, _ y: Double) -> CGPoint {
    CGPoint(x: rect.minX + x * rect.width / deviceW,
            y: rect.minY + y * rect.height / deviceH)
}

func post(_ type: CGEventType, _ pt: CGPoint) {
    CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: pt, mouseButton: .left)?
        .post(tap: .cghidEventTap)
}

func tap(_ x: Double, _ y: Double) {
    let pt = screenPoint(x, y)
    post(.mouseMoved, pt)
    usleep(150_000)
    post(.leftMouseDown, pt)
    usleep(150_000)   // 按压时间太短时模拟器偶尔不响应
    post(.leftMouseUp, pt)
}

func swipe(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) {
    let a = screenPoint(x1, y1), b = screenPoint(x2, y2)
    post(.mouseMoved, a)
    usleep(50_000)
    post(.leftMouseDown, a)
    let steps = 24
    for i in 1...steps {
        let t = Double(i) / Double(steps)
        let p = CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
        post(.leftMouseDragged, p)
        usleep(14_000)
    }
    usleep(120_000)
    post(.leftMouseUp, b)
}

// 先把模拟器窗口拉到前台，否则点击只会被用来激活窗口、不传给 app。
// 必须确认真的到了前台再发事件：NSWorkspace.activate() 是异步的，等不到就会点空。
func activateSimulator() {
    for attempt in 1...12 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        p.arguments = ["-e", "tell application \"Simulator\" to activate"]
        try? p.run()
        p.waitUntilExit()
        usleep(250_000)

        let check = Process()
        check.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        check.arguments = ["-e", "tell application \"System Events\" to get name of first application process whose frontmost is true"]
        let pipe = Pipe()
        check.standardOutput = pipe
        try? check.run()
        check.waitUntilExit()
        let front = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if front == "Simulator" { return }
        if attempt == 12 { FileHandle.standardError.write("模拟器没能到前台，当前前台是 \(front)\n".data(using: .utf8)!) }
    }
}
activateSimulator()

let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "" {
case "tap" where args.count >= 4:
    tap(Double(args[2])!, Double(args[3])!)
    print("tapped \(args[2]),\(args[3])")
case "swipe" where args.count >= 6:
    swipe(Double(args[2])!, Double(args[3])!, Double(args[4])!, Double(args[5])!)
    print("swiped")
default:
    print("用法: tap <x> <y> | swipe <x1> <y1> <x2> <y2>")
    exit(1)
}
usleep(200_000)
