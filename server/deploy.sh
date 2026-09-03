#!/bin/bash
# 把同步服务部署到 VPS。在 Mac 上交叉编译成一个 linux 二进制、传上去、重启 systemd。
#
# 跑法：cd server && ./deploy.sh
#
# ⚠️ VPS 上什么都不用装（不用装 Go、不用装 sqlite）——因为用的是纯 Go 的 sqlite 实现、
#    编译时关掉 CGO，产物是一个静态二进制。
#
# ⚠️⚠️ 绝对不碰这台 VPS 上的 nginx 和 443 端口：
#    443 是 xray（翻墙）在用；而 nginx:80 是 xray 的**回落目标**（config 里 dest: 80）。
#    动它们有可能把翻墙那条链路搞挂。本服务自己听一个高端口，跟它们完全隔离。
set -euo pipefail

cd "$(dirname "$0")"

# 🚨 服务器地址**不写进这个仓库**（仓库是公开的，而那台 VPS 上还跑着别的东西，
#    IP 落进公开仓不合适）。放在 server/.env.deploy 里，那个文件已 gitignore。
#
#    第一次用：cp .env.deploy.example .env.deploy 然后把 HOST 填上。
[ -f .env.deploy ] && . ./.env.deploy
HOST=${HOST:-}
PORT=${PORT:-8787}
REMOTE_DIR=${REMOTE_DIR:-/opt/expense-sync}
SVC=expense-sync

if [ -z "$HOST" ]; then
  echo "✗ 不知道要部署到哪台机器。"
  echo "  cp server/.env.deploy.example server/.env.deploy 并填上 HOST=你的服务器地址"
  echo "  （或者临时 HOST=1.2.3.4 ./deploy.sh）"
  exit 1
fi

echo "▸ 1/5 交叉编译 linux/amd64"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /tmp/$SVC .
ls -lh /tmp/$SVC | awk '{print "  产物 "$5}'

echo "▸ 2/5 确认远端目录、并**保住已有的 token 和数据库**"
# ⚠️ token 生成一次就固定下来（客户端里配的是它）；重复部署绝不能把它换掉，
#    换掉的话两台手机全部 401、而且报错长得像"服务挂了"
ssh "root@$HOST" "
  mkdir -p $REMOTE_DIR
  if [ ! -f $REMOTE_DIR/env ]; then
    echo \"TOKEN=\$(openssl rand -hex 32)\" > $REMOTE_DIR/env
    echo 'DB=$REMOTE_DIR/expense-sync.db' >> $REMOTE_DIR/env
    echo 'ADDR=:$PORT' >> $REMOTE_DIR/env
    chmod 600 $REMOTE_DIR/env
    echo '  第一次部署：已生成新 token'
  else
    echo '  已有 env，token 和数据库保持不动'
  fi"

echo "▸ 3/5 传二进制"
scp -q /tmp/$SVC "root@$HOST:$REMOTE_DIR/$SVC.new"

echo "▸ 4/5 装 systemd 服务并重启"
ssh "root@$HOST" "
  cat > /etc/systemd/system/$SVC.service <<'UNIT'
[Unit]
Description=Expense tracker sync service
After=network.target

[Service]
Type=simple
EnvironmentFile=$REMOTE_DIR/env
# 放行本服务的端口。⚠️ 这台机器的 iptables 是「默认 DROP + 只放 22/80/443」，
# 而 iptables 规则**重启就没了**——所以把放行动作绑在服务启动上，重启后跟着服务一起回来。
#
# 为什么不装 iptables-persistent：那东西是把**当前所有规则**快照下来开机重放，
# 而这台机器上 fail2ban 在动态管自己的链，快照会把它那些临时规则一起固化，容易打架。
# 绑在服务上只动自己这一条，谁都不碰。
#
# 先 -C 查一遍再 -I，所以重复启动不会插重复规则。
ExecStartPre=/bin/sh -c 'iptables -C INPUT -p tcp --dport $PORT -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport $PORT -j ACCEPT'
ExecStart=$REMOTE_DIR/$SVC
WorkingDirectory=$REMOTE_DIR
Restart=always
RestartSec=2
# 一点基础加固：只能读写自己那个目录
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=$REMOTE_DIR
# ⚠️ 不能加 NoNewPrivileges=true：上面那条 ExecStartPre 要动 iptables，
#    加了它 iptables 会以 "Permission denied" 失败，而服务照样起得来
#    —— 于是「服务 active、外网连不上」，症状跟防火墙没配一模一样。
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
UNIT
  mv $REMOTE_DIR/$SVC.new $REMOTE_DIR/$SVC && chmod +x $REMOTE_DIR/$SVC
  systemctl daemon-reload
  systemctl enable --now $SVC >/dev/null 2>&1
  systemctl restart $SVC
  sleep 1
  systemctl is-active $SVC | sed 's/^/  服务状态: /'"

echo "▸ 5/5 从外网真的请求一次（判据是响应，不是 systemctl 说它 active）"
# ⚠️ 「服务 active」不等于「外网能连上」——防火墙、监听地址、端口占用都可能让它只在本机通
if curl -sf --max-time 8 "http://$HOST:$PORT/v1/health" | grep -q ok; then
  echo "  ✓ 外网可达：http://$HOST:$PORT"
else
  echo "  ✗ 外网连不上。先在 VPS 上试 curl 127.0.0.1:$PORT/v1/health："
  echo "    通 → 是防火墙/安全组挡了；不通 → 看 journalctl -u $SVC -n 30"
  exit 1
fi

echo
echo "客户端要配的两个东西（token 只存在 VPS 上，这里现读）："
echo "  地址  http://$HOST:$PORT"
printf "  token "; ssh "root@$HOST" "grep '^TOKEN=' $REMOTE_DIR/env | cut -d= -f2"
