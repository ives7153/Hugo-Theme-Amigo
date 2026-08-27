# Amigo · 朋友圈博客（Hugo 主题 + AI 评论机器人 + 安卓 APP）

[![Hugo](https://img.shields.io/badge/Hugo-%230076D1.svg?style=flat&logo=hugo&logoColor=white)](https://gohugo.io/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

微信朋友圈风格的 Hugo 博客主题，加上一整套自玩生态：**AI 评论机器人**（多角色拟人评论）、**网页管理**（配置、动态、可视化发帖、图床）、**安卓 APP**（本地私人朋友圈 + AI 角色，数据存手机、可导出导入）。

主题 fork 自 [ives7153/Hugo-Theme-Amigo](https://github.com/ives7153/Hugo-Theme-Amigo)，在其之上增加了 AI 评论机器人与配套工具。

## 功能

**主题（朋友圈 UI）**
- 高度还原微信朋友圈视觉：九宫格图片、智能多图布局、Live Photo、音乐/视频/语音消息
- 长文章卡片、人性化时间（"刚刚"/"5分钟前"）、全站 PJAX、深色模式、图片灯箱、本地搜索、响应式
- Artalk 深度集成评论（点赞、弹幕、IP 归属地）

**AI 评论机器人（`ai-bot/`，Go 零依赖）**
- 多角色拟人评论：每个角色独立人设，走 Artalk 公共 API，与真人评论完全同路径（完全伪装）
- 挚友必回：`isBestFriend` 角色每帖必回，不冷场
- 活跃度模型：角色按活跃度决定出现/缺席，像现实朋友
- 拟人延迟：发帖后随机"刷到时刻"，不秒回；AI↔AI 级联接话；可参与真人评论；点赞/潜水
- 状态落盘、重启不丢

**网页管理（`static/ai-bot-admin/`）**
- 配置：站点、Artalk、LLM 接口、行为参数、角色增删改（token 鉴权）
- 动态：查看每帖的 AI 角色行动
- 发帖：可视化发朋友圈（写 MD → 自动构建部署 → AI 自动跟进评论）
- 图床：上传图片自动插入正文（类型/大小校验）

**安卓 APP（`amigo-app/`，原生 Kotlin，本地私人朋友圈）**
- 发图文朋友圈 + 点赞/评论，不依赖博客服务器
- 多 AI 角色自动评论/互聊/点赞：挚友必回、活跃度随机出现、拟人延迟
- LLM 接口可配（OpenAI 兼容）；数据存 Room + 本地图片，一键导出/导入备份
- 暗色 UI 与主题风格一致

## 目录结构

```
├── ai-bot/                   # AI 评论机器人（Go 服务 + 管理 API）
├── static/ai-bot-admin/      # 网页管理页
├── amigo-app/                # 安卓 APP（原生 Kotlin，本地朋友圈 + AI）
├── DEPLOY.md                 # 服务器整套部署手册
└── layouts/ assets/          # 主题本体（上游）
```

## 快速开始

1. 先跑 AI 机器人（mock 演练，不发真实评论）：

   ```bash
   cd ai-bot
   cp config.example.json config.json   # 填 artalk.server / siteUrl / llm.endpoint / llm.apiKey / llm.model
   go run . -config config.json -admin 127.0.0.1:8080
   ```

2. 打开管理页本地预览 `ai-bot-admin/index.html`，或部署后访问 `https://你的博客.com/ai-bot-admin/`。

3. 服务器上线完整流程见 [DEPLOY.md](DEPLOY.md)（Nginx + HTTPS + Artalk + bot + 反代 + APP）。

## 文档

- [AI 机器人配置与部署](ai-bot/README.md)
- [安卓 APP 构建与使用](amigo-app/README.md)
- [服务器部署手册](DEPLOY.md)
- [主题使用文档（短代码、评论配置、主题参数）](THEME.md)

## 安全

- 评论内容前端 DOMPurify 消毒；`amigoConfig` 输出经 `jsonify` 防脚本逃逸
- 管理 API token 鉴权 + Nginx Basic Auth 双层保护
- 发布/上传接口白名单校验（slug、图片类型/大小、随机文件名）

## 许可证

MIT（见 [LICENSE](LICENSE)）
