# Amigo 安卓 APP

把博客（朋友圈）+ AI 机器人管理页打包成原生安卓 APP。底部 Tab：`朋友圈`（加载博客站点）/ `设置`（加载 `/ai-bot-admin/` 管理页）。原生 Kotlin + WebView，依赖 AndroidX Material，暗色主题与博客一致。

## 目录结构

```
amigo-app/
├── app/src/main/java/com/amigo/app/MainActivity.kt   # 双 Tab + WebView
├── app/src/main/res/                                  # 布局、菜单、图标、主题
└── gradlew.bat                                        # 已带 Gradle Wrapper 8.9
```

## 构建 APK

环境要求：JDK 17+、Android SDK（platform 34 + build-tools 34.0.0）。

```bash
# 1. 配置 SDK 路径（Windows 示例）
#    写 amigo-app/local.properties：
sdk.dir=C\:\\Android\\sdk

# 2. 构建 debug 包
./gradlew.bat :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 中文路径项目可构建：项目 `gradle.properties` 已带 `android.overridePathCheck=true`。
> 首次构建需网络拉依赖；国内网络需让 Gradle JVM 走代理（用户级 `~/.gradle/gradle.properties` 加 `systemProp.https.proxyHost/Port`）。

## 配置博客地址

编辑 `MainActivity.kt` 顶部常量：

```kotlin
private val blogUrl = "https://你的博客.com"   // 改成你的线上地址，必须 https
```

「设置」Tab 自动加载 `blogUrl + "/ai-bot-admin/"`，管理 API 走 Nginx 同域反代（见 `ai-bot/README.md` 管理 API 一节）。

## 发布（release 签名）

```bash
# 生成签名（只跑一次）
keytool -genkey -v -keystore amigo.keystore -alias amigo -keyalg RSA -keysize 2048 -validity 10000

# 在 app/build.gradle 的 android.signingConfigs 配好 keystore 路径/密码后：
./gradlew.bat :app:assembleRelease
```

安装测试：`adb install app/build/outputs/apk/debug/app-debug.apk`。

## 说明

- WebView 开启 JS + DOM Storage（管理页 token 存 localStorage）
- 站内跳转留在 WebView 内；Android 返回键先退回上页
- 图标为微信绿自适应图标，minSdk 26（Android 8.0+）