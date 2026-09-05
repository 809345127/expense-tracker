#!/usr/bin/env bash
# 无线连上安卓手机（vivo），不用插线。
#
# 用法：./tools/adb-wifi.sh
#
# ⚠️⚠️ 安卓上有**两套**无线 adb，这个 ROM 上它们的差别是决定性的：
#
#   ① `adb tcpip 5555`（老办法）
#      端口固定 5555。**手机重启就失效**，重开要再插一次线。
#      ✅ **但它不受「哪个界面在前台」影响** —— 这是它在这台 vivo 上的杀手锏，见下。
#
#   ② 开发者选项里的「无线调试」（Android 11+）
#      端口随机、每次都变，只能靠 mDNS 现查。
#      🚨🚨 **这台 vivo（OriginOS）上：手机一离开「无线调试」那个设置页，开关就自己关掉。**
#      2026-09-05 实测坐实：`am start` 把记账本拉到前台之后，
#      `settings get global adb_wifi_enabled` 当场从 1 变成 0，那个随机端口立刻废掉。
#      所以**光靠它没法做任何需要操作 app 的事**（装完包想启动来看一眼都不行）——
#      而这正是我们最常要做的事。
#
# 👉 因此这个脚本**优先走 5555**，mDNS 只是兜底。
#    5555 没开的时候（手机重启过），插一次线跑 `adb tcpip 5555` 就能重新打开，
#    之后又可以拔线、长期无线用。
#
# ⚠️ 手机和 Mac 要在同一个 Wi-Fi。踩过一次：手机同时开着 5G 和 Wi-Fi 时，
#    `ip route` 里 src 抓到的是**移动数据**那个地址（10.x.x.x），拿它去连必然超时。

set -uo pipefail

ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export PATH="$ANDROID_HOME/platform-tools:$PATH"
CACHE="$HOME/.cache/expense-tracker"
mkdir -p "$CACHE"
LAST_IP_FILE="$CACHE/vivo-ip"

# 判据永远是「能不能真干活」，不是 connect 打印了什么 ——
# connect 说成功、实际跑不了命令的情况是有的（授权掉了就这样）
works() {
    local t=$1
    local m
    m=$(adb -s "$t" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    [ -n "$m" ] && { echo "$m"; return 0; }
    return 1
}

try() {
    local t=$1 how=$2 m port
    adb connect "$t" >/dev/null 2>&1
    sleep 1
    if m=$(works "$t"); then
        port=${t##*:}
        echo "✓ 连上了：$m  ($t)   [$how]"
        echo "${t%%:*}" > "$LAST_IP_FILE"
        echo
        echo "  之后的命令带上 -s $t"
        # ⚠️ 走的是哪套机制**按端口现判**，不能信调用方传进来那句话 ——
        # mDNS 的输出里也会出现 5555（老机制自己广播的 `_adb._tcp`），
        # 按「从哪一步找到的」贴标签会贴反。提示语误导比没有更糟。
        if [ "$port" = "5555" ]; then
            echo "  ✅ 这是**固定端口 5555**（老机制）：不怕你在手机上切来切去，"
            echo "     设置页不用一直开着。⚠️ 手机重启会失效，那时插线跑一次 adb tcpip 5555。"
        else
            echo "  ⚠️ 这是**「无线调试」的随机端口**：这个 ROM 上手机一离开那个设置页，"
            echo "     开关就自己关掉、这个端口当场作废。要长期用请开 5555："
            echo "       adb -s $t tcpip 5555      # 不用插线，现在这条连接就能开"
        fi
        exit 0
    fi
    adb disconnect "$t" >/dev/null 2>&1
    return 1
}

# ---- 0. 已经连着的就别折腾了 ----
for t in $(adb devices | awk '/:[0-9]+\tdevice$/{print $1}'); do
    if m=$(works "$t"); then
        echo "✓ 本来就连着：$m  ($t)"
        echo "${t%%:*}" > "$LAST_IP_FILE"
        exit 0
    fi
    adb disconnect "$t" >/dev/null 2>&1
done

# ---- 1. 上次那个 IP 的 5555（最省事，而且不挑前台是什么界面）----
if [ -f "$LAST_IP_FILE" ]; then
    IP=$(cat "$LAST_IP_FILE")
    echo "▸ 先试上次那个地址的 5555：$IP:5555"
    try "$IP:5555" "上次那个地址" || echo "  没成（手机可能重启过、或者换了 IP）"
fi

# ---- 2. mDNS 找「无线调试」那个随机端口 ----
# ⚠️ 必须重试：adb 的 mDNS 发现有冷启动延迟，刚 disconnect 完或 adb server 刚起时
# 第一次**稳定返回空**，隔几秒才有（实测第 1 次 0 条、第 2 次就有了）。
# 查一次就报「没发现」会把「还没发现」误判成「设备不在」—— 这两个长得一模一样。
# ⚠️ 用正则抓 `IP:端口`，不要按列取 —— 实测同一条命令的输出列数会变，按列会抓到服务名。
echo "▸ 再试「无线调试」广播出来的随机端口……"
for _ in $(seq 1 5); do
    for T in $(adb mdns services 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+' | sort -u); do
        try "$T" "mDNS 广播" || true
    done
    sleep 3
done

# ---- 3. 全网段扫 5555（换了 IP 时靠这条自愈）----
echo "▸ 都没成，扫一遍本网段的 5555……"
SUBNET=$(ipconfig getifaddr en0 2>/dev/null | sed 's/\.[0-9]*$//')
if [ -n "$SUBNET" ]; then
    : > "$CACHE/scan.txt"
    for i in $(seq 1 254); do
        ( nc -z -G 1 "$SUBNET.$i" 5555 2>/dev/null && echo "$SUBNET.$i" >> "$CACHE/scan.txt" ) &
    done
    wait 2>/dev/null
    while read -r IP; do
        [ -n "$IP" ] && try "$IP:5555" "网段扫描"
    done < "$CACHE/scan.txt"
fi

echo
echo "✗ 三条路都没连上。挨个查："
echo "   · **最常见**：手机重启过 → 5555 失效了。插一次线，跑 \`adb tcpip 5555\`，"
echo "     然后就能拔线长期无线用（不用一直开着设置页）。"
echo "   · 手机和 Mac 在同一个 Wi-Fi 吗？（手机同时开 5G 时也可能走错网卡）"
echo "   · 想用「无线调试」那条路的话，得**把那个设置页一直留在前台**——"
echo "     这个 ROM 上一切走它就自己关了（实测）。"
echo "   · 只看到 _adb-tls-pairing 说明还没配对：手机上点「使用配对码配对设备」，再跑"
echo "       adb pair <手机上显示的 IP:端口> <六位配对码>"
echo
echo "  当前 mDNS 能看到的："
adb mdns services 2>/dev/null | sed 's/^/    /'
exit 1
