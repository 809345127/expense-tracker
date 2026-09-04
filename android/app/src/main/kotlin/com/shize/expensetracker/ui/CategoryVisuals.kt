package com.shize.expensetracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
// 有方向性的图标要用 AutoMirrored 版本（RTL 语言下自动镜像），编译器会提示
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// 分类的图标和颜色。
//
// ⚠️⚠️ **图标名是 iOS 的 SF Symbols 名**（`fork.knife`、`tram.fill` 这种），
// 因为它跟着数据一起同步 —— 分类是在 iOS 上建的，安卓这边拉到的就是那个名字。
// 安卓没有 SF Symbols，所以必须有这张映射表。
//
// ⚠️ **绝对不要为了"安卓这边好写"就把同步过来的图标名改掉**：
// 名字改了会推回 iOS，那边就找不到图标了（SF Symbols 里没有 Material 的名字），
// 结果是 iPhone 上一片灰问号。图标名的真相在 iOS 那份 `CategoryIconLibrary` 里。
//
// 映射不上的走兜底问号 —— 这样漏了一个也只是图标不好看，不会崩、也不会污染数据。

private val iconMap: Map<String, ImageVector> = mapOf(
    // 吃喝
    "fork.knife" to Icons.Filled.Restaurant,
    "cup.and.saucer.fill" to Icons.Filled.LocalCafe,
    "wineglass.fill" to Icons.Filled.WineBar,
    "birthday.cake.fill" to Icons.Filled.Cake,
    "carrot.fill" to Icons.Filled.Eco,
    "takeoutbag.and.cup.and.straw.fill" to Icons.Filled.Fastfood,
    // 出行
    "tram.fill" to Icons.Filled.Tram,
    "car.fill" to Icons.Filled.DirectionsCar,
    "airplane" to Icons.Filled.Flight,
    "bicycle" to Icons.AutoMirrored.Filled.DirectionsBike,
    "fuelpump.fill" to Icons.Filled.LocalGasStation,
    "map.fill" to Icons.Filled.Map,
    "suitcase.fill" to Icons.Filled.Luggage,
    "ferry.fill" to Icons.Filled.DirectionsBoat,
    // 居家
    "house.fill" to Icons.Filled.Home,
    "bed.double.fill" to Icons.Filled.Bed,
    "lightbulb.fill" to Icons.Filled.Lightbulb,
    "washer.fill" to Icons.Filled.LocalLaundryService,
    "wrench.and.screwdriver.fill" to Icons.Filled.Build,
    "leaf.fill" to Icons.Filled.Grass,
    // 购物
    "bag.fill" to Icons.Filled.ShoppingBag,
    "cart.fill" to Icons.Filled.ShoppingCart,
    "giftcard.fill" to Icons.Filled.CardGiftcard,
    "shippingbox.fill" to Icons.Filled.Inventory2,
    "tshirt.fill" to Icons.Filled.Checkroom,
    "shoe.fill" to Icons.Filled.Hiking,
    // 身体
    "cross.case.fill" to Icons.Filled.MedicalServices,
    "pills.fill" to Icons.Filled.Medication,
    "stethoscope" to Icons.Filled.MonitorHeart,
    "figure.run" to Icons.AutoMirrored.Filled.DirectionsRun,
    "dumbbell.fill" to Icons.Filled.FitnessCenter,
    "scissors" to Icons.Filled.ContentCut,
    "comb.fill" to Icons.Filled.Face,
    // 学习娱乐
    "book.fill" to Icons.AutoMirrored.Filled.MenuBook,
    "graduationcap.fill" to Icons.Filled.School,
    "gamecontroller.fill" to Icons.Filled.SportsEsports,
    "film.fill" to Icons.Filled.Movie,
    "music.note" to Icons.Filled.MusicNote,
    "ticket.fill" to Icons.Filled.ConfirmationNumber,
    "camera.fill" to Icons.Filled.PhotoCamera,
    "paintbrush.fill" to Icons.Filled.Brush,
    // 人情往来
    "gift.fill" to Icons.Filled.Redeem,
    "heart.fill" to Icons.Filled.Favorite,
    "person.2.fill" to Icons.Filled.People,
    "hands.clap.fill" to Icons.Filled.EmojiPeople,
    // 钱与其它
    "arrow.triangle.2.circlepath" to Icons.Filled.Autorenew,
    "creditcard.fill" to Icons.Filled.CreditCard,
    "banknote.fill" to Icons.Filled.Payments,
    "pawprint.fill" to Icons.Filled.Pets,
    "phone.fill" to Icons.Filled.Phone,
    "wifi" to Icons.Filled.Wifi,
    "ellipsis.circle.fill" to Icons.Filled.MoreHoriz,
    "questionmark.circle.fill" to Icons.Filled.QuestionMark,
)

/// SF Symbols 名 → Material 图标。映射不上就给个问号（不崩、不改数据）
fun categoryIcon(sfSymbolName: String): ImageVector =
    iconMap[sfSymbolName] ?: Icons.Filled.QuestionMark

/// 分类配色。**顺序必须跟 iOS 那份 `CategoryPalette.colors` 一一对应** ——
/// 同步过来的是**下标**不是色值，两边顺序不一致的话同一个分类在两台设备上会是不同颜色。
private val categoryColors = listOf(
    Color(0xFFFF9500), // orange
    Color(0xFF007AFF), // blue
    Color(0xFFFF2D55), // pink
    Color(0xFFA2845E), // brown
    Color(0xFFAF52DE), // purple
    Color(0xFFFF3B30), // red
    Color(0xFF5856D6), // indigo
    Color(0xFF00C7BE), // mint
    Color(0xFF32ADE6), // cyan
    Color(0xFF8E8E93), // gray
    Color(0xFF34C759), // green
    Color(0xFF30B0C7), // teal
    Color(0xFFFFCC00), // yellow
)

fun categoryColor(index: Int): Color =
    categoryColors[((index % categoryColors.size) + categoryColors.size) % categoryColors.size]

/// 标签配色。同上，顺序对齐 iOS 的 `TagPalette.colors`
private val tagColors = listOf(
    Color(0xFF007AFF), // blue
    Color(0xFF34C759), // green
    Color(0xFFFF9500), // orange
    Color(0xFFFF2D55), // pink
    Color(0xFFAF52DE), // purple
    Color(0xFF00C7BE), // teal
    Color(0xFF5856D6), // indigo
    Color(0xFFA2845E), // brown
)

fun tagColor(index: Int): Color =
    tagColors[((index % tagColors.size) + tagColors.size) % tagColors.size]
