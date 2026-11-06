package com.closure.ui.feed

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
import com.closure.data.MediaItem
import com.closure.data.MediaRepository
import com.closure.media.MotionPhotoDetector
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

    /**
     * 仅视频模式：刷流里只留视频；取消后恢复成「照片 + 视频」混刷。
     * 与相册 / 收藏筛选叠加生效（相册筛选里只看视频）。
     */
    var onlyVideos by mutableStateOf(false)
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

    /**
     * 刷流顺序：true = 随机（每次启动都是它），false = 按拍摄时间从近到远。
     * 不落盘：这是个「本次刷流」的开关，下次打开 App 一律从随机开始——
     * 免得哪天顺手关掉，过几天再打开发现相册变成了按时间排，还以为默认变了。
     */
    var randomOrder by mutableStateOf(true)
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
            // 仓库给的是按时间倒序；随机模式才打乱
            master = if (randomOrder) library.items.shuffled() else library.items
            // 相册列表只在这里定一次：顺序稳定，切模式/换一批时菜单不会跳来跳去
            buckets = library.bucketCounts.keys.toList()
            applyFilter()
            loaded = true
            loading = false
        }
    }

    /** 随机顺序开关：开 = 打乱，关 = 按时间从近到远（只影响本次，重开 App 回到随机） */
    fun toggleRandomOrder() {
        randomOrder = !randomOrder
        master = if (randomOrder) master.shuffled() else master.sortedByDescending { it.dateMs }
        applyFilter()
        shuffleTick++   // 顺序整个变了，回到顶部
    }

    /** 换一批：随机模式下重新洗牌；顺序模式下什么都没变，只回到顶部 */
    fun reshuffle() {
        if (randomOrder) master = master.shuffled()
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

    /** 仅视频模式开关：开 → 只刷视频；关 → 照片 + 视频回到混刷 */
    fun toggleVideoMode() {
        onlyVideos = !onlyVideos
        // 当前相册里一张视频都没有时，切到仅视频会是空刷流 —— 退回全部相册
        if (onlyVideos && selectedBucket != null &&
            master.none { it.isVideo && it.bucket == selectedBucket }
        ) {
            selectedBucket = null
        }
        applyFilter()
    }

    /** 当前模式下可供刷的池子：仅视频模式只留视频，其余照旧 */
    private fun pool(): List<MediaItem> = if (onlyVideos) master.filter { it.isVideo } else master

    private fun applyFilter() {
        val pool = pool()
        val filtered = when {
            onlyFavorites -> pool.filter { it.isFavorite }
            selectedBucket != null -> pool.filter { it.bucket == selectedBucket }
            else -> pool
        }
        items.clear()
        items.addAll(filtered)
        refreshCounts(pool)
    }

    /** 计数跟着模式走：仅视频时菜单里的数字就是实际会刷到的条数 */
    private fun refreshCounts(pool: List<MediaItem>) {
        totalCount = pool.size
        favoritesCount = pool.count { it.isFavorite }
        bucketCounts = pool.groupingBy { it.bucket }.eachCount()
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
        favoritesCount = pool().count { it.isFavorite }
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
