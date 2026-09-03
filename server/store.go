package main

import (
	"database/sql"
	"fmt"
	"strings"

	_ "modernc.org/sqlite"
)

// SQLite 存储层。
//
// ⚠️ 用的是 modernc.org/sqlite（纯 Go 实现，不需要 CGO）。
// 这样才能在 Mac 上交叉编译出 linux/amd64 的单个二进制直接扔到 VPS 上——
// VPS 上什么都不用装（不用装 Go、不用装 sqlite）。
// 换成 mattn/go-sqlite3 就要 CGO，交叉编译立刻变成一场折腾。

const schema = `
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;

-- 全局递增的 rev 游标。放一行、在写事务里 +1，保证分配出来的 rev 不重不漏
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL);
INSERT OR IGNORE INTO meta (k, v) VALUES ('last_rev', 0);

CREATE TABLE IF NOT EXISTS expense (
  id           TEXT    PRIMARY KEY,
  updated_at   INTEGER NOT NULL,
  deleted      INTEGER NOT NULL DEFAULT 0,
  rev          INTEGER NOT NULL,
  amount       TEXT    NOT NULL,          -- ⚠️ 字符串，见 model.go
  category_key TEXT    NOT NULL,
  note         TEXT    NOT NULL DEFAULT '',
  date         INTEGER NOT NULL,
  created_at   INTEGER NOT NULL,
  is_private   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_expense_rev ON expense(rev);

CREATE TABLE IF NOT EXISTS tag (
  id          TEXT    PRIMARY KEY,
  updated_at  INTEGER NOT NULL,
  deleted     INTEGER NOT NULL DEFAULT 0,
  rev         INTEGER NOT NULL,
  name        TEXT    NOT NULL,
  color_index INTEGER NOT NULL DEFAULT 0,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  is_archived INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tag_rev ON tag(rev);

CREATE TABLE IF NOT EXISTS category (
  id          TEXT    PRIMARY KEY,        -- ⚠️ 就是分类代号 key，见 model.go
  updated_at  INTEGER NOT NULL,
  deleted     INTEGER NOT NULL DEFAULT 0,
  rev         INTEGER NOT NULL,
  name        TEXT    NOT NULL,
  icon_name   TEXT    NOT NULL,
  color_index INTEGER NOT NULL DEFAULT 0,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  is_fallback INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_category_rev ON category(rev);

CREATE TABLE IF NOT EXISTS link (
  id         TEXT    PRIMARY KEY,         -- ⚠️ "<expense_id>:<tag_id>"，见 model.go
  updated_at INTEGER NOT NULL,
  deleted    INTEGER NOT NULL DEFAULT 0,
  rev        INTEGER NOT NULL,
  expense_id TEXT    NOT NULL,
  tag_id     TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_link_rev ON link(rev);
`

func openDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	// ⚠️ 限成一条连接：SQLite 只允许一个写者，限死之后所有请求天然串行，
	// "database is locked" 这一整类问题直接不存在。
	// 代价是并发上不去——本服务一共两台手机、一分钟几个请求，无所谓。
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		return nil, fmt.Errorf("建表失败: %w", err)
	}
	return db, nil
}

// spec 描述一张表怎么读写。四种记录各一份，这样下面的拉取/推送逻辑只写一遍。
type spec[T any] struct {
	table string
	cols  []string                    // 业务列（不含同步列）
	vals  func(*T) []any              // 与 cols 一一对应
	base  func(*T) *Base              // 取同步字段
	scan  func(*sql.Rows) (T, error)  // 一行 → 一个对象
}

var expenseSpec = spec[Expense]{
	table: "expense",
	cols:  []string{"amount", "category_key", "note", "date", "created_at", "is_private"},
	vals: func(e *Expense) []any {
		return []any{e.Amount, e.CategoryKey, e.Note, e.Date, e.CreatedAt, e.IsPrivate}
	},
	base: func(e *Expense) *Base { return &e.Base },
	scan: func(r *sql.Rows) (Expense, error) {
		var e Expense
		err := r.Scan(&e.ID, &e.UpdatedAt, &e.Deleted, &e.Rev,
			&e.Amount, &e.CategoryKey, &e.Note, &e.Date, &e.CreatedAt, &e.IsPrivate)
		return e, err
	},
}

