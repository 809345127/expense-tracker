package com.shize.expensetracker

import android.app.Application

/// 整个 app 的入口。依赖在这里一次性组装好往下传（这个 app 规模小，
/// 不引 Hilt 那套注解生成——省一层构建复杂度，代价是自己写几行 lazy）。
class App : Application()
