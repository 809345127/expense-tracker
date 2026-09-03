#!/usr/bin/env python3
"""两台设备的同步收敛测试。

这个测试**不测服务端代码**，测的是「协议 + 客户端算法」这套设计本身：
用两个模拟客户端（各自有本地库、dirty 标记、lastRev 游标）照 server/README.md
里写的那套算法跑真实场景，检查两边最后是不是同一份数据。

为什么值得单独写：iOS 和安卓两个客户端要各实现一遍这套算法。
设计上的缺陷（收敛不了、数据丢一条、无限互推）等两个客户端都写完才发现，
代价是两边一起改；在这里发现，改的是一份文档 + 一处服务端逻辑。

跑法：先起本地服务，再 python3 test_convergence.py
    TOKEN=t DB=/tmp/c.db go run . -addr 127.0.0.1:18899 &
    python3 test_convergence.py http://127.0.0.1:18899 t
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18899"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "t"
PASS = FAIL = 0


def call(method, path, body=None):
    req = urllib.request.Request(
        f"{BASE}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def ok(msg):
    global PASS
    PASS += 1
    print(f"  ✓ {msg}")


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print(f"  ✗ {msg}\n     {detail}")


def eq(msg, got, want):
    ok(msg) if got == want else bad(msg, f"期望 {want!r}，实际 {got!r}")


# 两台设备共用的「墙上时钟」。真实手机都对 NTP，彼此偏差是毫秒级，
# 所以这里用一个共享的递增计数器模拟。
# ⚠️ 一开始我给两台设备各自一个独立时钟、还把 B 调快了 4000，
#    结果 A 后面所有改动的 updated_at 都小于服务器上那条、全被判成旧数据丢掉，
#    测试挂了两条 —— 那是**脚手架的问题**，不是协议的问题。
#    但它顺带暴露了一个真实弱点：时钟偏差大的时候慢的那台确实推不上去。
#    那个场景现在单独测（第 8 组），并且服务端会把它报出来而不是咽掉。
_WALL = [1000]


def tick(skew=0):
    _WALL[0] += 10
    return _WALL[0] + skew


class Device:
    """一个模拟客户端。算法照 server/README.md「客户端同步算法」那一节实现。"""

    def __init__(self, name, skew=0):
        self.name = name
        self.rows = {}       # id -> 记录（含本地专用的 dirty）
        self.last_rev = 0
        self.skew = skew     # 这台设备时钟相对真实时间的偏差（第 8 组用）
        self.last_stale = []  # 上次推送里被服务端判为旧数据的那些

    # ---- 本地写入：任何改动都要 updated_at + dirty ----
    def put(self, rec):
        rec = dict(rec)
        rec.update(updated_at=tick(self.skew), deleted=rec.get("deleted", False), dirty=True)
        self.rows[rec["id"]] = rec
        return rec

    def delete(self, rid):
        r = dict(self.rows[rid])
        r.update(deleted=True, updated_at=tick(self.skew), dirty=True)
        self.rows[rid] = r

    def visible(self):
        """界面上看得见的那些（墓碑不算）。"""
        return {k: v for k, v in self.rows.items() if not v["deleted"]}

    # ---- 同步 ----
    def sync(self):
        # 1~3 拉 + 合并
        guard = 0
        while True:
            page = call("GET", f"/v1/changes?since={self.last_rev}&limit=2")
            for r in page.get("expenses", []):
                local = self.rows.get(r["id"])
                # ⚠️ 唯一一条合并规则：本地有未推送的改动、且严格更新 → 留本地；否则服务器赢
                if local and local["dirty"] and local["updated_at"] > r["updated_at"]:
                    continue
                r = dict(r)
                r["dirty"] = False          # ⚠️ 绝不能置 True，否则两台设备无限互推
                self.rows[r["id"]] = r
            if not page.get("has_more"):
                self.last_rev = page["rev"]
                break
            if page["rev"] <= self.last_rev or guard > 50:
                raise AssertionError("游标没有前进，会死循环")
            guard += 1
            self.last_rev = page["rev"]

        # 4~5 推 + 清 dirty
        out = [{k: v for k, v in r.items() if k != "dirty"}
               for r in self.rows.values() if r["dirty"]]
        if out:
            res = call("POST", "/v1/changes", {"expenses": out})
            # ⚠️ 服务端报回来的 stale = 被当成旧数据丢掉的那些。
            # 真客户端拿到非空的 stale 要给用户提示（大概率是设备时钟不对），
            # 咽掉的话那些改动就是「永久丢失且毫无迹象」
            self.last_stale = res.get("stale") or []
            for sent in out:
                cur = self.rows[sent["id"]]
                # ⚠️ 只在 updated_at 没变时才清 dirty：推送在飞的时候本地可能又改了
                if cur["updated_at"] == sent["updated_at"]:
                    cur["dirty"] = False
            self.last_rev = max(self.last_rev, res["rev"])


def expense(rid, amount, note=""):
    return {"id": rid, "amount": amount, "category_key": "其他", "note": note,
            "date": 1, "created_at": 1, "is_private": False}


def same_view(a, b, msg):
    """两台设备看得见的内容逐字段一致（忽略 rev 和本地字段）。"""
    def norm(d):
        return {k: {kk: vv for kk, vv in v.items() if kk not in ("rev", "dirty")}
                for k, v in d.visible().items()}
    if norm(a) == norm(b):
        ok(msg)
    else:
        onlya = set(norm(a)) - set(norm(b))
        onlyb = set(norm(b)) - set(norm(a))
        diff = [k for k in set(norm(a)) & set(norm(b)) if norm(a)[k] != norm(b)[k]]
        bad(msg, f"只在 A: {onlya}  只在 B: {onlyb}  内容不同: {diff}")


print("== 0. 服务活着 ==")
try:
    eq("能拉到东西（空库也算）", "rev" in call("GET", "/v1/changes?since=0"), True)
except urllib.error.URLError as e:
    print(f"  ✗ 连不上 {BASE}：{e}")
    sys.exit(1)

A, B = Device("A"), Device("B")

print("== 1. A 记一笔，B 同步之后应该看得到 ==")
A.put(expense("x1", "24.50", "午饭"))
A.sync()
B.sync()
eq("B 拿到了那笔", B.visible().get("x1", {}).get("amount"), "24.50")
same_view(A, B, "两边视图一致")

print("== 2. 两台设备**各改同一条**，必须收敛到同一个值（不能各留自己那份）==")
A.put({**A.rows["x1"], "amount": "30.00"})   # A 先改
B.put({**B.rows["x1"], "amount": "99.00"})   # B 后改（共享时钟，所以 B 的 updated_at 更大）
A.sync(); B.sync(); A.sync()                  # 各同步几轮
eq("A 上是后写的那个值", A.visible()["x1"]["amount"], "99.00")
eq("B 上是后写的那个值", B.visible()["x1"]["amount"], "99.00")
same_view(A, B, "冲突之后两边一致")

print("== 3. 离线各记一笔，联网后两边都该有两笔 ==")
A.put(expense("x2", "5.00", "A 离线记的"))
B.put(expense("x3", "6.00", "B 离线记的"))
A.sync(); B.sync(); A.sync()
eq("A 有 3 笔", len(A.visible()), 3)
eq("B 有 3 笔", len(B.visible()), 3)
same_view(A, B, "离线各记之后两边一致")

print("== 4. A 删一笔，B 那边必须**消失**（这条最容易做漏）==")
A.delete("x2")
A.sync(); B.sync()
eq("A 上看不见了", "x2" in A.visible(), False)
eq("B 上也看不见了", "x2" in B.visible(), False)
eq("但 B 本地还留着墓碑（不是把行删了）", B.rows["x2"]["deleted"], True)
same_view(A, B, "删除之后两边一致")

print("== 5. 反复同步不该产生任何变化（防无限互推）==")
before_rev = call("GET", "/v1/changes?since=0")["rev"]
for _ in range(3):
    A.sync(); B.sync()
after_rev = call("GET", "/v1/changes?since=0")["rev"]
eq("空跑 3 轮之后服务端 rev 一动不动", after_rev, before_rev)
eq("A 没有残留的待推记录", any(r["dirty"] for r in A.rows.values()), False)
eq("B 没有残留的待推记录", any(r["dirty"] for r in B.rows.values()), False)

print("== 6. 边推边改：推送在飞的时候又改了同一条，那次改动不能丢 ==")
# 模拟：把 dirty 记录取出来（相当于已经发出请求），发出后本地又改了一次，
# 然后才收到响应去清 dirty
A.put({**A.rows["x1"], "amount": "111.00"})
out = [{k: v for k, v in r.items() if k != "dirty"} for r in A.rows.values() if r["dirty"]]
res = call("POST", "/v1/changes", {"expenses": out})
A.put({**A.rows["x1"], "amount": "222.00"})           # ← 请求在飞的时候又改了
for sent in out:                                       # 收到响应，按「updated_at 没变才清」清 dirty
    cur = A.rows[sent["id"]]
    if cur["updated_at"] == sent["updated_at"]:
        cur["dirty"] = False
eq("那次改动的 dirty 还在（没被误清）", A.rows["x1"]["dirty"], True)
A.sync(); B.sync()
eq("所以它推上去了，B 看到的是 222.00", B.visible()["x1"]["amount"], "222.00")

print("== 7. 控制组：如果客户端把「清 dirty」写成只按 id 清，上面那条应该失败 ==")
# 这一组故意用错误实现，用来证明第 6 组真的在测东西（而不是恒通过）
A.put({**A.rows["x1"], "amount": "333.00"})
out = [{k: v for k, v in r.items() if k != "dirty"} for r in A.rows.values() if r["dirty"]]
call("POST", "/v1/changes", {"expenses": out})
A.put({**A.rows["x1"], "amount": "444.00"})
for sent in out:
    A.rows[sent["id"]]["dirty"] = False                # ← 错误实现：只按 id 清
eq("错误实现下：那次改动的 dirty 被误清了（这正是要防的）", A.rows["x1"]["dirty"], False)
A.sync(); B.sync()
eq("于是 444.00 永远推不上去，B 只看到 333.00", B.visible()["x1"]["amount"], "333.00")

print("== 8. 时钟偏差：慢的那台推不上去，但**必须报出来**、不能静默丢 ==")
# C 这台设备的时钟慢了一大截（模拟用户手动把时间调错、或者 NTP 没同步上）
C = Device("C", skew=-100000)
C.sync()                                   # 先拉齐，它现在有 x1
C.put({**C.rows["x1"], "amount": "555.00"})  # 它改一笔，但 updated_at 因为时钟慢而偏小
C.sync()
eq("服务端确实没接受（数据没变）", call("GET", "/v1/changes?since=0")
   and next(e["amount"] for e in call("GET", "/v1/changes?since=0")["expenses"] if e["id"] == "x1"),
   "333.00")
eq("但服务端把它报回来了（stale 非空 → 客户端能提示用户）", len(C.last_stale) > 0, True)
ok(f"报回来的内容：{C.last_stale}")

print(f"\n===== {PASS} 通过 / {FAIL} 失败 =====")
sys.exit(1 if FAIL else 0)
