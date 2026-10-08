package com.skylerlin.closure.ui.feed

import android.app.Application
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skylerlin.closure.data.MediaItem
import com.skylerlin.closure.data.MediaRepository
import com.skylerlin.closure.media.MotionPhotoDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val KEY_MUTED = "muted"

class FeedViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val resolver = application.contentResolver

    /** 当前展示（按相册筛选后）的媒体列表，随机顺序 */
    val items = mutableStateListOf<MediaItem>()

    /** 已标记待删除 */
    val marked = mutableStateListOf<MediaItem>()

    /** 实况照片：id -> 提取出的内嵌视频路径 */
    val motionVideos = mutableStateMapOf<Long, String>()

    /** 实况照片 id 集合（角标用；可能视频提取中/失败） */
    val liveIds = mutableStateMapOf<Long, Boolean>()

    /** 实况内嵌视频播放失败的项：回退显示静态原图，避免黑屏 */
    val playbackFailedIds = mutableStateMapOf<Long, Boolean>()

    private val motionChecked = mutableSetOf<Long>()

    var buckets by mutableStateOf<List<String>>(emptyList())
        private set
    var bucketCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var selectedBucket by mutableStateOf<String?>(null)
        private set

    /** 只看收藏（与相册筛选互斥） */
    var onlyFavorites by mutableStateOf(false)
        private set

    /** 收藏总数（筛选菜单里显示） */
    var favoritesCount by mutableIntStateOf(0)
        private set
    var totalCount by mutableIntStateOf(0)
        private set
    var deletedCount by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** 每次自增代表一次重新洗牌，UI 据此回到顶部 */
    var shuffleTick by mutableIntStateOf(0)
        private set

    /** 视频与实况的静音开关（持久化，下次启动记住偏好） */
    var muted by mutableStateOf(prefs.getBoolean(KEY_MUTED, false))
        private set

    /** 极速模式：已授予「所有文件访问」，确认翻篇直接删除、不弹系统窗 */
    var speedMode by mutableStateOf(Environment.isExternalStorageManager())
        private set

    fun refreshSpeedMode() {
        speedMode = Environment.isExternalStorageManager()
    }

    /** 极速删除：直接走 MediaStore 删除（已授予所有文件访问时不弹系统窗） */
    fun deleteDirectly(items: List<MediaItem>, onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var n = 0
            items.forEach { item ->
                runCatching {
                    if (resolver.delete(item.uri, null, null) > 0) n++
                }
            }
            withContext(Dispatchers.Main) { onDone(n) }
        }
    }

    private var master: List<MediaItem> = emptyList()
    private var loading = false

    fun load() {
        if (loaded || loading) return
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
            val library = repository.loadLibrary()
            master = library.items.shuffled()
            bucketCounts = library.bucketCounts
            buckets = library.bucketCounts.keys.toList()
            totalCount = library.items.size
            favoritesCount = master.count { it.isFavorite }
            applyFilter()
            loaded = true
            loading = false
        }
    }

    /** 换一批：重新洗牌 */
    fun reshuffle() {
        master = master.shuffled()
        applyFilter()
        shuffleTick++
    }

    fun toggleMuted() {
        muted = !muted
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
    }

    fun selectBucket(bucket: String?) {
        if (selectedBucket == bucket && !onlyFavorites) return
        selectedBucket = bucket
        onlyFavorites = false
        applyFilter()
    }

    /** 只看收藏 */
    fun selectFavorites() {
        if (onlyFavorites) return
        onlyFavorites = true
        selectedBucket = null
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = when {
            onlyFavorites -> master.filter { it.isFavorite }
            selectedBucket != null -> master.filter { it.bucket == selectedBucket }
            else -> master
        }
        items.clear()
        items.addAll(filtered)
    }

    /**
     * 收藏 / 取消收藏：先乐观更新界面（点心形立刻有反馈），再尝试直接写系统收藏字段。
     * 没有「所有文件访问」权限时，把 PendingIntent 交给 UI 弹系统确认框。
     */
    fun toggleFavorite(item: MediaItem, onNeedConfirm: (android.content.IntentSender) -> Unit) {
        val target = !item.isFavorite
        setFavoriteState(item.id, target)
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.setFavorite(item.uri, target)) return@launch
            val request = runCatching {
                MediaStore.createFavoriteRequest(resolver, listOf(item.uri), target)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (request != null) onNeedConfirm(request.intentSender)
                else setFavoriteState(item.id, !target)   // 两条路都不通，回滚
            }
        }
    }

    /** 只改本地状态：乐观更新与回滚共用 */
    fun setFavoriteState(id: Long, favorite: Boolean) {
        val masterIdx = master.indexOfFirst { it.id == id }
        if (masterIdx >= 0) {
            master = master.toMutableList().also {
                it[masterIdx] = it[masterIdx].copy(isFavorite = favorite)
            }
        }
        val itemIdx = items.indexOfFirst { it.id == id }
        if (itemIdx >= 0) items[itemIdx] = items[itemIdx].copy(isFavorite = favorite)
        favoritesCount = master.count { it.isFavorite }
    }

    fun markForDelete(item: MediaItem) {
        if (marked.none { it.id == item.id }) marked.add(item)
    }

    /** 确认弹窗里反悔：把这张从待翻篇队列里拿掉（回到刷流继续决定） */
    fun unmark(id: Long) {
        marked.removeAll { it.id == id }
    }

    fun pendingDeleteUris(): List<Uri> = marked.map { it.uri }

    /** 系统确认弹窗点允许后调用，做状态清理 */
    fun onDeleteConfirmed(): Int {
        val n = marked.size
        if (n == 0) return 0
        val removed = marked.toList()
        val removedIds = removed.map { it.id }.toHashSet()
        master = master.filter { it.id !in removedIds }
        bucketCounts = bucketCounts.mapValues { (bucket, count) ->
            count - removed.count { it.bucket == bucket }
        }
        totalCount -= n
        deletedCount += n
        marked.removeAll { it.id in removedIds }
        removed.forEach { item ->
            motionVideos.remove(item.id)?.let { path -> File(path).delete() }
            liveIds.remove(item.id)
            playbackFailedIds.remove(item.id)
        }
        applyFilter()
        return n
    }

    fun markPlaybackFailed(id: Long) {
        playbackFailedIds[id] = true
    }

    /** 检查当前项是否实况照片（懒检查：每张只查一次），是则提取内嵌视频供循环播放。 */
    fun ensureMotionChecked(item: MediaItem) {
        if (item.isVideo || motionChecked.contains(item.id)) return
        motionChecked.add(item.id)
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val info = MotionPhotoDetector.detect(app.contentResolver, item) ?: return@launch
            liveIds[item.id] = true
            MotionPhotoDetector.extract(app, app.contentResolver, item, info)?.let { file ->
                motionVideos[item.id] = file.absolutePath
            }
        }
    }
}