var tagSpec = spec[Tag]{
	table: "tag",
	cols:  []string{"name", "color_index", "sort_order", "is_archived", "created_at"},
	vals: func(t *Tag) []any {
		return []any{t.Name, t.ColorIndex, t.SortOrder, t.IsArchived, t.CreatedAt}
	},
	base: func(t *Tag) *Base { return &t.Base },
	scan: func(r *sql.Rows) (Tag, error) {
		var t Tag
		err := r.Scan(&t.ID, &t.UpdatedAt, &t.Deleted, &t.Rev,
			&t.Name, &t.ColorIndex, &t.SortOrder, &t.IsArchived, &t.CreatedAt)
		return t, err
	},
}

var categorySpec = spec[Category]{
	table: "category",
	cols:  []string{"name", "icon_name", "color_index", "sort_order", "is_fallback", "created_at"},
	vals: func(c *Category) []any {
		return []any{c.Name, c.IconName, c.ColorIndex, c.SortOrder, c.IsFallback, c.CreatedAt}
	},
	base: func(c *Category) *Base { return &c.Base },
	scan: func(r *sql.Rows) (Category, error) {
		var c Category
		err := r.Scan(&c.ID, &c.UpdatedAt, &c.Deleted, &c.Rev,
			&c.Name, &c.IconName, &c.ColorIndex, &c.SortOrder, &c.IsFallback, &c.CreatedAt)
		return c, err
	},
}

var linkSpec = spec[Link]{
	table: "link",
	cols:  []string{"expense_id", "tag_id"},
	vals:  func(l *Link) []any { return []any{l.ExpenseID, l.TagID} },
	base:  func(l *Link) *Base { return &l.Base },
	scan: func(r *sql.Rows) (Link, error) {
		var l Link
		err := r.Scan(&l.ID, &l.UpdatedAt, &l.Deleted, &l.Rev, &l.ExpenseID, &l.TagID)
		return l, err
	},
}

// pull 取出 rev 比 since 大的记录，按 rev 升序。
//
// ⚠️ 返回的 Rev 必须是**这一页里实际最大的那个**，不能拿 meta.last_rev 顶替
// —— 分页截断时那样会让客户端跳过没读到的行，而且悄无声息。
func pull[T any](tx *sql.Tx, s spec[T], since int64, limit int) ([]T, int64, error) {
	q := fmt.Sprintf(
		"SELECT id, updated_at, deleted, rev, %s FROM %s WHERE rev > ? ORDER BY rev LIMIT ?",
		strings.Join(s.cols, ", "), s.table)
	rows, err := tx.Query(q, since, limit)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var out []T
	var maxRev int64
	for rows.Next() {
		item, err := s.scan(rows)
		if err != nil {
			return nil, 0, err
		}
		if r := s.base(&item).Rev; r > maxRev {
			maxRev = r
		}
		out = append(out, item)
	}
	return out, maxRev, rows.Err()
}

