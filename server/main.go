package main

import (
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"errors"
	"flag"
	"log"
	"math"
	"net/http"
	"os"
	"strconv"
	"time"
)

// 记账 app 的同步服务。一个人、两台手机、几千条记录——按这个量级设计的，
// 所以没有用户系统、没有 ORM、没有缓存，一个二进制 + 一个 SQLite 文件。
//
// 协议见 README.md。部署见 deploy.sh。

const (
	defaultLimit = 1000
	maxLimit     = 5000
	maxBodyBytes = 8 << 20 // 8MB，够推几万条
)

type server struct {
	db    *sql.DB
	token string
}

func main() {
	addr := flag.String("addr", env("ADDR", ":8787"), "监听地址")
	dbPath := flag.String("db", env("DB", "expense-sync.db"), "SQLite 文件路径")
	flag.Parse()

	// ⚠️ token 只从环境变量读，绝不写进代码——这个仓库是公开的。
	token := os.Getenv("TOKEN")
	if len(token) < 16 {
		log.Fatal("必须设置环境变量 TOKEN（至少 16 个字符）。生成一个：openssl rand -hex 32")
	}

	db, err := openDB(*dbPath)
	if err != nil {
		log.Fatalf("打不开数据库 %s: %v", *dbPath, err)
	}
	defer db.Close()

	rev, err := lastRev(db)
	if err != nil {
		log.Fatalf("读 rev 游标失败: %v", err)
	}

	s := &server{db: db, token: token}
	mux := http.NewServeMux()
	// health 不要求认证，方便 curl 一下看服务活没活；它什么信息都不吐
	mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	mux.HandleFunc("GET /v1/changes", s.auth(s.handlePull))
	mux.HandleFunc("POST /v1/changes", s.auth(s.handlePush))
	mux.HandleFunc("GET /v1/export", s.auth(s.handleExport))

	log.Printf("同步服务启动：%s  库=%s  当前 rev=%d", *addr, *dbPath, rev)
	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// auth 校验 Bearer token。
// ⚠️ 用 subtle.ConstantTimeCompare 而不是 ==：普通字符串比较会在第一个不同的字节就返回，
// 理论上能靠计时差一个字节一个字节猜出 token。
func (s *server) auth(next http.HandlerFunc) http.HandlerFunc {
	want := []byte("Bearer " + s.token)
	return func(w http.ResponseWriter, r *http.Request) {
		got := []byte(r.Header.Get("Authorization"))
		if len(got) != len(want) || subtle.ConstantTimeCompare(got, want) != 1 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

// handlePull：GET /v1/changes?since=<rev>&limit=<n>
// 返回 rev 比 since 大的全部记录（含删除墓碑——客户端要靠它才知道有东西被删了）。
func (s *server) handlePull(w http.ResponseWriter, r *http.Request) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	limit := defaultLimit
	if v, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && v > 0 {
		limit = min(v, maxLimit)
	}

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, err)
		return
	}
	defer tx.Rollback()

	var out Payload
	// 每张表各自的「取到哪了 / 有没有被截断」，下面算游标要用
	type page struct {
		maxRev    int64
		truncated bool
	}
	var pages []page

	expenses, maxE, err := pull(tx, expenseSpec, since, limit)
	if err != nil {
		fail(w, err)
		return
	}
	out.Expenses, pages = expenses, append(pages, page{maxE, len(expenses) == limit})

	tags, maxT, err := pull(tx, tagSpec, since, limit)
	if err != nil {
		fail(w, err)
		return
	}
	out.Tags, pages = tags, append(pages, page{maxT, len(tags) == limit})

	cats, maxC, err := pull(tx, categorySpec, since, limit)
	if err != nil {
		fail(w, err)
		return
	}
	out.Categories, pages = cats, append(pages, page{maxC, len(cats) == limit})

	links, maxL, err := pull(tx, linkSpec, since, limit)
	if err != nil {
		fail(w, err)
		return
	}
	out.Links, pages = links, append(pages, page{maxL, len(links) == limit})

	// ⚠️⚠️ 游标只能推进到「四张表都已经取干净」的那个位置。
	//
	// 反例（不这么算就会静默丢数据）：账目表有 rev 1..500 但被 limit=200 截断、只返回到 200，
	// 而标签表只有一条 rev=600。如果游标取四张表的最大值 600，客户端下次 since=600，
	// 账目的 201..500 就**永远拉不到了**，而且没有任何报错。
	//
	// 所以：有表被截断时，游标取「所有被截断的表各自返回的最大 rev」里最小的那个
	// —— 到这个位置为止，每张表都是完整的。没有表被截断才可以推进到全局最大值。
	cursor := int64(math.MaxInt64)
	truncated, maxAll := false, since
	for _, p := range pages {
		if p.maxRev > maxAll {
			maxAll = p.maxRev
		}
		if p.truncated {
			truncated = true
			cursor = min(cursor, p.maxRev)
		}
	}
	if truncated {
		out.Rev, out.HasMore = cursor, true
	} else {
		out.Rev = maxAll
	}

	if err := tx.Commit(); err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, out)
}

