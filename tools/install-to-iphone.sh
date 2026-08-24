#!/bin/bash
# 把 app 编译、签名、装到连着的 iPhone 上。
# 前提：iPhone 已连上（线或同一 WiFi）、已在手机上点过「信任此电脑」、开发者模式已打开。
# 用法：./tools/install-to-iphone.sh
set -uo pipefail

XCODE=/Applications/Xcode-27.0.0-Beta.5.app
export DEVELOPER_DIR="$XCODE/Contents/Developer"
PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$PROJ_DIR/build-device"

echo "▸ 1/6 找设备"
DEVICES=$(xcrun devicectl list devices 2>/dev/null | grep -v simulated | grep -iE "iphone|ipad")
if [ -z "$DEVICES" ]; then
    echo "  ✗ 没找到 iPhone。检查：数据线插好了吗？手机上点过「信任此电脑」吗？手机解锁了吗？"
    exit 1
fi
echo "$DEVICES" | sed 's/^/  /'
UDID=$(echo "$DEVICES" | head -1 | grep -oE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}')
echo "  用这台：$UDID"

echo "▸ 2/6 先把手机上的账目备份到电脑（重装理论上不清数据，但不值得赌）"
BACKUP_DIR="$PROJ_DIR/backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
if xcrun devicectl device copy from --device "$UDID" \
        --domain-type appDataContainer \
        --domain-identifier com.shize.ExpenseTracker \
        --source "Library/Application Support" \
        --destination "$BACKUP_DIR" 2>&1 | tail -5; then
    if [ -n "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]; then
        echo "  ✓ 已备份到 $BACKUP_DIR"
        find "$BACKUP_DIR" -name 'default.store' -exec echo "    数据库：{}" \;
    else
        echo "  ⚠️  备份命令没报错，但目录是空的——手机上可能还没有数据库（一笔都没记过）"
        rmdir "$BACKUP_DIR" 2>/dev/null
    fi
else
    echo "  ⚠️  备份失败（上面有报错）。装新版本本身不会清数据，但这次没有备份兜底。"
    echo "     要停下来的话现在按 Ctrl-C；10 秒后继续。"
    sleep 10
fi

echo "▸ 3/6 检查签名要不要换新的"
# ⚠️ 这一步 2026-08-17 才补上，别删。当天实测：签名还剩不到 3 天时跑这个脚本，
# 苹果看本地那份描述文件「还没过期」就直接复用，到期时间一动不动 —— 装完了照样两天后打不开。
# 破法：把本地那份删掉，逼 xcodebuild 重新申请。删掉之后拿到的是崭新的 7 天（实测有效）。
#
# 判断标准用「剩余不足 5 天就换」：免费账号一份签名只有 7 天，续期又必须插线，
# 留 2 天余量才不至于「刚跑完脚本、隔天出门就打不开」。
PROFILE_DIR="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
BUNDLE_ID=com.shize.ExpenseTracker

# 挑出「这个 app 及其扩展」的所有描述文件。
# ⚠️ 不能用 `ls -t | head -1`（取最新那份）：本机只要因为别的原因多出一份描述文件
# （2026-08-20 就因为拿另一个 bundle id 做实验多出来一份），"最新"就会指错，
# 于是脚本读的是别人的到期时间 —— 判成「还够用、不用换」，真正的签名到期那天照样打不开。
# 这正是坑 C1 那个失败模式，只是触发路径换了个。
# ⚠️ 装了桌面小组件之后，这个 app 有**两份**描述文件：
#   com.shize.ExpenseTracker        （主 app）
#   com.shize.ExpenseTracker.Widget （小组件扩展）
# 两份到期时间不一样（申请时间差了几小时）。判「要不要续」必须看**最早到期的那一份**，
# 续的时候也要**两份一起删**，否则会出现「app 续到 8/31、小组件 8/27 就过期」——
# 而扩展的描述文件一过期，整个 app 都可能装不上或打不开。
#
# 匹配规则：bundle id 等于主 id，或者以「主 id.」开头（扩展都是这个形状）。
list_profiles () {
    local want=$1
    for f in "$PROFILE_DIR"/*.mobileprovision; do
        [ -e "$f" ] || continue
        local info
        info=$(security cms -D -i "$f" 2>/dev/null | python3 -c "
import sys, plistlib
try:
    d = plistlib.loads(sys.stdin.buffer.read())
except Exception:
    raise SystemExit
app = d['Entitlements'].get('application-identifier', '')
print(app.split('.', 1)[-1], int(d['ExpirationDate'].timestamp()))
" 2>/dev/null)
        [ -z "$info" ] && continue
        local bid exp
        bid=$(echo "$info" | awk '{print $1}')
        exp=$(echo "$info" | awk '{print $2}')
        case "$bid" in
            "$want"|"$want".*) echo "$exp|$f" ;;
        esac
    done
}

# 最早到期的那一份（判「还剩几天」用它，宁可早续也不能晚）
find_profile () {
    list_profiles "$1" | sort -t'|' -k1,1n | head -1 | cut -d'|' -f2-
}

# 可以用环境变量顶掉，比如想「明明还剩 5 天但趁手机在手边就把签名续掉」：
#   RENEW_THRESHOLD_DAYS=99 ./tools/install-to-iphone.sh
# 走这条路的好处是复用下面那套「先备份旧描述文件、申请失败就还原」的逻辑，
# 而不是自己手动删文件——手动删一旦申请失败就没有兜底了。
RENEW_THRESHOLD_DAYS=${RENEW_THRESHOLD_DAYS:-5}
NEED_RENEW=no
CURRENT_PROFILE=$(find_profile "$BUNDLE_ID")

if [ -z "$CURRENT_PROFILE" ]; then
    echo "  本地还没有描述文件，这次会新申请一份"
else
    DAYS_LEFT=$(security cms -D -i "$CURRENT_PROFILE" 2>/dev/null | python3 -c "
import sys, plistlib, datetime
d = plistlib.loads(sys.stdin.buffer.read())
left = d['ExpirationDate'] - datetime.datetime.utcnow()
# 向下取整到天；已过期就是负数
print(left.days)
" 2>/dev/null || echo "unknown")

    if [ "$DAYS_LEFT" = "unknown" ]; then
        echo "  ⚠️  读不出现有描述文件的到期时间，保险起见换一份新的"
        NEED_RENEW=yes
    elif [ "$DAYS_LEFT" -lt "$RENEW_THRESHOLD_DAYS" ]; then
        echo "  现有签名只剩 $DAYS_LEFT 天（不足 $RENEW_THRESHOLD_DAYS 天）→ 换新的"
        NEED_RENEW=yes
    else
        echo "  现有签名还剩 $DAYS_LEFT 天，够用，不动它"
    fi
fi

if [ "$NEED_RENEW" = yes ]; then
    # 先备份再删：万一苹果那边申请失败（没网、要重新登录），还能还原回去
    PROFILE_BACKUP="$PROJ_DIR/backups/profiles-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$PROFILE_BACKUP"
    # 主 app 和小组件的都要备份 + 删掉，两份一起重新申请
    list_profiles "$BUNDLE_ID" | cut -d'|' -f2- | while read -r p; do
        cp "$p" "$PROFILE_BACKUP/" 2>/dev/null && echo "  已备份 $(basename "$p")"
    done
    # ⚠️ 只删这个 app 及其扩展的，别把目录里别人的描述文件一起清掉
    list_profiles "$BUNDLE_ID" | cut -d'|' -f2- | while read -r p; do rm -f "$p"; done
    echo "  已删掉本地旧描述文件（含小组件那份），下一步会重新申请"
fi

echo "▸ 4/6 编译并签名（要向苹果申请描述文件，可能要几十秒）"
# 团队 ID 不写进工程文件（那样会把个人 Apple 开发者账号的标识提交进 git），
# 改成编译时从本机钥匙串里的「Apple Development」证书里读出来 —— 谁在自己机器上跑就用谁的。
TEAM_ID=${DEVELOPMENT_TEAM:-$(security find-certificate -a -c "Apple Development" -p 2>/dev/null \
    | openssl x509 -noout -subject 2>/dev/null \
    | sed -n 's/.*OU=\([A-Z0-9]*\).*/\1/p' | head -1)}
if [ -z "$TEAM_ID" ]; then
    echo "  ✗ 读不出团队 ID：本机钥匙串里没有「Apple Development」证书。"
    echo "    先在 Xcode 里登录 Apple ID（Settings → Accounts），登录后苹果会自动下发这张证书。"
    echo "    也可以自己指定：DEVELOPMENT_TEAM=XXXXXXXXXX ./tools/install-to-iphone.sh"
    exit 1
fi
echo "  用团队 ID：$TEAM_ID"
if ! xcodebuild -project "$PROJ_DIR/ExpenseTracker.xcodeproj" \
        -scheme ExpenseTracker -configuration Debug \
        -destination "id=$UDID" -derivedDataPath "$BUILD_DIR" \
        DEVELOPMENT_TEAM="$TEAM_ID" \
        -allowProvisioningUpdates build 2>&1 | tail -40; then
    echo "  ✗ 编译或签名失败，看上面的报错"
    if [ "$NEED_RENEW" = yes ] && [ -d "${PROFILE_BACKUP:-}" ]; then
        cp "$PROFILE_BACKUP"/*.mobileprovision "$PROFILE_DIR/" 2>/dev/null \
            && echo "  已把旧描述文件还原回去（至少 app 还能开到原来的到期时间）"
    fi
    exit 1
fi

APP="$BUILD_DIR/Build/Products/Debug-iphoneos/ExpenseTracker.app"
[ -d "$APP" ] || { echo "  ✗ 没生成 app：$APP"; exit 1; }

echo "▸ 5/6 装到手机"
xcrun devicectl device install app --device "$UDID" "$APP" || {
    echo "  ✗ 安装失败。如果提示信任问题，去手机：设置 → 通用 → VPN与设备管理 → 信任你的开发者证书"
    exit 1
}

echo "▸ 6/6 启动"
# 手机锁屏时启动会被系统拒（报 Locked），等一会儿重试几次；启动本身不影响装机结果，
# 但只有真跑起来 SwiftData 才会做数据库升级，所以值得等
LAUNCHED=no
for _ in 1 2 3 4 5 6; do
    if xcrun devicectl device process launch --device "$UDID" com.shize.ExpenseTracker >/dev/null 2>&1; then
        LAUNCHED=yes
        echo "  ✓ 已在手机上启动"
        break
    fi
    echo "  手机像是锁着（启动被拒），解锁一下…… 5 秒后重试"
    sleep 5
done
[ "$LAUNCHED" = yes ] || echo "  （没能自动启动，手机桌面上点「记账本」图标即可，效果一样）"

echo ""
echo "✓ 装好了。手机桌面上找「记账本」。"

# 把这次签名的实际到期时间打出来，「到底续上了没有」不靠猜
# 收尾同样看最早到期的那一份
FINAL_PROFILE=$(find_profile "$BUNDLE_ID")
if [ -n "$FINAL_PROFILE" ]; then
    security cms -D -i "$FINAL_PROFILE" 2>/dev/null | python3 -c "
import sys, plistlib, datetime
d = plistlib.loads(sys.stdin.buffer.read())
exp = d['ExpirationDate']
left = exp - datetime.datetime.utcnow()
bj = exp + datetime.timedelta(hours=8)
print(f\"  签名有效到：{bj:%Y-%m-%d %H:%M} （北京时间）— 还剩 {left.days} 天 {left.seconds // 3600} 小时\")
print(f\"  下次最晚在 {bj:%m月%d日} 之前插线重跑一次这个脚本。\")
if left.days < 5:
    print('  ⚠️  居然不足 5 天：说明这次没换到新描述文件。看上面第 3 步是不是判成了「不用换」，')
    print('     或者苹果那边申请失败了（要重新登 Apple ID 之类）。')
" 2>/dev/null || true
fi
