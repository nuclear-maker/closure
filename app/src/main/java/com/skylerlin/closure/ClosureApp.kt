package com.skylerlin.closure

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder

class ClosureApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
                // GIF 动图：Movie 方案优先（兼容性最稳），再兜底系统的 ImageDecoder
                add(GifDecoder.Factory())
                add(ImageDecoderDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
