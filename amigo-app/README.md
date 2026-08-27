# Amigo 原生安卓朋友圈 APP

本地私人朋友圈 APP（原生 Kotlin，无 WebView、不依赖博客服务器）：发图文 → AI 角色像朋友一样评论/互聊/点赞，数据全部存在手机本地，可一键导出/导入备份迁移。

## 功能

- **朋友圈**：发图文（系统相册多选，最多 9 张）、九宫格/大图布局、点赞、评论、评论弹层
- **AI 角色**：自由创建多个角色（昵称/人设/头像/活跃度/点赞率），挚友每帖必回，其余角色按活跃度随机出现
- **拟人机制**：发帖后每个角色有随机"刷到时刻"（不秒回）；AI 之间会概率接话（级联最多 2 轮）；可点赞/潜水
- **AI 大脑可换**：设置页填 OpenAI 兼容 `chat/completions` 接口地址 + Key + 模型（DeepSeek/通义/Kimi 等）；不填则用内置兜底文案演示
- **完全本地**：Room 数据库 + 应用私有目录图片，不联网即可用（仅 LLM 接口需要网络）
- **备份**：设置页一键导出 `.zip`（数据 + 图片）到任意位置，导入即恢复，换机迁移
- **后台定时**：WorkManager 每 15 分钟自动处理到期的 AI 评论（可开关）

## 目录结构

```
amigo-app/
├── app/src/main/java/com/amigo/app/
│   ├── MainActivity.kt             # 底部双 Tab（朋友圈/设置）
│   ├── PostEditorActivity.kt       # 发朋友圈（文字 + 图片）
│   ├── CharacterEditActivity.kt    # AI 角色增删改
│   ├── data/                       # Room 实体/DAO/数据库/设置仓库
│   ├── ai/                         # LLM 客户端 + AI 引擎（移植 ai-bot 逻辑）
│   ├── ui/                         # Feed 列表、适配器、评论弹层、设置页
│   ├── util/                       # 图片存储、头像、备份导出导入
│   └── work/                       # WorkManager 定时 AI worker
└── app/src/main/res/               # 布局、菜单、图标、暗色主题（微信绿）
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

## 发布（release 签名）

```bash
# 生成签名（只跑一次）
keytool -genkey -v -keystore amigo.keystore -alias amigo -keyalg RSA -keysize 2048 -validity 10000

# 在 app/build.gradle 的 android.signingConfigs 配好 keystore 路径/密码后：
./gradlew.bat :app:assembleRelease
```

安装测试：`adb install app/build/outputs/apk/debug/app-debug.apk`。

## 使用流程

1. 打开 APP → 「设置」→ 建 1~2 个角色（勾选至少一个「挚友」保证每帖有回复）
2. 想接真实 AI：设置页填 LLM 接口地址 / Key / 模型，保存；不填也能用兜底文案跑通
3. 「朋友圈」→ 点顶部或空状态 → 写内容选图 → 发表
4. 等几分钟（挚友快、普通角色慢），下拉刷新/重进即可看到 AI 评论
5. 换机迁移：设置页「导出备份」→ 新机「导入备份」

## 说明

- minSdk 26（Android 8.0+），暗色主题与博客 UI 一致（#111111 底、#1e1e1e 卡片、微信绿 #07C160）
- 数据全部在应用私有目录，卸载即清空；请定期导出备份
- 图片选择走系统 Photo Picker（无需存储权限）