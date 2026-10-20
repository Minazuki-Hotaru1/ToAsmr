package com.asmrone.app.data.download

/**
 * 下载内容存在哪。
 *
 * - [APP]：存放在 App 私有目录 `filesDir/downloads/`，文件管理器看不到，
 *   卸载 App 一起删。这是历史行为，界面上叫「系统保存」。
 * - [LOCAL]：存放在公共存储（默认 `/storage/emulated/0/ToAsmr`）里，
 *   按「作品名 [RJxxxxxxx]/原目录结构/原文件名」落盘，文件管理器能直接翻到、
 *   也能用别的播放器播。它**不进** App 的下载目录，因此下载页要把两边合并显示。
 *
 * 之所以做成"逐个任务记录"而不是"读全局开关"：用户在下载进行中切了开关，
 * 已经排队的任务不应该跑到一半换目录（半份文件在两个地方，续传就对不上了）。
 */
enum class DownloadStorage {
    APP,
    LOCAL;

    val label: String
        get() = when (this) {
            APP -> "系统"
            LOCAL -> "本地"
        }
}