// handlePush：POST /v1/changes
// 一整批（四种记录混在一个信封里）在同一个事务里落库，共用一个新 rev。
// 冲突规则「后写赢」在 store.go 的 push 里，客户端必须实现同一条规则。
func (s *server) handlePush(w http.ResponseWriter, r *http.Request) {
	var in Payload
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err := dec.Decode(&in); err != nil {
		http.Error(w, "请求体不是合法 JSON: "+err.Error(), http.StatusBadRequest)
		return
	}
	if in.count() == 0 {
		// 空推送是合法的（客户端没有本地改动时也会走这条路），直接回当前 rev
		rev, err := lastRev(s.db)
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, map[string]any{"rev": rev, "applied": 0})
		return
	}

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, err)
		return
	}
	defer tx.Rollback()

	rev, err := nextRev(tx)
	if err != nil {
		fail(w, err)
		return
	}

	applied := 0
	stale := []string{}
	for _, n := range []func() (int, []string, error){
		func() (int, []string, error) { return push(tx, expenseSpec, in.Expenses, rev) },
		func() (int, []string, error) { return push(tx, tagSpec, in.Tags, rev) },
		func() (int, []string, error) { return push(tx, categorySpec, in.Categories, rev) },
		func() (int, []string, error) { return push(tx, linkSpec, in.Links, rev) },
	} {
		k, s, err := n()
		if err != nil {
			fail(w, err)
			return
		}
		applied += k
		stale = append(stale, s...)
	}

	if err := tx.Commit(); err != nil {
		fail(w, err)
		return
	}

	// ⚠️ stale 非空 = 有改动**被当成旧数据丢掉了**。这几乎只有一个原因：
	// 这台设备的时钟比另一台慢。必须让客户端看得见 —— 咽掉的话那些改动就是
	// 「永久丢失且毫无迹象」，界面上一切正常，只是那笔账在另一台上永远不出现。
	if len(stale) > 0 {
		log.Printf("⚠️ 推送里有 %d 条被判为旧数据丢弃（大概率是设备时钟慢了）：%v", len(stale), stale)
	}
	log.Printf("推送：收到 %d 条，写入 %d 条，rev=%d", in.count(), applied, rev)
	writeJSON(w, map[string]any{
		"rev": rev, "received": in.count(), "applied": applied, "stale": stale,
	})
}

// handleExport 全量导出（含墓碑）。备份和排障用，客户端正常同步不走这里。
func (s *server) handleExport(w http.ResponseWriter, r *http.Request) {
	r.URL.RawQuery = "since=0&limit=" + strconv.Itoa(maxLimit)
	s.handlePull(w, r)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("写响应失败: %v", err)
	}
}

func fail(w http.ResponseWriter, err error) {
	log.Printf("出错: %v", err)
	http.Error(w, "internal error", http.StatusInternalServerError)
}
