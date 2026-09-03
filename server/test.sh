#!/bin/bash
# 同步服务的端到端测试。真起服务、真发 HTTP、真读回来比对。
#
# 跑法：cd server && ./test.sh
#
# ⚠️ 里面有几条是**控制组**（故意应该失败的用例）。没有它们的话，
# 「全部通过」可能只是因为断言压根没在断言——比如认证那条：
# 不先证明「错 token 会被拦」，后面所有 200 都不能说明认证生效了。
set -u

PORT=18787
DB=$(mktemp -d)/test.db
TOKEN=test-token-0123456789abcdef
BASE="http://127.0.0.1:$PORT/v1"
PASS=0; FAIL=0

go build -o /tmp/expense-sync-test . || exit 1
TOKEN=$TOKEN DB="$DB" /tmp/expense-sync-test -addr "127.0.0.1:$PORT" >/tmp/sync-test.log 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
for _ in $(seq 30); do
  curl -sf "$BASE/health" >/dev/null 2>&1 && break
  sleep 0.2
done

ok()   { PASS=$((PASS+1)); printf "  ✓ %s\n" "$1"; }
bad()  { FAIL=$((FAIL+1)); printf "  ✗ %s\n     期望 %s，实际 %s\n" "$1" "$2" "$3"; }
is()   { [ "$2" = "$3" ] && ok "$1" || bad "$1" "$3" "$2"; }

# 从 JSON 里取一个字段（这台机器不一定有 jq，用 python3）
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null || echo "ERR"; }

pull() { curl -s -H "Authorization: Bearer $TOKEN" "$BASE/changes?since=$1${2:+&limit=$2}"; }
pushj(){ curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" "$BASE/changes"; }

echo "== 1. 活着 + 认证（含控制组）=="
is "health 返回 ok" "$(curl -s "$BASE/health")" "ok"
is "控制组：错 token 必须被拦成 401" \
   "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong-token-xxxxxxxx" "$BASE/changes?since=0")" "401"
is "控制组：不带 token 也要 401" \
   "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/changes?since=0")" "401"
is "对 token 才放行" \
   "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE/changes?since=0")" "200"

echo "== 2. 空推送 =="
is "空推送不报错、写入 0 条" "$(pushj '{}' | jget "['applied']")" "0"

echo "== 3. 推一批，再原样拉回来 =="
BATCH='{
 "expenses":[
  {"id":"e1","updated_at":1000,"deleted":false,"amount":"24.50","category_key":"餐饮","note":"午饭","date":900,"created_at":900,"is_private":false},
  {"id":"e2","updated_at":1000,"deleted":false,"amount":"0.10","category_key":"交通","note":"","date":901,"created_at":901,"is_private":true}],
 "tags":[{"id":"t1","updated_at":1000,"deleted":false,"name":"出差","color_index":2,"sort_order":0,"is_archived":false,"created_at":900}],
 "categories":[{"id":"餐饮","updated_at":1000,"deleted":false,"name":"餐饮","icon_name":"fork.knife","color_index":1,"sort_order":0,"is_fallback":false,"created_at":900}],
 "links":[{"id":"e1:t1","updated_at":1000,"deleted":false,"expense_id":"e1","tag_id":"t1"}]
}'
R=$(pushj "$BATCH")
is "写入 5 条" "$(echo "$R" | jget "['applied']")" "5"
is "rev 涨到 1" "$(echo "$R" | jget "['rev']")" "1"
P=$(pull 0)
is "拉回 2 笔账" "$(echo "$P" | jget "[\"expenses\"].__len__()")" "2"
is "拉回 1 个标签" "$(echo "$P" | jget "[\"tags\"].__len__()")" "1"
is "拉回 1 个分类" "$(echo "$P" | jget "[\"categories\"].__len__()")" "1"
is "拉回 1 条标签关联" "$(echo "$P" | jget "[\"links\"].__len__()")" "1"
is "私密标记原样传回来" "$(echo "$P" | jget "[\"expenses\"][1][\"is_private\"]")" "True"
is "中文分类代号没被搞坏" "$(echo "$P" | jget "[\"categories\"][0][\"id\"]")" "餐饮"

