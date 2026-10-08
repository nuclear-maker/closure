package com.closure.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 「翻篇」品牌视觉：
 * 日落珊瑚 → 紫罗兰的对角渐变，配深空黑底。参考汽水音乐/抖音的高饱和暗色风。
 */
object Brand {
    val Coral = Color(0xFFFF6B6B)
    val Violet = Color(0xFF8B7CFF)
    val Bg = Color(0xFF0A0A0F)

    /** 主按钮 / CTA 的品牌渐变 */
    val buttonGradient = Brush.linearGradient(listOf(Color(0xFFFF7A59), Color(0xFFFF5E7E), Color(0xFF8B5CFF)))

    /** 启动图标背景渐变 */
    val launcherColors = listOf(Color(0xFFFF8A5B), Color(0xFFFF5E7E), Color(0xFF8B5CFF))

    /** 玻璃质感（半透明白）圆形按钮 */
    val GlassShape = androidx.compose.foundation.shape.RoundedCornerShape(50)
}
