# Closure（翻篇）

像刷短视频一样整理你的相册：全屏竖滑，边刷边决定去留，攒一批、一次翻篇。

> 照片不会消失，只是翻过去的那一页，终于合上了。

## 品牌故事

（待补充）

## 功能特性

- **竖滑刷相册**：照片、视频、实况照片、GIF 混在一起随机刷，预加载前后各一张
- **翻篇（删除）**：点「翻篇」攒一批，最后一次性系统确认删除；删除内容进系统相册回收站，30 天内可反悔
- **收藏**：心形收藏直接写入系统相册（`MediaStore.IS_FAVORITE`），其他相册 App 也能识别；支持只看收藏
- **实况照片**：识别各厂商 Motion Photo，提取内嵌视频原地播放
- **长图展开**：低清整图垫底 + 按内容坐标分块的瓦片式高清解码，滚动时已解区域保持清晰，任意长度不爆内存
- **视频**：自动播放循环、静音开关、进度条点按/拖动定位、横屏视频全屏自动旋转
- **照片手势**：双击沉浸全屏、双指缩放、长图双击展开/收起
- **极速翻篇（可选）**：授予「所有文件访问」后删除不再弹系统确认框
- **纯本地**：不联网、不上传，没有任何分析统计

## 截图

（待补充）

## 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.1 |
| UI | Jetpack Compose（Material 3）、VerticalPager |
| 图片 | Coil 2.7（含 GIF、视频首帧解码） |
| 视频 | Media3 ExoPlayer 1.7 |
| 系统 | minSdk 30（Android 11+），target/compile 36 |

## 构建与运行

```bash
git clone https://github.com/<you>/closure.git
cd closure
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
```

或直接用 Android Studio（Ladybug 及以上）打开工程，插上手机点 Run。

- 首次启动会先看到介绍页，点「开始翻篇」才会申请照片/视频权限
- Android 14 的「仅允许访问选中的照片」部分授权同样可用
- 极速翻篇需要额外授予「所有文件访问」，不授予也能正常用（走系统确认弹窗）

## 项目结构

```
app/src/main/java/com/skylerlin/closure/
├── MainActivity.kt        # 入口与权限时序（首屏 → 点开始 → 授权）
├── ClosureApp.kt          # 应用级初始化（Coil 解码器注册：GIF/视频帧）
├── data/                  # MediaStore 读取、收藏写入、媒体模型
├── media/                 # Motion Photo 识别与内嵌视频提取
└── ui/
    ├── feed/              # 刷流页（分页、视频播放、长图瓦片、批量删除）
    └── theme/             # 品牌色与主题
```

## 贡献

Issue 与 PR 都欢迎。提交前请跑一遍 `./gradlew assembleDebug` 确保能编译通过。

## 许可证

[MIT](LICENSE)
