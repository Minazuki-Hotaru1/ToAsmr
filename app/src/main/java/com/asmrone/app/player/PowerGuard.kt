package com.asmrone.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保护 = 系统的「电池优化」白名单。
 *
 * 为什么需要它：前台服务只是"声明我在干活"，省电策略仍然可能在息屏一段时间后
 * 限制进程（国产 ROM 尤其激进）。用户感知到的现象是"听着听着自己停了"，
 * 而这类问题在日志里什么都没有 —— 进程被冻结，来不及写日志。
 *
 * 这里只做两件事：**读状态**、**跳转到系统里能改它的地方**。不去写
 * `Settings.Secure` 的白名单（需要系统权限，普通应用做不到）。
 */
object PowerGuard {

    /**
     * 当前是否已在白名单里。
     *
     * 读不到就返回 false：宁可让用户点一次进设置确认，也不要骗他说"已经好了"。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * 打开能让用户关掉电池优化的系统界面。
     *
     * 三级退化，因为这三条路各自都有 ROM 不支持：
     * 1. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` —— 直接弹"是否允许"，
     *    一步到位；部分 ROM 把这条 action 屏蔽了（会抛 ActivityNotFound）。
     * 2. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` —— 电池优化列表，用户自己找本应用。
     * 3. `ACTION_APPLICATION_DETAILS_SETTINGS` —— 应用详情页，任何设备都有。
     *
     * @return 有没有成功打开某个界面。全失败时调用方负责给出提示。
     */
    fun tryOpenSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
        for (intent in candidates) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return true
        }
        return false
    }
}
