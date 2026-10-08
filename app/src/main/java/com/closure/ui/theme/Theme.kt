package com.closure.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun ClosureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.Coral,
            secondary = Brand.Violet,
            background = Brand.Bg,
            surface = Brand.Bg
        ),
        content = content
    )
}
