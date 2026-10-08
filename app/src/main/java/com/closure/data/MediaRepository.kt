package com.closure.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

class MediaRepository(context: Context) {

    private val resolver = context.contentResolver

    data class Library(val items: List<MediaItem>, val bucketCounts: Map<String, Int>)

    fun loadLibrary(): Library {
        val images = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        val items = (images + videos).sortedByDescending { it.dateMs }
        val bucketCounts = items.groupingBy { it.bucket }.eachCount()
        return Library(items, bucketCounts)
    }

    /**
     * 直接写入系统收藏标记（MediaStore.IS_FAVORITE），写成功后 Google 相册这类
     * 系统 App 也能看到。需要「所有文件访问」权限；没权限时返回 false，
     * 交给调用方回退到 MediaStore.createFavoriteRequest 的系统确认弹窗。
     */
    fun setFavorite(uri: Uri, favorite: Boolean): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
        }
        resolver.update(uri, values, null, null) >= 0
    }.getOrDefault(false)

    private fun query(uri: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.IS_FAVORITE)
            if (isVideo) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()

        val out = mutableListOf<MediaItem>()
        resolver.query(
            uri, projection, null, null,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iWidth = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iHeight = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            // IS_FAVORITE 是老设备/个别 ROM 上可能缺失的列，取不到就当未收藏
            val iFav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            val iDur = if (isVideo) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                val taken = c.getLong(iTaken)
                val modified = c.getLong(iMod) * 1000
                out += MediaItem(
                    id = c.getLong(iId),
                    isVideo = isVideo,
                    dateMs = if (taken > 0) taken else modified,
                    bucket = c.getString(iBucket)?.takeIf { it.isNotBlank() } ?: "未分类",
                    sizeBytes = c.getLong(iSize),
                    durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                    width = c.getInt(iWidth),
                    height = c.getInt(iHeight),
                    name = c.getString(iName) ?: "",
                    isFavorite = iFav >= 0 && c.getInt(iFav) == 1,
                )
            }
        }
        return out
    }
}
