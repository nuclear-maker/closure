package com.skylerlin.closure.ui.feed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.skylerlin.closure.R
import com.skylerlin.closure.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f

@Composable
fun MediaPage(
    item: MediaItem,
    isActive: Boolean,
    player: ExoPlayer,
    motionPath: String?,
    playbackFailed: Boolean,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    onLockChange: (Boolean) -> Unit,
    fullscreen: Boolean = false,
    onEnterFullscreen: () -> Unit = {},
    videoFrameReady: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val motionPlayable = motionPath != null && !playbackFailed
    val playNow = isActive && (item.isVideo || motionPlayable)
    val underlayInteractive = !item.isVideo && !(isActive && motionPlayable)

    // PlayerView 在首帧渲染前是黑 TextureView：藏住透出垫底静态图，首帧回调后交叉淡入
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (videoFrameReady) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "videoSurface"
    )

    // 分层渲染：底层是静态图（视频用 VideoFrameDecoder 取首帧、实况用原图 JPEG），
    // 播放器 shutter 已设透明，首帧渲染完成前透出底层图——消除「静态图→黑→视频」的闪烁。
    Box(modifier) {
        if (underlayInteractive) {
            InteractiveImage(
                item = item,
                isActive = isActive,
                immersive = immersive,
                onToggleImmersive = onToggleImmersive,
                onLockChange = onLockChange,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (playNow) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = surfaceAlpha },
                factory = { context ->
                    LayoutInflater.from(context)
                        .inflate(R.layout.view_video_player, null, false) as PlayerView
                },
                update = { view -> if (view.player !== player) view.player = player },
                // 离开组合时显式摘掉 player：避免旧 surface 的销毁事件与
                // 新页面的 attach 竞争，把新画面短暂清黑
                onRelease = { view -> view.player = null }
            )
        }
        // 视频覆盖层画在「页内」而不是放在 Pager 外面：Pager 外面的浮层是兄弟节点，
        // 会抢在 Pager 之前命中触摸，纵向滑动翻页收不到事件（实测滑动直接失效）。
        // 只画在当前稳定页上，相邻预加载页不重复显示
        if (item.isVideo && isActive) {
            VideoSeekBar(
                player = player,
                item = item,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                        )
                    )
                    .padding(start = 24.dp, end = 24.dp, bottom = 66.dp)
            )
            if (!fullscreen) {
                GlassCircle(
                    size = 48.dp,
                    onClick = onEnterFullscreen,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(bottom = 98.dp)
                ) {
                    Icon(
                        Icons.Rounded.Fullscreen, contentDescription = "全屏观看",
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * 照片查看：双指缩放（放大后有「收起」退出并解锁翻页）；双击进入沉浸全屏（再双击或单击退出，
 * 不做放大——放大交给双指）；长图可展开为全宽滚动——低清全图垫底 + 视口区域高清解码
 * （BitmapRegionDecoder，只解码屏幕可见段，任意长度不爆 Bitmap 上限导致黑屏）+ 可拖动滚动条。
 */
@Composable
private fun InteractiveImage(
    item: MediaItem,
    isActive: Boolean,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    onLockChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLong = item.isLongImage()
    var expanded by remember(item.id) { mutableStateOf(false) }
    var zoomed by remember(item.id) { mutableStateOf(false) }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var container by remember(item.id) { mutableStateOf(IntSize(1, 1)) }
    // 展开模式胶囊：点击后计时变淡，不挡阅读；再点恢复
    var pillDim by remember(item.id) { mutableStateOf(false) }
    var pillTick by remember(item.id) { mutableIntStateOf(0) }
    // pointerInput 的闭包不随重组更新，必须用最新值转发，否则手势里读到的是首帧状态
    val toggleImmersive by rememberUpdatedState(onToggleImmersive)
    val immersiveNow by rememberUpdatedState(immersive)

    // 放大/展开期间锁定翻页手势
    LaunchedEffect(isActive, expanded, zoomed) {
        if (isActive) onLockChange(expanded || zoomed)
    }

    LaunchedEffect(pillTick) {
        pillDim = false
        if (pillTick > 0) {
            kotlinx.coroutines.delay(2200)
            pillDim = true
        }
    }

    Box(modifier) {
        if (isLong && expanded) {
            ExpandedLongImage(
                item = item,
                // 展开态双击收起（进入展开则靠未展开时的手势）
                onDoubleTap = {
                    pillTick++
                    expanded = false
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { container = it }
                    .pointerInput(item.id) {
                        detectTapGestures(
                            // 沉浸中单击退出；进入只认双击，避免滑动时误触
                            onTap = { if (immersiveNow) toggleImmersive() },
                            onDoubleTap = {
                                when {
                                    // 长图：双击即展开/收起（所以不再需要「展开」按钮）
                                    isLong && !zoomed -> {
                                        pillTick++
                                        expanded = !expanded
                                    }
                                    // 已放大：双击复位（缩放只归双指管）
                                    scale > 1.01f -> {
                                        scale = 1f
                                        offset = Offset.Zero
                                        zoomed = false
                                        if (isActive) onLockChange(false)
                                    }
                                    else -> toggleImmersive()
                                }
                            }
                        )
                    }
                    .pointerInput(item.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val multiTouch = event.changes.size > 1
                                if (multiTouch || scale > 1.01f) {
                                    val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                    val newOffset = if (newScale <= 1.01f) {
                                        Offset.Zero
                                    } else {
                                        val next = offset + event.calculatePan()
                                        val maxX = (newScale - 1f) * container.width / 2f
                                        val maxY = (newScale - 1f) * container.height / 2f
                                        Offset(
                                            next.x.coerceIn(-maxX, maxX),
                                            next.y.coerceIn(-maxY, maxY)
                                        )
                                    }
                                    scale = newScale
                                    offset = newOffset
                                    val z = scale > 1.01f
                                    if (z != zoomed) {
                                        zoomed = z
                                        if (isActive) onLockChange(z)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
        // 展开/缩放态的「收起」放回底部居中——此时底部按钮行与计数徽章都不显示
        // （mediaLocked 为真），不会互相压住，也比右下角更好按
        val pillBottom = if (immersive) 40.dp else 110.dp
        if (isLong && expanded && !zoomed) {
            ModePill(
                text = "收起",
                dimmed = pillDim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = pillBottom),
                onClick = {
                    pillTick++
                    expanded = false
                }
            )
        }
        if (zoomed) {
            ModePill(
                text = "收起",
                dimmed = pillDim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = pillBottom),
                onClick = {
                    pillTick++
                    scale = 1f
                    offset = Offset.Zero
                    zoomed = false
                    if (isActive) onLockChange(false)
                }
            )
        }
    }
}

/**
 * 长图展开：低清全图垫底（可滚动画廓）+ 视口区域高清解码覆盖层，
 * 右侧滚动条可拖动。整图不参与全尺寸解码，任意长度不会黑屏。
 */
@Composable
private fun ExpandedLongImage(
    item: MediaItem,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val context = LocalContext.current
    // MediaStore 的 WIDTH/HEIGHT 可能为 0 或过期（导致滚动范围为 0、无法触摸滚动），
    // 展开时以文件真实解码尺寸为准
    var realSize by remember(item.id) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(item.id) {
        realSize = decodeBounds(context, item.uri)
    }
    BoxWithConstraints(
        modifier.pointerInput(item.id) {
            detectTapGestures(onDoubleTap = { onDoubleTap() })
        }
    ) {
        val viewportWpx = with(density) { maxWidth.toPx() }.roundToInt()
        val viewportHpx = with(density) { maxHeight.toPx() }.roundToInt()
        val img = realSize
        val targetHeight = if (img != null && img.width > 0) {
            maxWidth * (img.height.toFloat() / img.width)
        } else Dp.Unspecified
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
        ) {
            // 低清垫底：整图缩略（文字略糊但可滚动浏览全貌）
            SubsampledStillImage(
                item = item,
                maxDimension = 2048,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (targetHeight != Dp.Unspecified) Modifier.height(targetHeight)
                        else Modifier.fillMaxHeight()
                    )
            )
            // 高清瓦片层：按内容坐标分块解码，滚动中已解码区域保持清晰
            if (targetHeight != Dp.Unspecified && img != null) {
                HiResTiles(
                    item = item,
                    scrollState = scroll,
                    imgW = img.width,
                    imgH = img.height,
                    contentHeightPx = with(density) { targetHeight.toPx() }.roundToInt(),
                    viewportWpx = viewportWpx,
                    viewportHpx = viewportHpx,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(targetHeight)
                )
            }
        }
        if (scroll.maxValue > 0 && targetHeight != Dp.Unspecified) {
            Scrollbar(
                scroll = scroll,
                viewportHeight = maxHeight,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(32.dp)
            )
        }
    }
}

/** 只读图片头部的真实像素尺寸（不整图解码），失败返回 null */
private suspend fun decodeBounds(
    context: android.content.Context,
    uri: android.net.Uri,
): IntSize? = withContext(Dispatchers.IO) {
    runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) IntSize(opts.outWidth, opts.outHeight) else null
    }.getOrNull()
}

/** 对长图降采样解码到 maxDimension 以内，返回 ImageBitmap（失败返回 null） */
private suspend fun decodeDownsampled(
    context: android.content.Context,
    uri: android.net.Uri,
    maxDimension: Int,
): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        // 注意：inJustDecodeBounds 时 decodeStream 返回值恒为 null（只回填 bounds），不能用它判空
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }?.asImageBitmap()
    }.getOrNull()
}

