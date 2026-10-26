package com.asmrone.app.data.update

import android.content.Context

/**
 * 安装包的版本名。
 *
 * 从 PackageManager 读，不写死 —— 写死的那份永远不会跟着 `build.gradle.kts` 走，
 * 一旦发版忘了改，更新检查会把"当前版本"判断错（新版本装上了还提示更新）。
 */
object AppVersion {
    fun installedName(context: Context): String = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?.trim()
            .orEmpty()
    }.getOrDefault("")
}
