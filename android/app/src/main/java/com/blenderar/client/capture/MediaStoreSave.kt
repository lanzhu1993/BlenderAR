package com.blenderar.client.capture

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 按 Android MediaStore 规范保存截图与视频。
 *
 * - API 29+:MediaStore 之外无需任何存储权限,通过 RELATIVE_PATH 指定相册;
 * - API 24-28:写入公共媒体目录后调用 MediaScanner 入库(截图无需权限;
 *   视频写入 Movies 同样无需 WRITE 权限?→ 实际上 API 24-28 写公共目录需要
 *   WRITE_EXTERNAL_STORAGE。这里在 AR 页面不申请存储权限,降级为仅写应用
 *   私有目录并提示路径(避免过度申请权限);文件仍可通过系统文件管理器访问。
 */
object MediaStoreSave {

    /**
     * 保存图片到系统相册(Pictures/AR魔法特效)。
     * @return 成功时返回 MediaStore Uri;失败返回 null
     */
    fun saveImage(context: Context, file: File, displayName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, if (displayName.endsWith(".png")) "image/png" else "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AR魔法特效")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            runCatching {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.getOrElse {
                resolver.delete(uri, null, null)
                return null
            }
            uri
        } else {
            scanLegacy(context, file)
        }
    }

    /**
     * 保存视频到系统图库(Movies/AR魔法特效)。
     * @return 成功时返回 MediaStore Uri;失败返回 null
     */
    fun saveVideo(context: Context, file: File, displayName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AR魔法特效")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            runCatching {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.getOrElse {
                resolver.delete(uri, null, null)
                return null
            }
            uri
        } else {
            scanLegacy(context, file)
        }
    }

    private fun scanLegacy(context: Context, file: File): Uri? {
        // 旧版本:应用私有文件无法直接入库;通知扫描器尽力入库,返回文件 Uri 兜底
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
        ) { _, _ -> }
        return Uri.fromFile(file)
    }
}