/**
 * 长图高清瓦片层：按「内容坐标」锚定的分块解码。每片一屏高、放在它在内容里的
 * 绝对位置上，随内容一起滚动——永不与垫底图错位，所以滚动进行中也可以照常
 * 补解码：已解码区域保持锐利，只有跨进还没解到的边界才短暂露出糊的垫底图
 * （快滑甩动时任何实现都只能糊，与系统相册一致）。视口外远处的瓦片及时释放控内存。
 */
@Composable
private fun HiResTiles(
    item: MediaItem,
    scrollState: androidx.compose.foundation.ScrollState,
    imgW: Int,
    imgH: Int,
    contentHeightPx: Int,
    viewportWpx: Int,
    viewportHpx: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var tiles by remember(item.id) { mutableStateOf<Map<Int, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }

    LaunchedEffect(item.id, imgW, imgH, contentHeightPx) {
        if (imgW <= 0 || imgH <= 0 || contentHeightPx <= 0 || viewportWpx <= 0 || viewportHpx <= 0) {
            return@LaunchedEffect
        }
        snapshotFlow { scrollState.value }
            .map { scrollPx ->
                // 需要的瓦片：可视范围 ± 半片
                val first = ((scrollPx - viewportHpx / 2).coerceAtLeast(0) / viewportHpx)
                val last = ((scrollPx + viewportHpx + viewportHpx / 2)
                    .coerceAtMost(contentHeightPx - 1) / viewportHpx)
                first..last
            }
            .distinctUntilChanged()
            .collectLatest { needed ->
                tiles = tiles.filterKeys { it in needed.first - 1..needed.last + 1 }
                val mid = (needed.first + needed.last) / 2
                for (idx in needed.filter { it !in tiles }.sortedBy { abs(it - mid) }) {
                    val bmp = decodeRegionRows(
                        context, item.uri, imgW, imgH,
                        rowStartPx = idx * viewportHpx,
                        rowEndPx = ((idx + 1) * viewportHpx).coerceAtMost(contentHeightPx),
                        viewportWpx = viewportWpx
                    )
                    if (bmp != null) tiles = tiles + (idx to bmp)
                }
            }
    }

    Box(modifier) {
        tiles.forEach { (idx, bmp) ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .offset { IntOffset(0, idx * viewportHpx) }
                    .fillMaxWidth()
            )
        }
    }
}