echo "== 4. 金额绝不能经过浮点数 =="
is "0.10 逐字符原样" "$(echo "$P" | jget "[\"expenses\"][1][\"amount\"]")" "0.10"
pushj '{"expenses":[{"id":"e3","updated_at":1000,"deleted":false,"amount":"12345678.99","category_key":"其他","note":"","date":1,"created_at":1,"is_private":false}]}' >/dev/null
is "八位数带两位小数原样" "$(pull 0 | jget "[e['id'] for e in d['expenses']].index('e3') if True else 0" >/dev/null; pull 0 | python3 -c "
import sys,json; d=json.load(sys.stdin)
print(next(e['amount'] for e in d['expenses'] if e['id']=='e3'))")" "12345678.99"

echo "== 5. 重推同一批必须是空操作（严格大于）=="
REV_BEFORE=$(pull 0 | jget "['rev']")
R=$(pushj "$BATCH")
is "重推写入 0 条" "$(echo "$R" | jget "['applied']")" "0"
is "重推之后 rev 没涨" "$(pull 0 | jget "['rev']")" "$REV_BEFORE"

echo "== 6. 后写赢 / 旧数据推不动（这两条互为控制组）=="
pushj '{"expenses":[{"id":"e1","updated_at":2000,"deleted":false,"amount":"99.99","category_key":"餐饮","note":"改过了","date":900,"created_at":900,"is_private":false}]}' >/dev/null
is "新的（updated_at 更大）覆盖成功" \
   "$(pull 0 | python3 -c "import sys,json;d=json.load(sys.stdin);print(next(e['amount'] for e in d['expenses'] if e['id']=='e1'))")" "99.99"
R=$(pushj '{"expenses":[{"id":"e1","updated_at":1500,"deleted":false,"amount":"66.66","category_key":"餐饮","note":"旧的","date":900,"created_at":900,"is_private":false}]}')
is "旧的（updated_at 更小）被丢弃" "$(echo "$R" | jget "['applied']")" "0"
is "丢弃之后内容还是新的那份" \
   "$(pull 0 | python3 -c "import sys,json;d=json.load(sys.stdin);print(next(e['amount'] for e in d['expenses'] if e['id']=='e1'))")" "99.99"

echo "== 7. 删除靠墓碑传播（不是把行删掉）=="
pushj '{"expenses":[{"id":"e2","updated_at":3000,"deleted":true,"amount":"0.10","category_key":"交通","note":"","date":901,"created_at":901,"is_private":true}]}' >/dev/null
is "删掉的那条仍然拉得到（带 deleted=true）" \
   "$(pull 0 | python3 -c "import sys,json;d=json.load(sys.stdin);print(next(e['deleted'] for e in d['expenses'] if e['id']=='e2'))")" "True"

echo "== 8. 增量：只拉比 since 大的 =="
REV=$(pull 0 | jget "['rev']")
is "since=当前 rev 时一条都不返回" "$(pull "$REV" | jget "['expenses'].__len__() if 'expenses' in d else 0")" "0"

echo "== 9. 分页游标不许丢数据（最容易写错的一条）=="
# 造一个「一张表被截断、另一张表 rev 更大」的局面：先塞 5 笔账（各自一个 rev），再塞一个标签（rev 最大）
for i in 1 2 3 4 5; do
  pushj "{\"expenses\":[{\"id\":\"p$i\",\"updated_at\":$((5000+i)),\"deleted\":false,\"amount\":\"$i.00\",\"category_key\":\"其他\",\"note\":\"\",\"date\":1,\"created_at\":1,\"is_private\":false}]}" >/dev/null
done
pushj '{"tags":[{"id":"tz","updated_at":9999,"deleted":false,"name":"最后","color_index":0,"sort_order":0,"is_archived":false,"created_at":1}]}' >/dev/null
# 用 limit=2 一页一页拉完，统计一共见到多少条不同的账目
SEEN=$(python3 - "$BASE" "$TOKEN" <<'PY'
import json,sys,urllib.request
base,token=sys.argv[1],sys.argv[2]
seen=set(); since=0; rounds=0
while True:
    req=urllib.request.Request(f"{base}/changes?since={since}&limit=2",
                               headers={"Authorization":f"Bearer {token}"})
    d=json.load(urllib.request.urlopen(req))
    for e in d.get("expenses",[]): seen.add(e["id"])
    rounds+=1
    if not d.get("has_more"): break
    if d["rev"]<=since or rounds>50:   # 防死循环：游标必须前进
        print("STUCK"); sys.exit()
    since=d["rev"]
print(len(seen))
PY
)
# 库里一共 8 笔账：e1 e2 e3 + p1..p5
is "limit=2 分页拉完，8 笔账一条不少" "$SEEN" "8"

echo
echo "===== $PASS 通过 / $FAIL 失败 ====="
[ "$FAIL" -eq 0 ]
