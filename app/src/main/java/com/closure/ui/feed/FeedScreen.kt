package com.closure.ui.feed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.closure.data.MediaItem
import com.closure.ui.theme.Brand
import kotlinx.coroutines.launch

private val Glass = Color(0x59262631)
private val GlassBorder = Color(0x24FFFFFF)
private val SnackbarGlass = Color(0xCC141419)

/** 跳转系统「所有文件访问」授权页（极速翻篇用） */
private fun openAllFilesAccessSettings(context: Context) {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(perApp) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
private val ScrimTop = Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000)))
private val ScrimBottom = Brush.verticalGradient(listOf(Color(0x00000000), Color(0xCC000000)))

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen() {
    val vm: FeedViewModel = viewModel()
    LaunchedEffect(Unit) { vm.load() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 最后一页固定为结束页
    val pagerState = rememberPagerState(pageCount = { vm.items.size + 1 })

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // 静音开关作用于共享播放器（视频与实况统一生效）
    LaunchedEffect(vm.muted) {
        player.volume = if (vm.muted) 0f else 1f
    }

    // 照片缩放中/长图展开时锁定翻页手势
    var mediaLocked by remember { mutableStateOf(false) }

    // 当前视频/实况的首帧是否已真正渲染进 PlayerView。
    // 落页瞬间新 attach 的 PlayerView，其 TextureView 在首帧渲染出来之前是纯黑的，
    // 直接摆上去会在静态垫底图上闪一帧黑（透明 shutter 只解决「上一视频残留」，管不了首帧前的黑）
    // ——先藏住，等首帧回调后交叉淡化进入，顺带把 JPEG↔视频的色域/取景硬切也柔化
    var videoFrameReady by remember { mutableStateOf(false) }

    // 实况内嵌视频播放失败 → 回退静态原图，绝不黑屏
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                videoFrameReady = true
            }
            override fun onPlayerError(error: PlaybackException) {
                val item = vm.items.getOrNull(pagerState.settledPage)
                if (item != null && !item.isVideo && vm.motionVideos.containsKey(item.id)) {
                    vm.markPlaybackFailed(item.id)
                    player.clearMediaItems()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var currentPageHasVideo by remember { mutableStateOf(false) }

    // 视频横屏全屏：旋转 + 沉浸式隐藏系统栏，隐藏全部覆盖层
    var fullscreen by remember { mutableStateOf(false) }
    val activity = context as? Activity
    fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (on) {
            // 竖屏（含正方形）视频全屏保持竖屏，只有真的宽>高才旋转
            val item = vm.items.getOrNull(pagerState.settledPage)
            val landscape = item == null || (item.width > 0 && item.width > item.height)
            activity?.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = fullscreen) { setFullscreen(false) }

    // 照片沉浸全屏：双击进入（隐藏全部覆盖层与系统栏），单击/双击/返回退出
    var immersive by remember { mutableStateOf(false) }
    fun setImmersive(on: Boolean) {
        if (immersive == on) return
        immersive = on
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (on) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = immersive) { setImmersive(false) }

    // 当前页的播放决策：视频播原文件，实况照片播提取出的内嵌视频
    fun playCurrent(page: Int) {
        videoFrameReady = false   // 换内容后旧首帧不再代表画面，等新首帧回调再亮出播放器
        val item = vm.items.getOrNull(page)
        val motionPath = item?.let { vm.motionVideos[it.id] }
        currentPageHasVideo = item?.isVideo == true || motionPath != null
        when {
            item == null -> { player.pause(); player.clearMediaItems() }
            item.isVideo -> {
                player.setMediaItem(ExoMediaItem.fromUri(item.uri))
                player.prepare(); player.playWhenReady = true
            }
            motionPath != null -> {
                player.setMediaItem(ExoMediaItem.fromUri(Uri.fromFile(File(motionPath))))
                player.prepare(); player.playWhenReady = true
            }
            else -> { player.pause(); player.clearMediaItems() }
        }
    }

    // 页面稳定后切换播放内容并懒检查实况。
    // 监听「页面 + 该页条目 id」：加载完成、切相册筛选、删除移位都可能让第 0 页
    // 换了内容而 settledPage 不变（snapshotFlow 去重后不发），所以必须带上条目 id。
    LaunchedEffect(pagerState, vm.loaded) {
        snapshotFlow {
            val page = pagerState.settledPage
            Pair(page, vm.items.getOrNull(page)?.id)
        }.collect { (page, itemId) ->
            mediaLocked = false
            val item = itemId?.let { id -> vm.items.firstOrNull { it.id == id } }
            if (fullscreen && item?.isVideo != true) setFullscreen(false)
            if (item != null) vm.ensureMotionChecked(item)
            playCurrent(page)
        }
    }
    // 换相册后回到顶部
    LaunchedEffect(vm.selectedBucket) {
        pagerState.scrollToPage(0)
    }
    // 实况视频提取完成时（页面可能还停在原地），补一次播放决策
    LaunchedEffect(vm.motionVideos.size) {
        if (vm.motionVideos.isNotEmpty()) playCurrent(pagerState.settledPage)
    }
    // 换一批后回到顶部
    LaunchedEffect(vm.shuffleTick) {
        if (vm.shuffleTick > 0) pagerState.scrollToPage(0)
    }

    // 退到后台暂停播放，回前台继续；回前台时刷新极速模式授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> {
                    if (currentPageHasVideo) player.play()
                    vm.refreshSpeedMode()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 批量删除：先弹自家底部确认弹窗（大按钮单手友好），确认后再走系统确认
    var showConfirmSheet by remember { mutableStateOf(false) }
    var preDeleteAnchorId by remember { mutableStateOf<Long?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val deleted = vm.onDeleteConfirmed()
            scope.launch {
                val target = preDeleteAnchorId
                    ?.let { id -> vm.items.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: pagerState.settledPage.coerceAtMost(vm.items.size - 1)
                pagerState.animateScrollToPage(target.coerceAtLeast(0))
                snackbarHostState.showSnackbar("已翻篇 $deleted 张 · 后悔药在系统相册回收站（保留 30 天）")
            }
        }
    }

    fun requestBatchDelete() {
        val uris = vm.pendingDeleteUris()
        if (uris.isEmpty()) return
        preDeleteAnchorId = vm.items.getOrNull(pagerState.settledPage)?.id
        try {
            val pi = android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("删除请求失败：${e.message}") }
        }
    }

    // 收藏：没有「所有文件访问」权限时系统要弹一次确认框，用户拒绝则回滚本地状态
    var pendingFavorite by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val favoriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            pendingFavorite?.let { (id, target) -> vm.setFavoriteState(id, !target) }
        }
        pendingFavorite = null
    }

    fun toggleFavorite(item: MediaItem) {
        pendingFavorite = item.id to !item.isFavorite
        vm.toggleFavorite(item) { sender: IntentSender ->
            favoriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    /** 分享当前媒体原文件（实况照片分享原图，内嵌动态随之带走） */
    fun shareItem(item: MediaItem) {
        val mime = runCatching { context.contentResolver.getType(item.uri) }.getOrNull()
            ?: if (item.isVideo) "video/*" else "image/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享给…"))
    }

    // 首次进入的操作提示
    var hintShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.loaded) {
        if (vm.loaded && !hintShown) {
            hintShown = true
            snackbarHostState.showSnackbar("上滑看下一张 · 点「翻篇」攒一批，一次告别")
        }
    }

    val safeDrawing = WindowInsets.safeDrawing
    val currentItem = vm.items.getOrNull(pagerState.currentPage)
    val currentIsLive = currentItem?.let { vm.liveIds.containsKey(it.id) } == true

    // 顶栏文件名：点击展开完整文件名，换页后收回
    var nameExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(currentItem?.id) { nameExpanded = false }

    Box(Modifier.fillMaxSize().background(Brand.Bg)) {
        if (!vm.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand.Coral)
            }
        } else {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !mediaLocked && !fullscreen,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = vm.items.getOrNull(page)
                if (item == null) {
                    EndPage(
                        hasContent = vm.items.isNotEmpty(),
                        title = when {
                            vm.onlyFavorites && vm.items.isEmpty() -> "还没有收藏"
                            vm.onlyFavorites -> "收藏的都翻完了"
                            vm.items.isEmpty() -> "这里已经清清爽爽"
                            else -> "这一册，翻完了"
                        },
                        subtitle = when {
                            vm.onlyFavorites && vm.items.isEmpty() -> "刷到想留的，点一下心形"
                            vm.onlyFavorites -> "已收藏 ${vm.favoritesCount} 张 · 系统相册里也能看到"
                            vm.deletedCount > 0 -> "本轮翻篇 ${vm.deletedCount} 张 · 后悔药在系统相册回收站（30 天）"
                            else -> "没有需要翻篇的内容，都是好回忆"
                        },
                        onRestart = { vm.reshuffle() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MediaPage(
                        item = item,
                        isActive = page == pagerState.settledPage,
                        player = player,
                        motionPath = vm.motionVideos[item.id],
                        playbackFailed = vm.playbackFailedIds.containsKey(item.id),
                        immersive = immersive,
                        onToggleImmersive = { setImmersive(!immersive) },
                        onLockChange = { mediaLocked = it },
                        fullscreen = fullscreen,
                        onEnterFullscreen = { setFullscreen(true) },
                        videoFrameReady = videoFrameReady,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 顶部 / 底部防遮罩，保证文字与按钮在亮图上可读。
            // 沉浸全屏、视频全屏、长图展开、缩放放大——任何「专注看图」的状态都隐去，别压暗内容
            val showScrim = !immersive && !fullscreen && !mediaLocked
            AnimatedVisibility(
                visible = showScrim,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(), exit = fadeOut()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 只盖到按钮行附近：视频的进度条与全屏按钮画在「页内」，在这层阴影之下，
                        // 铺太高会把它们一起压灰，跟下方纯白文字一比就显得脏
                        .height(140.dp)
                        .background(ScrimBottom)
                )
            }
            AnimatedVisibility(
                visible = showScrim,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(), exit = fadeOut()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 只盖住按钮行：视频进度条与全屏按钮画在页内（在这层阴影之下），
                        // 铺太高就会把它们连同白色一起压灰，跟下方纯白文字对比显得很脏
                        .height(120.dp)
                        .background(ScrimBottom)
                )
            }

            // 顶部：文件名（点击展开）+ 静音 + 换一批 + 相册筛选（放大/展开/沉浸时隐藏）
            AnimatedVisibility(
                visible = !mediaLocked && !fullscreen && !immersive,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(), exit = fadeOut()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                currentItem?.let { item ->
                    val liveSuffix = if (vm.liveIds.containsKey(item.id)) " · 实况" else ""
                    // 占满右侧按钮之前的全部宽度：文件名够长就能显示更多字符，短名字则贴合内容
                    Box(Modifier.weight(1f).padding(end = 12.dp)) {
                        FileNamePill(
                            text = item.fileName() + liveSuffix,
                            expanded = nameExpanded,
                            onToggle = { nameExpanded = !nameExpanded }
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        GlassCircle(onClick = { vm.toggleMuted() }) {
                            Icon(
                                if (vm.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                contentDescription = if (vm.muted) "取消静音" else "静音",
                                tint = Color.White, modifier = Modifier.padding(10.dp).size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        GlassCircle(onClick = { vm.reshuffle() }) {
                            Icon(
                                Icons.Rounded.Shuffle, contentDescription = "换一批",
                                tint = Color.White, modifier = Modifier.padding(10.dp).size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        GlassCircle(onClick = { /* 由内部 DropdownMenu 展开 */ }) {
                            BucketMenu(
                                buckets = vm.buckets,
                                counts = vm.bucketCounts,
                                totalCount = vm.totalCount,
                                favoritesCount = vm.favoritesCount,
                                selected = vm.selectedBucket,
                                onlyFavorites = vm.onlyFavorites,
                                onSelect = { vm.selectBucket(it) },
                                onSelectFavorites = { vm.selectFavorites() }
                            )
                        }
                    }
                    AnimatedVisibility(visible = currentIsLive, enter = fadeIn(), exit = fadeOut()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC141419),
                            modifier = Modifier.padding(top = 10.dp).border(1.dp, GlassBorder, RoundedCornerShape(50))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(6.dp).background(Color(0xFFFFD166), CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text("实况", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            }

            // 底部：左下时间/信息 + 已攒 chip + 分享 + 全屏 + 「翻篇」渐变主按钮
            AnimatedVisibility(
                visible = !mediaLocked && !fullscreen && !immersive,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(), exit = fadeOut()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左下：时间（重要信息）+ 相册·大小；弹性区绝不挤压右侧按钮
                Column(Modifier.weight(1f)) {
                    Text(
                        currentItem?.timeLine() ?: "",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        currentItem?.metaLine() ?: "",
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (currentItem != null) {
                    val fav = currentItem!!.isFavorite
                    GlassCircle(
                        size = 48.dp,
                        onClick = { currentItem?.let { toggleFavorite(it) } }
                    ) {
                        Icon(
                            if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (fav) "取消收藏" else "收藏",
                            tint = if (fav) Brand.Coral else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                if (currentItem != null) {
                    GlassCircle(
                        size = 48.dp,
                        onClick = { currentItem?.let { shareItem(it) } }
                    ) {
                        Icon(
                            Icons.Rounded.Share, contentDescription = "分享",
                            tint = Color.White, modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                FlipButton(
                    onClick = {
                        val page = pagerState.settledPage
                        val item = vm.items.getOrNull(page) ?: return@FlipButton
                        vm.markForDelete(item)
                        scope.launch {
                            pagerState.animateScrollToPage((page + 1).coerceAtMost(vm.items.size))
                        }
                    }
                )
            }
            }

            // 视频的进度条与全屏按钮画在 MediaPage 页内（放在 Pager 外面会抢走纵向翻页手势），
            // 这里只取「当前是视频」用于给浮动控件让位
            val playingItem = vm.items.getOrNull(pagerState.settledPage)
            val videoNow = playingItem?.isVideo == true

            // 攒下的待翻篇计数：圆形徽章浮在底部按钮上方，点开确认弹窗。
            // 与其他覆盖层同进退——沉浸/长图展开/缩放/视频全屏时都不出现。
            // 视频页要再高一档，让开页内居中的全屏按钮
            val badgeBottom by animateDpAsState(if (videoNow) 154.dp else 84.dp, label = "badgeBottom")
            val markedVisible = vm.marked.isNotEmpty()
            AnimatedVisibility(
                visible = markedVisible && !mediaLocked && !fullscreen && !immersive,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = badgeBottom),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xF2FFFFFF), CircleShape)
                        .border(1.dp, Color(0x1F000000), CircleShape)
                        .clickable { showConfirmSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${vm.marked.size}",
                        color = Color(0xFF17171C),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            // 全屏时的退出按钮
            if (fullscreen) {
                IconButton(
                    onClick = { setFullscreen(false) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                        .padding(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close, contentDescription = "退出全屏",
                        tint = Color.White,
                        modifier = Modifier
                            .background(Color(0x66000000), CircleShape)
                            .padding(8.dp)
                    )
                }
            }

            // 提示条：半透明暗色浮层 + 细边框，与「实况」角标、模式胶囊同一套视觉
            val snackBottom by animateDpAsState(if (videoNow) 202.dp else 148.dp, label = "snackBottom")
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = 16.dp, end = 16.dp, bottom = snackBottom)   // 让开下方的进度条与浮层控件
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SnackbarGlass,
                    contentColor = Color(0xFFF2F2F2),
                    actionColor = Brand.Coral,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.border(1.dp, GlassBorder, RoundedCornerShape(50))
                )
            }

            // 批量删除确认弹窗：屏幕下方弹出，大号居中按钮方便单手操作
            if (showConfirmSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showConfirmSheet = false },
                    containerColor = Color(0xFF1C1D24)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "一起翻篇",
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "确认翻篇 ${vm.marked.size} 张 · 回收站保留 30 天，随时可反悔",
                            color = Color(0xFF9AA0A6), fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                        if (vm.speedMode) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "极速模式 · 删除不再弹系统窗",
                                color = Color(0xFF7BD88F), fontSize = 11.sp
                            )
                        } else {
                            TextButton(onClick = { openAllFilesAccessSettings(context) }) {
                                Icon(
                                    Icons.Rounded.Bolt, contentDescription = null,
                                    tint = Brand.Coral, modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "开启极速翻篇 · 删除免系统弹窗",
                                    color = Color(0xFFB9BEC7), fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(vm.marked, key = { it.id }) { m ->
                                Box {
                                    AsyncImage(
                                        model = m.uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                    // 右上角 X：点一下把这张从待翻篇队列里拿掉；
                                    // 全拿掉就收起弹窗（没有可确认的内容了）
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(20.dp)
                                            .background(Color(0xCC141419), CircleShape)
                                            .border(1.dp, GlassBorder, CircleShape)
                                            .clickable {
                                                vm.unmark(m.id)
                                                if (vm.marked.isEmpty()) showConfirmSheet = false
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close, contentDescription = "取消翻篇这张",
                                            tint = Color.White, modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "confirmScale")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .background(brush = Brand.buttonGradient, RoundedCornerShape(28.dp))
                                .clickable(interactionSource = interaction, indication = null, onClick = {
                                    showConfirmSheet = false
                                    val items = vm.marked.toList()
                                    if (items.isEmpty()) return@clickable
                                    preDeleteAnchorId = vm.items.getOrNull(pagerState.settledPage)?.id
                                    if (vm.speedMode) {
                                        // 极速模式：已授予所有文件访问，直接删除不弹系统窗
                                        vm.deleteDirectly(items) { moved ->
                                            scope.launch {
                                                vm.onDeleteConfirmed()
                                                val target = preDeleteAnchorId
                                                    ?.let { id -> vm.items.indexOfFirst { it.id == id } }
                                                    ?.takeIf { it >= 0 }
                                                    ?: pagerState.settledPage.coerceAtMost(vm.items.size - 1)
                                                pagerState.animateScrollToPage(target.coerceAtLeast(0))
                                                snackbarHostState.showSnackbar(
                                                    "已翻篇 $moved 张 · 即刻生效"
                                                )
                                            }
                                        }
                                    } else {
                                        requestBatchDelete()
                                    }
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "确认翻篇 ${vm.marked.size} 张",
                                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { showConfirmSheet = false }) {
                            Text("再想想", color = Color(0xFF9AA0A6))
                        }
                    }
                }
            }
        }
    }
}

/** 品牌主按钮：渐变胶囊 + 按压缩放 */
@Composable
private fun FlipButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "flipScale")
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(brush = Brand.buttonGradient, shape = RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Style, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "翻篇",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun GlassCircle(
    size: androidx.compose.ui.unit.Dp = 40.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Glass, CircleShape)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 顶栏文件名：单行显示（放不下就点开看完整），高度与右侧圆形按钮一致，左右齐平 */
@Composable
private fun FileNamePill(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0x66000000),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
        modifier = modifier
            .heightIn(min = 40.dp)
            .clickable(onClick = onToggle)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = Color(0xF2FFFFFF),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = if (expanded) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun BucketMenu(
    buckets: List<String>,
    counts: Map<String, Int>,
    totalCount: Int,
    favoritesCount: Int,
    selected: String?,
    onlyFavorites: Boolean,
    onSelect: (String?) -> Unit,
    onSelectFavorites: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // 供外层 GlassCircle 点击展开：整个圆形区域都算按钮
        Box(
            Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        Icon(
            Icons.Rounded.PhotoLibrary, contentDescription = "选择相册",
            tint = Color.White, modifier = Modifier.padding(10.dp).size(20.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部照片 · $totalCount") },
                leadingIcon = {
                    if (selected == null && !onlyFavorites) Icon(Icons.Rounded.Check, null) else null
                },
                onClick = { onSelect(null); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("我的收藏 · $favoritesCount") },
                leadingIcon = {
                    Icon(
                        if (onlyFavorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        null,
                        tint = if (onlyFavorites) Brand.Coral else Color(0xFF9AA0A6)
                    )
                },
                onClick = { onSelectFavorites(); expanded = false }
            )
            buckets.forEach { bucket ->
                DropdownMenuItem(
                    text = { Text("$bucket · ${counts[bucket] ?: 0}") },
                    leadingIcon = {
                        if (selected == bucket) Icon(Icons.Rounded.Check, null) else null
                    },
                    onClick = { onSelect(bucket); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun EndPage(
    hasContent: Boolean,
    title: String,
    subtitle: String,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎉", fontSize = 64.sp)
        Spacer(Modifier.height(20.dp))
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            subtitle,
            color = Color(0xFF9AA0A6), fontSize = 14.sp, textAlign = TextAlign.Center
        )
        if (hasContent) {
            Spacer(Modifier.height(28.dp))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "restartScale")
            Text(
                "换一批，再刷一遍",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .background(brush = Brand.buttonGradient, RoundedCornerShape(50))
                    .clickable(interactionSource = interaction, indication = null, onClick = onRestart)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}