/** 解码原图指定内容行段（显示像素坐标），按 FillWidth 显示正好 1:1 落回该行段 */
private suspend fun decodeRegionRows(
    context: android.content.Context,
    uri: android.net.Uri,
    imgW: Int,
    imgH: Int,
    rowStartPx: Int,
    rowEndPx: Int,
    viewportWpx: Int,
): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (imgW <= 0 || imgH <= 0 || viewportWpx <= 0 || rowEndPx <= rowStartPx) return@runCatching null
        val scale = viewportWpx.toFloat() / imgW   // 显示↔原图换算
        @Suppress("DEPRECATION")
        val decoder = context.contentResolver.openInputStream(uri)?.let {
            BitmapRegionDecoder.newInstance(it, false)
        } ?: return@runCatching null
        val y0 = (rowStartPx / scale).roundToInt().coerceIn(0, imgH - 1)
        val y1 = (rowEndPx / scale).roundToInt().coerceIn(y0 + 1, imgH)
        val sample = max(1, (imgW / (viewportWpx * 1.5f)).roundToInt())
        val bitmap = decoder.decodeRegion(
            Rect(0, y0, imgW, y1),
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565   // 瓦片不需要透明，内存减半
            }
        )
        decoder.recycle()
        bitmap.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun SubsampledStillImage(
    item: MediaItem,
    maxDimension: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(item.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(item.id) {
        bitmap = decodeDownsampled(context, item.uri, maxDimension)
    }
    bitmap?.let { bm ->
        Image(
            bitmap = bm,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier
        )
    }
}

@Composable
private fun Scrollbar(scroll: androidx.compose.foundation.ScrollState, viewportHeight: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewportPx = with(density) { viewportHeight.toPx() }
        val trackHeightPx = viewportPx * 0.55f
        val contentRange = scroll.maxValue.toFloat()
        if (contentRange <= 0f) return@BoxWithConstraints
        val thumbHeightPx = maxOf(
            trackHeightPx * (viewportPx / (viewportPx + contentRange)),
            with(density) { 40.dp.toPx() }
        )
        val thumbRangePx = trackHeightPx - thumbHeightPx
        val thumbY = (scroll.value / contentRange) * thumbRangePx

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(28.dp)
                .height(with(density) { trackHeightPx.toDp() }),
            // 滑块靠 offset(thumbY) 定位，thumbY=0 必须落在轨道顶部：
            // 用 Center 会先把它摆到轨道正中（起始位置看起来就在中间），
            // 滚到底还会向下溢出轨道半个滑块高度
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(5.dp)
                    .background(Color(0x30FFFFFF), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .width(5.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .background(Color(0xCCFFFFFF), RoundedCornerShape(3.dp))
                    .pointerInput(contentRange) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            if (thumbRangePx > 0f) {
                                scroll.dispatchRawDelta((dragAmount / thumbRangePx) * contentRange)
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun ModePill(text: String, dimmed: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "pillScale")
    val alpha by animateFloatAsState(if (dimmed) 0.28f else 1f, label = "pillDim")
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .background(Color(0xCC141419), RoundedCornerShape(50))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

/**
 * 视频进度条：点按定位、横向拖动连续定位。纵向（或斜向）滑动一律让给翻页——
 * 只有横向位移越过触摸阈值才开始消费手势，所以从进度条上起手也能正常上下翻页。
 * 拖动期间暂停播放并在上方露出时间气泡，松手定位后恢复原播放状态。
 * 手势回调里的时长走 rememberUpdatedState：pointerInput 闭包不随重组更新，
 * 直接捕获会一直读到首帧的 0，拉不动。
 */
@Composable
private fun VideoSeekBar(player: ExoPlayer, item: MediaItem, modifier: Modifier = Modifier) {
    var durationMs by remember(item.id) { mutableLongStateOf(item.durationMs) }
    var positionMs by remember(item.id) { mutableLongStateOf(0L) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    var dragFraction by remember(item.id) { mutableFloatStateOf(0f) }
    var trackWidth by remember { mutableIntStateOf(0) }
    val durationNow by rememberUpdatedState(durationMs)

    // 进度轮询：拖动时不读播放器位置，否则手指会被回读值拽回去
    LaunchedEffect(item.id, dragging) {
        while (!dragging) {
            player.duration.takeIf { it > 0L }?.let { durationMs = it }
            positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(120)
        }
    }

    val fraction = when {
        durationMs <= 0L -> 0f
        dragging -> dragFraction
        else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }
    val thumbScale by animateFloatAsState(if (dragging) 1.5f else 1f, label = "seekThumb")
    val density = LocalDensity.current
    val thumbPx = with(density) { 13.dp.toPx() }
    val thumbRange = (trackWidth - thumbPx).coerceAtLeast(0f)

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = dragging, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    "${clock(if (dragging) (dragFraction * durationMs).toLong() else positionMs)} / ${clock(durationMs)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color(0xCC141419), RoundedCornerShape(50))
                        .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onSizeChanged { trackWidth = it.width }
                .pointerInput(item.id) {
                    awaitEachGesture {
                        // 按下先不消费：纵向滑动要原样交给翻页
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var fraction = (down.position.x / size.width).coerceIn(0f, 1f)
                        var isDrag = false
                        var cancelled = false
                        var resume = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) { cancelled = true; break }   // 翻页接管了
                            if (!change.pressed) break
                            if (!isDrag &&
                                abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
                            ) {
                                isDrag = true
                                resume = player.isPlaying
                                player.pause()
                                dragging = true
                            }
                            if (isDrag) {
                                fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                dragFraction = fraction
                                change.consume()
                            }
                        }
                        // 松手定位：拖动取最后位置，点按取按下位置
                        if (!cancelled && durationNow > 0L) {
                            player.seekTo((fraction * durationNow).toLong())
                        }
                        if (isDrag) {
                            dragging = false
                            if (resume) player.play()
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x59FFFFFF), RoundedCornerShape(50))
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.001f, 1f))
                    .height(3.dp)
                    .background(Color.White, RoundedCornerShape(50))
            )
            Box(
                Modifier
                    .offset { IntOffset((thumbRange * fraction).roundToInt(), 0) }
                    .size(13.dp)
                    .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                    .background(Color.White, CircleShape)
            )
        }
    }
}

/** 毫秒 → m:ss，超过一小时进位成 h:mm:ss */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
