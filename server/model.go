package main

// 同步协议里的四种记录。设计理由全在 README.md 的「协议」一节，改这里之前先读那一节。
//
// ⚠️ 四种记录共用同一套同步字段（下面的 Base），客户端合并逻辑因此只用写一遍。

// Base 是每条记录都有的同步字段。
type Base struct {
	// 稳定 id。**客户端生成**，一辈子不变。
	// ⚠️ 不能用数据库自增主键：两台设备各自从 1 开始自增，一合并必然张冠李戴。
	ID string `json:"id"`

	// 这条记录最后一次被改动的时刻（毫秒时间戳），由**改动它的那个客户端**写。
	// 合并时用它判谁赢（后写赢）。
	UpdatedAt int64 `json:"updated_at"`

	// 删除墓碑。删除 = 把这个置 1，**不是**把行删掉。
	// ⚠️ 真删行的话，另一台设备下次同步会把它原样送回来。
	Deleted bool `json:"deleted"`

	// 服务器分配的全局递增序号，客户端只读。
	// 客户端记住自己见过的最大 rev，下次只拉比它大的（增量同步）。
	Rev int64 `json:"rev"`
}

// Expense 一笔账。
type Expense struct {
	Base
	// ⚠️⚠️ 金额是**字符串**，不是浮点数。
	// iOS 用 Decimal、安卓用 BigDecimal，中间用字符串传，全程不碰 float——
	// 记账 app 里 0.1+0.2 这种误差是不能接受的，而 JSON 的 number 在很多语言里就是 double。
	Amount string `json:"amount"`

	// 分类代号。⚠️ 是代号不是显示名，对应 Category.ID
	CategoryKey string `json:"category_key"`
	Note        string `json:"note"`
	Date        int64  `json:"date"`       // 记账时间（这笔钱花出去的时刻）
	CreatedAt   int64  `json:"created_at"` // 创建时间（写进库的时刻）
	IsPrivate   bool   `json:"is_private"`
}

// Tag 标签。
type Tag struct {
	Base
	Name       string `json:"name"`
	ColorIndex int    `json:"color_index"`
	SortOrder  int    `json:"sort_order"`
	IsArchived bool   `json:"is_archived"`
	CreatedAt  int64  `json:"created_at"`
}

// Category 分类。
//
// ⚠️ 它的 ID **就是分类代号 key**（不是另发一个 UUID）。两个原因：
//  1. iOS 侧本来就有这个 key，而且设计上「建好之后永不改」（改名只改显示名），所以它天生稳定；
//  2. 两台设备各自新建一个同名分类时，代号是按名字算出来的、会算出同一个，于是自动并成一条。
//     要是发 UUID，就会变成两条一模一样的分类。
type Category struct {
	Base
	Name       string `json:"name"`
	IconName   string `json:"icon_name"`
	ColorIndex int    `json:"color_index"`
	SortOrder  int    `json:"sort_order"`
	IsFallback bool   `json:"is_fallback"`
	CreatedAt  int64  `json:"created_at"`
}

// Link 「某笔账挂了某个标签」这件事本身。
//
// ⚠️ 必须当成一条独立记录来同步（带自己的删除墓碑），否则「取消一个标签」这个动作传不过去
// —— 账目那条记录的字段一个都没变，另一台设备察觉不到。
//
// ⚠️ 它的 ID 是拼出来的：`<expense_id>:<tag_id>`，确定性。
// 两台设备各自给同一笔账打同一个标签，算出的 id 相同，自动并成一条；发 UUID 就会变成两条。
type Link struct {
	Base
	ExpenseID string `json:"expense_id"`
	TagID     string `json:"tag_id"`
}

// LinkID 按约定拼出关联记录的 id。两个客户端必须用同一个拼法。
func LinkID(expenseID, tagID string) string { return expenseID + ":" + tagID }

// Payload 是拉取和推送共用的信封。
type Payload struct {
	// 拉取时：这一页里最大的 rev（客户端下次拿它当 since）。推送时：忽略。
	Rev int64 `json:"rev"`
	// 拉取时：还有没有下一页。true 就带着新的 rev 再拉一次。
	HasMore bool `json:"has_more,omitempty"`

	Expenses   []Expense  `json:"expenses,omitempty"`
	Tags       []Tag      `json:"tags,omitempty"`
	Categories []Category `json:"categories,omitempty"`
	Links      []Link     `json:"links,omitempty"`
}

func (p *Payload) count() int {
	return len(p.Expenses) + len(p.Tags) + len(p.Categories) + len(p.Links)
}
