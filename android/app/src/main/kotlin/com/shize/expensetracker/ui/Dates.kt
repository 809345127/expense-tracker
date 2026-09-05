package com.shize.expensetracker.ui

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 时间处理。
//
// ⚠️ 一律按**手机当前时区**显示（跟 iOS 那边一致）。
// 存的是绝对时刻（毫秒时间戳），显示时才转成本地时间 —— 所以出国时看到的时间会变，
// 这是刻意的：一笔账应该显示"我当时在的那个地方几点"还是"现在这个地方几点"，
// iOS 那边选了后者，两端必须一致，否则同一笔账在两台设备上显示不同时间。

private val zone: ZoneId get() = ZoneId.systemDefault()

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/// 某个月的起止（左闭右开），毫秒
fun YearMonth.rangeMillis(): Pair<Long, Long> {
    val from = atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return from to to
}

fun YearMonth.title(): String = "${year}年${monthValue}月"

private val dayFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)

fun LocalDate.dayTitle(): String {
    val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[dayOfWeek.value - 1]
    return "${format(dayFmt)} $week"
}

fun Long.timeText(): String = Instant.ofEpochMilli(this).atZone(zone).format(timeFmt)

private val fullDayFmt = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

/// 表单里那一行「2026年9月5日」。带年份 —— 补记的账可能跨年，不带年会看不出来
fun Long.dateText(): String = Instant.ofEpochMilli(this).atZone(zone).format(fullDayFmt)

/// 毫秒时间戳 → 本地日期时间的各个部分，给日期/时间选择器当初值
fun Long.localDateTime(): java.time.LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

/// 把「某一天」和「某个时刻」拼回毫秒时间戳。
///
/// ⚠️ **秒是从 `keepSecondsFrom` 那个时间戳里继承下来的**，不清成 0。
/// 理由：Material 的 `TimePicker` 只给时和分，而这个 app 的列表行显示到秒
///（iOS 那边也显示到秒，两端一致）。清成 0 的话，「当场记一笔、顺手把时间往前调五分钟」
/// 这个动作会把秒抹掉、看着像整点整分录的假数据。影响面最多 59 秒，继承更诚实。
fun composeTimestamp(date: LocalDate, hour: Int, minute: Int, keepSecondsFrom: Long): Long {
    val secs = Instant.ofEpochMilli(keepSecondsFrom).atZone(zone).second
    return date.atTime(hour, minute, secs).atZone(zone).toInstant().toEpochMilli()
}

private val stampFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
private val fullStampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

/// 补记时那一行要显示的「创建时间」。
///
/// ⚠️ **同年只写月日，跨年才补上年份** —— 跟 iOS 一模一样，而且这个分情况是有原因的：
/// 列表上唯一的年份锚点是顶部那张月份卡片。12 月 31 日花的钱、元旦凌晨才补记时，
/// 只写 `01-01` 会被读成同一年的 1 月 1 日 —— 看着像「钱还没花就先建了记录」，
/// 把创建时间的意义整个读反。
fun createdStampText(date: Long, createdAt: Long): String {
    val d = Instant.ofEpochMilli(date).atZone(zone)
    val c = Instant.ofEpochMilli(createdAt).atZone(zone)
    return c.format(if (d.year == c.year) stampFmt else fullStampFmt)
}

/// 这笔账是不是"事后补记的"：创建时间比记账时间晚很多。
/// ⚠️ 判据跟 iOS 对齐 —— 当场记的账两个时间只差几十秒，那种情况不显示创建时间（纯噪音）
fun isBackfilled(date: Long, createdAt: Long): Boolean = createdAt - date > 10 * 60 * 1000
