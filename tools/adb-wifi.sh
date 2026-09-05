#!/usr/bin/env bash
# 无线连上安卓手机（vivo），不用插线。
#
# 用法：./tools/adb-wifi.sh
#
# 背景：安卓上有**两套**无线 adb，别搞混了 ——
#
#   ① `adb tcpip 5555`（老办法）
#      端口固定 5555，但**手机一重启就失效**，而且要重新插一次线才能再开。
#
#   ② 「开发者选项 → 无线调试」（Android 11+，现在用的是这套）
#      配对一次之后**重启也还在**，不用再插线。
#      ⚠️ 但**端口是随机的、每次都变**（41773 这种），所以不能写死，
#      只能每次靠 mDNS 现查 —— 这个脚本干的就是这件事。
#
# ⚠️ 前提：手机和这台 Mac 要在**同一个 Wi-Fi** 里。
#    踩过一次：手机同时开着 5G 和 Wi-Fi，`ip route` 里 src 抓到的是**移动数据**那个地址
#    （10.x.x.x），拿它去连必然超时。要看的是 `wlan0` 那个（192.168.x.x）。

set -euo pipefail

ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export PATH="$ANDROID_HOME/platform-tools:$PATH"

echo "▸ 找手机广播出来的无线调试端口……"

# `_adb-tls-connect._tcp` = 已经配过对、可以直接连
# `_adb-tls-pairing._tcp` = 还没配对，得先在手机上开「使用配对码配对设备」
#
# ⚠️ **必须重试，查一次就放弃是错的。** adb 的 mDNS 发现有冷启动延迟：
# 刚 disconnect 完、或者 adb server 刚起来的时候，第一次查**稳定返回空**，
# 隔几秒再查就有了（实测第 1 次 0 条、第 2 次就 2 条都在）。
# 查一次就报「没发现」会把「还没发现」误判成「设备不在」—— 这两个长得一模一样。
TARGET=""
for _ in $(seq 1 8); do
    TARGET=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ {print $3; exit}')
    [ -n "$TARGET" ] && break
    sleep 4
done

if [ -z "$TARGET" ]; then
    echo "  没发现 _adb-tls-connect 服务。挨个查一下："
    echo "   · 手机「开发者选项 → 无线调试」开着吗？"
    echo "     （⚠️ 这个 ROM 上点整行就是切开关，很容易手滑关掉）"
    echo "   · 手机和 Mac 在同一个 Wi-Fi 吗？"
    echo "   · 只看到 _adb-tls-pairing 的话，是**还没配对** ——"
    echo "     在手机上点「使用配对码配对设备」，然后跑："
    echo "       adb pair <手机上显示的 IP:端口> <六位配对码>"
    echo
    echo "  当前 mDNS 能看到的（空的话就是一个都没发现）："
    adb mdns services 2>/dev/null | sed 's/^/    /'
    exit 1
fi

echo "  找到：$TARGET"
adb connect "$TARGET"

# ⚠️ 判据是「能不能真的干活」，不是 connect 打印了什么 —— connect 说成功、
# 实际用不了的情况是有的（比如授权掉了）
sleep 1
MODEL=$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
if [ -n "$MODEL" ]; then
    echo "✓ 连上了：$MODEL  ($TARGET)"
    echo "  之后的命令带上 -s ${TARGET}，或者先把别的入口断掉：adb disconnect <另一个>"
else
    echo "✗ connect 说成功，但实际跑不了命令 —— 多半是授权掉了。"
    echo "  插一次线，在手机上重新确认「允许 USB 调试」，再跑一遍这个脚本。"
    exit 1
fi