// push 把客户端推上来的记录写进库，冲突用「后写赢」解决。
//
// 规则：`incoming.updated_at > 库里那条的 updated_at` → 覆盖，否则丢弃。
//
// ⚠️ 严格大于，不是 `>=`。这样「把同一批数据再推一遍」是真正的空操作：
// 一条都不会写、rev 一动不动。用 `>=` 的话每次重推都会把这些行的 rev 抬高一遍，
// 另一台设备就得把这些没变过的行重新下载一次——客户端的 dirty 标记一旦有 bug，
// 两台设备会无限互相重传，而且看起来一切正常。
//
// ⚠️ 那时间戳正好撞在同一毫秒怎么办（两台设备各自改了同一条、又没看到对方的改动）？
// 服务器保留自己那份。推的那一方下次拉取时会拿到服务器这份并覆盖本地
// （它的 dirty 已经清掉了），所以最终两边一致——不会分叉。
//
// 返回值里的 stale 是**被当成旧数据丢掉的那些 id**。这个必须报出来，不能咽掉：
// 两台设备时钟偏差大时（一台快几分钟），慢的那台每次推都会被判成"旧的"、
// 它的改动**永久丢失且毫无迹象** —— 界面上一切正常，只是那笔账在另一台上永远不出现。
// 客户端拿到非空的 stale 就该提示"检查两台设备的时间"。
func push[T any](tx *sql.Tx, s spec[T], items []T, newRev int64) (applied int, stale []string, err error) {
	if len(items) == 0 {
		return 0, nil, nil
	}
	setCols := make([]string, 0, len(s.cols)+3)
	for _, c := range s.cols {
		setCols = append(setCols, c+"=excluded."+c)
	}
	setCols = append(setCols, "updated_at=excluded.updated_at", "deleted=excluded.deleted", "rev=excluded.rev")

	placeholders := strings.TrimSuffix(strings.Repeat("?, ", len(s.cols)+4), ", ")
	q := fmt.Sprintf(
		`INSERT INTO %s (id, updated_at, deleted, rev, %s) VALUES (%s)
		 ON CONFLICT(id) DO UPDATE SET %s
		 WHERE excluded.updated_at > %s.updated_at`,
		s.table, strings.Join(s.cols, ", "), placeholders, strings.Join(setCols, ", "), s.table)

	stmt, err := tx.Prepare(q)
	if err != nil {
		return 0, nil, err
	}
	defer stmt.Close()

	// 判「没写进去是因为旧、还是因为一模一样」要现查一次库里那条的 updated_at。
	// 一模一样的重推是正常的（客户端重试、断网补发都会这样），不该报警；
	// 真的被判旧才要报出去。
	prev, err := tx.Prepare(fmt.Sprintf("SELECT updated_at FROM %s WHERE id = ?", s.table))
	if err != nil {
		return 0, nil, err
	}
	defer prev.Close()

	for i := range items {
		b := s.base(&items[i])
		if b.ID == "" {
			return applied, stale, fmt.Errorf("%s 里有一条记录没带 id", s.table)
		}

		var before int64 = -1
		_ = prev.QueryRow(b.ID).Scan(&before) // 查不到就是新记录，保持 -1

		args := append([]any{b.ID, b.UpdatedAt, b.Deleted, newRev}, s.vals(&items[i])...)
		res, err := stmt.Exec(args...)
		if err != nil {
			return applied, stale, fmt.Errorf("写 %s/%s 失败: %w", s.table, b.ID, err)
		}
		if n, _ := res.RowsAffected(); n > 0 {
			applied++
		} else if before > b.UpdatedAt {
			// 库里那条**严格更新** → 这次推送是真的被当成旧数据丢掉了
			stale = append(stale, s.table+"/"+b.ID)
		}
		// before == b.UpdatedAt 的情况是"原样重推"，正常，不计入 stale
	}
	return applied, stale, nil
}

// nextRev 在写事务里把全局 rev 游标 +1 并返回。
// 同一批推送共用一个 rev —— 客户端只关心「比我见过的大」，不需要每条一个号。
func nextRev(tx *sql.Tx) (int64, error) {
	if _, err := tx.Exec("UPDATE meta SET v = v + 1 WHERE k = 'last_rev'"); err != nil {
		return 0, err
	}
	var rev int64
	err := tx.QueryRow("SELECT v FROM meta WHERE k = 'last_rev'").Scan(&rev)
	return rev, err
}

func lastRev(db *sql.DB) (int64, error) {
	var rev int64
	err := db.QueryRow("SELECT v FROM meta WHERE k = 'last_rev'").Scan(&rev)
	return rev, err
}
