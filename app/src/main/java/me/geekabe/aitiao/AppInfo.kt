package me.geekabe.aitiao

import android.graphics.drawable.Drawable

/**
 * 已安装应用的数据模型
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable
) {
    /** 当前跳过状态，默认不跳过 */
    var skipStatus: SkipStatus = SkipStatus.DONT_SKIP
}

/** 跳过状态枚举 */
enum class SkipStatus(val label: String) {
    SKIP("跳过"),
    DONT_SKIP("不跳过")
}
