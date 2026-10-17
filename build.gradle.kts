// 顶层构建脚本 —— 与 comicApp 保持同一套 AGP / Kotlin 版本，便于共享构建缓存
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
