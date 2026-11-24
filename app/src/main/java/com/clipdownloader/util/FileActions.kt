package com.clipdownloader.util

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 已下载文件的打开 / 分享 / 定位文件夹。首页下载列表与作品详情页共用。
 */
object FileActions {

    /** 已知厂商相册包名（优先用相册打开视频/图片，而不是视频播放器） */
    private val galleryPackages = listOf(
        "com.coloros.gallery3d",         // OPPO/一加 相册
        "com.heytap.gallery3d",
        "com.oppo.gallery3d",
        "com.miui.gallery",              // 小米相册
        "com.sec.android.gallery3d",     // 三星相册
        "com.huawei.photos",             // 华为图库
        "com.google.android.apps.photos" // Google 相册
    )

    private fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: when (file.extension.lowercase()) {
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "opus" -> "audio/opus"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "webp" -> "image/webp"
                else -> "*/*"
            }

    private fun viewIntent(context: Context, file: File, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), mime
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 用相册 / 播放器打开文件；文件已被移动或删除时提示，不抛异常 */
    fun open(context: Context, path: String) {
        try {
            val file = File(path)
            // 文件已被移动/删除（或路径失效）时直接提示，不要走 FileProvider 抛异常
            // 再闷声掉进「打开系统相册」的兜底——用户看到的就是「点了没反应/跳相册」
            if (!file.exists()) {
                Toast.makeText(context, "文件已被移动或删除", Toast.LENGTH_SHORT).show()
                return
            }
            val mime = mimeOf(file)
            val intent = viewIntent(context, file, mime)
            // 视频/图片优先交给厂商相册（系统默认会路由到视频播放器）
            if (mime.startsWith("video/") || mime.startsWith("image/")) {
                for (pkg in galleryPackages) {
                    try {
                        val probe = Intent(intent).setPackage(pkg)
                        if (context.packageManager.resolveActivity(probe, 0) != null) {
                            context.startActivity(probe)
                            return
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 没有查看器时退回打开系统相册
            try {
                val pm = context.packageManager
                val gallery = pm.getLaunchIntentForPackage("com.coloros.gallery3d")
                    ?: pm.getLaunchIntentForPackage("com.android.gallery3d")
                    ?: Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_GALLERY) }
                context.startActivity(gallery)
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开文件，请在文件管理器中查看", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 用系统分享面板分享一个已下载的文件 */
    fun share(context: Context, path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "文件已被移动或删除", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, file.name))
        } catch (e: Exception) {
            LogFile.w("FileActions", "分享失败: ${e.message}")
            Toast.makeText(context, "无法分享文件：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开文件所在的文件夹，按命中率依次尝试：
     * 1. 系统文件 App（DocumentsUI）document URI —— 能直接定位到文件夹本身；
     * 2. FileProvider 目录 VIEW，mime 用 vnd.android.document/directory 与
     *    resource/folder 各试一次（后者是部分厂商文件管理器——含 OPPO——监听的旧约定）；
     * 3. 系统文件夹选择器 + EXTRA_INITIAL_URI —— 全机型保证打开且定位到目标文件夹
     *    （选择器界面可直接浏览该文件夹内容）。
     */
    fun openFolder(context: Context, path: String) {
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()
        val rel = path
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")
            .trimStart('/')
        val docUri = if (rel.isNotBlank() && rel != path)
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        else null

        // 1) DocumentsUI document URI
        if (docUri != null) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                return
            } catch (_: Exception) {
            }
        }
        // 2) FileProvider 目录 VIEW（两种 mime，覆盖不同厂商文件管理器）
        for (mime in listOf(
            DocumentsContract.Document.MIME_TYPE_DIR,
            "resource/folder"
        )) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                return
            } catch (_: Exception) {
            }
        }
        // 3) 系统文件夹选择器（保证可用，初始位置即目标文件夹）
        try {
            context.startActivity(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    if (docUri != null) {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            return
        } catch (_: Exception) {
        }
        Toast.makeText(context, "未找到文件管理器，请手动查看：$path", Toast.LENGTH_LONG).show()
    }
}
