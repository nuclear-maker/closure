package com.closure.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaItem(
    val id: Long,
    val isVideo: Boolean,
    val dateMs: Long,
    val bucket: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val name: String = "",
    /** 系统相册的收藏标记（MediaStore.IS_FAVORITE），其他 App 也能识别 */
    val isFavorite: Boolean = false,
) {
    val uri: Uri by lazy {
        ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id
        )
    }

    /** 长图（截屏/长截图）：高宽比 ≥ 2.5，提供「展开全图」滚动浏览 */
    fun isLongImage(): Boolean =
        !isVideo && width > 0 && height > 0 && height.toFloat() / width >= 2.5f

    fun fileName(): String =
        name.ifBlank { if (isVideo) "VID_$id" else "IMG_$id" }

    /** 左下角时间：精确到分 */
    fun timeLine(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(dateMs))

    /** 相册 · 大小（· 时长），跟在左下角时间下面 */
    fun metaLine(): String = buildString {
        append(bucket).append(" · ").append(formatSize(sizeBytes))
        if (isVideo && durationMs > 0) append(" · ").append(formatDuration(durationMs))
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1048576f)
        bytes >= 1 shl 10 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
