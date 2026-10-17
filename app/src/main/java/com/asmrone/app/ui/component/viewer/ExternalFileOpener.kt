package com.asmrone.app.ui.component.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载到缓存的**文件**交给外部 App 打开（目前只用于 PDF）。
 *
 * ## 为什么必须走 FileProvider
 *
 * Android 7（API 24）起禁止把 `file://` URI 暴露给别的 App
 * （`FileUriExposedException`）。所以要先经 `FileProvider` 换成一个
 * 可授权的 `content://` URI，再配 `FLAG_GRANT_READ_URI_PERMISSION`
 * 让对方读得到 —— 否则外部阅读器拿到 URI 也打不开。
 *
 * ## 为什么只有 PDF 走这条路
 *
 * 实测判断：
 *  - **PDF**：系统/厂商的 PDF 阅读器在缩放、翻页、搜索、目录上都远好于
 *    自研（`PdfRenderer` 只能逐页渲染成 bitmap，那些功能全要自己写）。
 *  - **txt**：Android **没有**内置纯文本查看器，也没有约定俗成的第三方接管
 *    `text/plain`，外跳的常见结果是"没有应用可执行此操作"。
 *    所以文本一律走内置查看器（见 [TextViewerDialog]）。
 */
object ExternalFileOpener {

    /** 各类型对应的 MIME；给不出的用通配 MIME 让系统自己挑 */
    private fun mimeOf(fileName: String): String = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "pdf" -> "application/pdf"
        "txt", "md", "csv" -> "text/plain"
        else -> "*/*"
    }

    /**
     * 尝试用外部 App 打开 [file]。
     *
     * @return 是否成功发出 Intent。false 表示系统里没有能处理它的 App，
     *   调用方**必须**给出可见提示 —— 静默失败会让用户以为点击没生效。
     */
    fun open(context: Context, file: File, fileName: String): Boolean {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: IllegalArgumentException) {
            // 文件不在 file_paths.xml 声明的目录里 —— 说明配置与代码不一致
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
