package com.closure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.closure.ui.feed.FeedScreen
import com.closure.ui.theme.Brand
import com.closure.ui.theme.ClosureTheme

private const val PREFS_NAME = "closure_prefs"
private const val KEY_ONBOARDED = "onboarded"

class MainActivity : ComponentActivity() {

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPermission(): Boolean {
        if (requiredPermissions().any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) return true
        // Android 14 “仅允许访问选中的照片”的部分授权也视为可用
        return Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClosureTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Bg) {
                    AppEntry(
                        requiredPermissions = requiredPermissions(),
                        checkPermission = { hasMediaPermission() }
                    )
                }
            }
        }
    }
}

/**
 * 启动时序：首次安装先进欢迎页、**不自动弹权限**，点「开始翻篇」才请求；
 * 授权结果落盘，之后启动不再出现欢迎页——没权限时直接进权限说明页。
 */
@Composable
private fun AppEntry(
    requiredPermissions: Array<String>,
    checkPermission: () -> Boolean,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var granted by remember { mutableStateOf(checkPermission()) }
    var onboarded by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 无论授权还是拒绝，都算走过了欢迎页，下次直接进对应页面
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        onboarded = true
        granted = checkPermission()
    }

    // 去系统设置授权后返回时重新判定，不必重启 App
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = checkPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        granted -> FeedScreen()
        !onboarded -> WelcomeScreen(onStart = { launcher.launch(requiredPermissions) })
        else -> PermissionDeniedScreen(onRetry = { launcher.launch(requiredPermissions) })
    }
}

/** 首屏：只负责讲清楚「翻篇是什么」，点了开始才去要权限 */
@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandIntro()
        Spacer(Modifier.height(40.dp))
        GradientButton("开始翻篇", onStart)
    }
}

/** 权限被拒后的说明页：不再重复品牌介绍，专注给出路 */
@Composable
private fun PermissionDeniedScreen(onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(brush = Brand.buttonGradient, shape = RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Style, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(22.dp))
        Text("还差一步", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "翻篇要读你的相册才能开工。\n没有权限，一张都翻不动。",
            color = Color(0xFFB9BEC7), fontSize = 15.sp,
            textAlign = TextAlign.Center, lineHeight = 24.sp
        )
        Spacer(Modifier.height(40.dp))
        GradientButton("重新授权", onRetry)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { openAppSettings(context) }) {
            Text("或去系统设置开启相册权限", color = Color(0xFF9AA0A6), fontSize = 13.sp)
        }
    }
}

@Composable
private fun BrandIntro() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(brush = Brand.buttonGradient, shape = RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Style, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(34.dp)
        )
    }
    Spacer(Modifier.height(22.dp))
    Text("翻篇", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text(
        "把相册从头刷到尾：\n该收藏的收藏，该翻篇的翻篇。",
        color = Color(0xFFB9BEC7), fontSize = 15.sp,
        textAlign = TextAlign.Center, lineHeight = 24.sp
    )
    Spacer(Modifier.height(36.dp))
    FeatureRow(Icons.Rounded.Lock, "只在你手机里", "不联网、不上传，刷完还是你的")
    Spacer(Modifier.height(16.dp))
    FeatureRow(Icons.Rounded.Bolt, "照片、视频、实况，混着刷", "随机顺序，每翻一次都是新感觉")
    Spacer(Modifier.height(16.dp))
    FeatureRow(Icons.Rounded.Restore, "翻篇有后悔药", "删除先进系统回收站，30 天内随时反悔")
}

/** 品牌主按钮：渐变胶囊 + 按压缩放 */
@Composable
private fun GradientButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "ctaScale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(brush = Brand.buttonGradient, RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    )
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0x1AFFFFFF), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Brand.Coral, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF9AA0A6), fontSize = 13.sp)
        }
    }
}
