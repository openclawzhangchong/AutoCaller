# 自动拨号器 (AutoCaller)

## 功能

设置指定号码 → 设定拨打次数和每次通话时长 → 自动拨号 + 自动挂断。

## 核心原理

| 环节 | 技术方案 |
|------|---------|
| 拨号 | `Intent.ACTION_CALL`（系统API，需 `CALL_PHONE` 权限） |
| 通话计时 | 无障碍服务监听电话App界面变化 |
| 挂断 | AccessibilityService `GLOBAL_ACTION_END_CALL`（Android 9+ 原生API） |
| 任务调度 | 前台 Service + Kotlin 协程 |

## 编译方法

### Windows / macOS / Linux

**方式一：Android Studio（推荐）**

1. 打开 Android Studio → File → Open → 选择 `auto-caller/` 文件夹
2. 等待 Gradle 同步完成（会自动下载 SDK 和依赖）
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. 输出在 `app/build/outputs/apk/debug/app-debug.apk`

**方式二：命令行（需安装 JDK 17+ 和 Android SDK）**

```bash
# 设置 ANDROID_HOME 环境变量指向你的 SDK 目录
export ANDROID_HOME=/path/to/android/sdk
# Windows:
# set ANDROID_HOME=C:\Users\xxx\AppData\Local\Android\Sdk

# 给 Gradle 执行权限（macOS/Linux）
chmod +x gradlew

# 编译 Debug APK
./gradlew assembleDebug
```

## 使用步骤

1. 安装 APK 到手机
2. 打开 App → 允许「拨打电话」和「读取电话状态」权限
3. **关键步骤**：去系统设置开启「自动拨号器」的无障碍服务
   - 设置 → 辅助功能/无障碍 → 已安装的应用 → 自动拨号器 → 开启
4. 输入号码、次数、时长、间隔 → 点击「开始呼叫」

## 权限说明

| 权限 | 用途 |
|------|------|
| `CALL_PHONE` | 直接拨打电话 |
| `READ_PHONE_STATE` | 监听电话状态（振铃/通话中/空闲） |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| `BIND_ACCESSIBILITY_SERVICE` | 自动挂断 |